"""VMware Workstation MCP 服务。"""

from __future__ import annotations

import asyncio
import os
import re
import subprocess
from pathlib import Path
from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError


VMRUN_PATH = Path(os.getenv("VMWARE_VMRUN", r"D:\vmware\vmrun.exe"))
HOST_TYPE = os.getenv("VMWARE_HOST_TYPE", "ws")
INVENTORY_PATH = Path(
    os.getenv(
        "VMWARE_INVENTORY",
        str(Path(os.getenv("APPDATA", "")) / "VMware" / "inventory.vmls"),
    )
)
VM_ROOTS = [
    Path(item)
    for item in os.getenv("VMWARE_VM_ROOTS", "").split(os.pathsep)
    if item.strip()
]
GUEST_EXECUTION_ENABLED = os.getenv(
    "VMWARE_ENABLE_GUEST_EXECUTION", "false"
).casefold() in {"1", "true", "yes", "on"}
GUEST_ALLOWED_PROGRAMS = {
    item.strip().casefold()
    for item in os.getenv(
        "VMWARE_ALLOWED_GUEST_PROGRAMS",
        "k6,wrk,ab,hey,vegeta,jmeter,python,bash,sh,powershell,cmd,curl",
    ).split(",")
    if item.strip()
}

server = MCPServer(
    name="vmware-workstation",
    title="VMware Workstation",
    version="0.1.0",
    instructions=(
        "用于只读查询和受确认保护的 VMware Workstation 操作。"
        "破坏性操作必须显式传入 confirm=true。"
    ),
)


