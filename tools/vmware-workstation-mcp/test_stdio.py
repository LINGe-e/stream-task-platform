"""通过 MCP stdio 客户端验证 VMware Workstation Server。"""

from __future__ import annotations

import anyio
from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


async def main() -> None:
    params = StdioServerParameters(
        command=r"D:\a22\tools\vmware-workstation-mcp\.venv\Scripts\python.exe",
        args=[r"D:\a22\tools\vmware-workstation-mcp\server.py"],
        cwd=r"D:\a22",
        encoding="utf-8",
    )
    async with stdio_client(params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()
            tools = await session.list_tools()
            names = {tool.name for tool in tools.tools}
            assert {
                "list_vms",
                "get_vm_status",
                "power_on_vm",
                "power_off_vm",
                "list_snapshots",
                "run_guest_program",
            } <= names

            listing = await session.call_tool("list_vms", {})
            assert not listing.is_error
            assert "vms" in listing.structured_content
            vms = listing.structured_content["vms"]
            assert vms
            first_path = vms[0]["path"]

            blocked = await session.call_tool(
                "power_on_vm",
                {"vm": first_path, "confirm": False},
            )
            assert blocked.is_error
            assert "confirm=true" in str(blocked.content)

            status = await session.call_tool(
                "get_vm_status",
                {"vm": first_path},
            )
            assert not status.is_error
            assert status.structured_content["path"] == first_path
            print(f"tools={len(names)} vms={len(vms)} safety_check=passed")


if __name__ == "__main__":
    anyio.run(main)