def _quote_value(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        value = value[1:-1]
    return value.replace('\\"', '"')


def _read_text(path: Path) -> str:
    data = path.read_bytes()
    for encoding in ("gbk", "utf-8"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def _display_name(path: Path) -> str:
    try:
        match = re.search(r'^displayName\s*=\s*"(.*)"\s*$', _read_text(path), re.MULTILINE)
        if match:
            return match.group(1).replace('\\"', '"')
    except OSError:
        pass
    return path.stem


def _inventory_entries() -> list[dict[str, Any]]:
    if not INVENTORY_PATH.is_file():
        return []
    entries: dict[str, dict[str, Any]] = {}
    for line in _read_text(INVENTORY_PATH).splitlines():
        config = re.match(r"^vmlist(\d+)\.config\s*=\s*(.*)$", line.strip())
        if config:
            item_id = config.group(1)
            entries.setdefault(item_id, {"path": Path(_quote_value(config.group(2)))})
            continue
        display = re.match(r"^vmlist(\d+)\.DisplayName\s*=\s*(.*)$", line.strip())
        if display:
            entries.setdefault(display.group(1), {})["name"] = _quote_value(display.group(2))
    result: list[dict[str, Any]] = []
    for item_id in sorted(entries, key=int):
        item = entries[item_id]
        path = item["path"]
        if path.suffix.casefold() != ".vmx":
            continue
        result.append(
            {
                "name": item.get("name") or _display_name(path),
                "path": str(path),
                "registered": True,
            }
        )
    return result


def _collect_entries() -> list[dict[str, Any]]:
    entries = _inventory_entries()
    seen = {Path(item["path"]).absolute().as_posix().casefold() for item in entries}
    for root in VM_ROOTS:
        if not root.is_dir():
            continue
        for vmx_path in root.rglob("*.vmx"):
            key = vmx_path.absolute().as_posix().casefold()
            if key in seen:
                continue
            seen.add(key)
            entries.append(
                {
                    "name": _display_name(vmx_path),
                    "path": str(vmx_path),
                    "registered": False,
                }
            )
    return sorted(entries, key=lambda item: (item["name"].casefold(), item["path"].casefold()))


def _redact(text: str) -> str:
    text = re.sub(r"(?i)(-gp\s+)(\S+)", r"\1***", text)
    return re.sub(r"(?i)(password\s*[=:]\s*)\S+", r"\1***", text)


def _run_vmrun(args: list[str], timeout: int = 60) -> str:
    if not VMRUN_PATH.is_file():
        raise ToolError(f"找不到 vmrun: {VMRUN_PATH}")
    command = [str(VMRUN_PATH), "-T", HOST_TYPE, *args]
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except subprocess.TimeoutExpired as exc:
        raise TimeoutError(f"vmrun 超过 {timeout} 秒未返回") from exc
    output = f"{result.stdout}\n{result.stderr}".strip()
    if result.returncode != 0:
        raise RuntimeError(f"vmrun 执行失败（退出码 {result.returncode}）：{_redact(output)}")
    return output.strip()


async def _run_vmrun_async(args: list[str], timeout: int = 60) -> str:
    return await asyncio.to_thread(_run_vmrun, args, timeout)


def _running_paths() -> set[str]:
    output = _run_vmrun(["list"])
    running: set[str] = set()
    for line in output.splitlines():
        value = line.strip().strip('"')
        if value.casefold().endswith(".vmx"):
            running.add(Path(value).absolute().as_posix().casefold())
    return running


def _allowed_vms() -> set[str]:
    raw = os.getenv("VMWARE_ALLOWED_VMS", "")
    return {
        Path(item).absolute().as_posix().casefold()
        for item in raw.split(os.pathsep)
        if item.strip()
    }


def _resolve_vm(reference: str) -> Path:
    candidate = Path(reference).expanduser()
    if candidate.is_absolute() and candidate.suffix.casefold() == ".vmx":
        path = candidate
    else:
        matches = [
            item
            for item in _collect_entries()
            if item["name"].casefold() == reference.casefold()
            or Path(item["path"]).as_posix().casefold() == candidate.as_posix().casefold()
        ]
        if not matches:
            raise ToolError(f"找不到虚拟机：{reference}")
        if len(matches) > 1:
            raise ToolError(f"虚拟机名称不唯一，请改用 VMX 路径：{reference}")
        path = Path(matches[0]["path"])
    if not path.is_file():
        raise ToolError(f"VMX 文件不存在：{path}")
    allowed = _allowed_vms()
    if allowed and path.absolute().as_posix().casefold() not in allowed:
        raise ToolError(f"虚拟机不在 VMWARE_ALLOWED_VMS 允许列表中：{path}")
    return path


def _require_confirmation(confirm: bool) -> None:
    if not confirm:
        raise ToolError("该操作会改变虚拟机状态，请显式传入 confirm=true")


def _require_guest_execution() -> tuple[str, str]:
    if not GUEST_EXECUTION_ENABLED:
        raise ToolError(
            "客户机执行已禁用；压测前请在 MCP 环境变量中设置 "
            "VMWARE_ENABLE_GUEST_EXECUTION=true"
        )
    user = os.getenv("VMWARE_GUEST_USER", "")
    password = os.getenv("VMWARE_GUEST_PASSWORD")
    if not user or password is None:
        raise ToolError("请通过 MCP 环境变量配置 VMWARE_GUEST_USER 和 VMWARE_GUEST_PASSWORD")
    return user, password


@server.tool(
    description="列出已注册及可发现的 VMware Workstation 虚拟机，并标记当前是否运行。",
)
async def list_vms() -> dict[str, Any]:
    entries = await asyncio.to_thread(_collect_entries)
    running = await asyncio.to_thread(_running_paths)
    return {
        "vms": [
            {
                **entry,
                "running": Path(entry["path"]).absolute().as_posix().casefold() in running,
            }
            for entry in entries
        ]
    }


@server.tool(description="查询指定虚拟机的名称、路径和运行状态。")
async def get_vm_status(vm: str) -> dict[str, Any]:
    path = _resolve_vm(vm)
    running = await asyncio.to_thread(_running_paths)
    key = path.absolute().as_posix().casefold()
    return {
        "name": _display_name(path),
        "path": str(path),
        "running": key in running,
        "state": "running" if key in running else "not_running",
    }


@server.tool(description="启动指定虚拟机；需要 confirm=true。")
async def power_on_vm(vm: str, confirm: bool) -> dict[str, Any]:
    _require_confirmation(confirm)
    path = _resolve_vm(vm)
    output = await _run_vmrun_async(["start", str(path), "nogui"], timeout=120)
    return {"path": str(path), "action": "start", "output": output}


@server.tool(description="关闭指定虚拟机；需要 confirm=true，可选择 soft 或 hard。")
async def power_off_vm(vm: str, confirm: bool, mode: str = "soft") -> dict[str, Any]:
    _require_confirmation(confirm)
    if mode not in {"soft", "hard"}:
        raise ValueError("mode 必须是 soft 或 hard")
    path = _resolve_vm(vm)
    output = await _run_vmrun_async(["stop", str(path), mode], timeout=120)
    return {"path": str(path), "action": "stop", "mode": mode, "output": output}


@server.tool(description="重置指定虚拟机；需要 confirm=true，可选择 soft 或 hard。")
async def reset_vm(vm: str, confirm: bool, mode: str = "soft") -> dict[str, Any]:
    _require_confirmation(confirm)
    if mode not in {"soft", "hard"}:
        raise ValueError("mode 必须是 soft 或 hard")
    path = _resolve_vm(vm)
    output = await _run_vmrun_async(["reset", str(path), mode], timeout=120)
    return {"path": str(path), "action": "reset", "mode": mode, "output": output}


@server.tool(description="挂起指定虚拟机；需要 confirm=true，可选择 soft 或 hard。")
async def suspend_vm(vm: str, confirm: bool, mode: str = "soft") -> dict[str, Any]:
    _require_confirmation(confirm)
    if mode not in {"soft", "hard"}:
        raise ValueError("mode 必须是 soft 或 hard")
    path = _resolve_vm(vm)
    output = await _run_vmrun_async(["suspend", str(path), mode], timeout=120)
    return {"path": str(path), "action": "suspend", "mode": mode, "output": output}


@server.tool(description="列出指定虚拟机的快照。")
async def list_snapshots(vm: str) -> dict[str, Any]:
    path = _resolve_vm(vm)
    output = await _run_vmrun_async(["listSnapshots", str(path)])
    return {"path": str(path), "output": output}


@server.tool(description="创建指定虚拟机的快照；需要 confirm=true。")
async def create_snapshot(vm: str, snapshot_name: str, confirm: bool) -> dict[str, Any]:
    _require_confirmation(confirm)
    if not snapshot_name.strip():
        raise ValueError("snapshot_name 不能为空")
    path = _resolve_vm(vm)
    output = await _run_vmrun_async(["snapshot", str(path), snapshot_name])
    return {"path": str(path), "snapshot": snapshot_name, "output": output}


@server.tool(description="恢复到指定快照；需要 confirm=true。")
async def revert_snapshot(vm: str, snapshot_name: str, confirm: bool) -> dict[str, Any]:
    _require_confirmation(confirm)
    path = _resolve_vm(vm)
    output = await _run_vmrun_async(["revertToSnapshot", str(path), snapshot_name])
    return {"path": str(path), "snapshot": snapshot_name, "output": output}


@server.tool(description="删除指定快照；需要 confirm=true，可选择是否删除子快照。")
async def delete_snapshot(
    vm: str, snapshot_name: str, confirm: bool, delete_children: bool = False
) -> dict[str, Any]:
    _require_confirmation(confirm)
    path = _resolve_vm(vm)
    args = ["deleteSnapshot", str(path), snapshot_name]
    if delete_children:
        args.append("andDeleteChildren")
    output = await _run_vmrun_async(args)
    return {"path": str(path), "snapshot": snapshot_name, "output": output}


@server.tool(description="获取指定虚拟机客户机 IP 地址。")
async def get_vm_ip(vm: str, wait: bool = False) -> dict[str, Any]:
    path = _resolve_vm(vm)
    args = ["getGuestIPAddress", str(path)]
    if wait:
        args.append("-wait")
    output = await _run_vmrun_async(args, timeout=120)
    return {"path": str(path), "output": output}


@server.tool(
    description=(
        "在客户机中运行受允许列表限制的程序，用于压力测试；"
        "需要启用环境开关并传入 confirm=true。"
    ),
)
async def run_guest_program(
    vm: str,
    program: str,
    args: list[str] | None = None,
    confirm: bool = False,
    no_wait: bool = False,
    timeout_seconds: int = 300,
) -> dict[str, Any]:
    _require_confirmation(confirm)
    user, password = _require_guest_execution()
    program_name = Path(program.replace("\\", "/")).name.casefold()
    if program_name not in GUEST_ALLOWED_PROGRAMS:
        raise ToolError(
            f"程序不在 VMWARE_ALLOWED_GUEST_PROGRAMS 允许列表中：{program_name}"
        )
    path = _resolve_vm(vm)
    running = await asyncio.to_thread(_running_paths)
    if path.absolute().as_posix().casefold() not in running:
        raise RuntimeError("虚拟机未运行，无法执行客户机程序")
    command = ["-gu", user, "-gp", password, "runProgramInGuest", str(path)]
    if no_wait:
        command.append("-noWait")
    command.append(program)
    command.extend(args or [])
    output = await _run_vmrun_async(command, timeout=timeout_seconds)
    return {"path": str(path), "program": program, "output": output}


def main() -> None:
    server.run(transport="stdio")


if __name__ == "__main__":
    main()
