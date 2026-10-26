# VMware Workstation MCP

这是一个面向本项目的最小 MCP Server，使用 `vmrun` 控制 VMware Workstation。

## 启动

```powershell
D:\a22\tools\vmware-workstation-mcp\.venv\Scripts\python.exe D:\a22\tools\vmware-workstation-mcp\server.py
```

默认工具包括虚拟机查询、电源操作、快照和客户机 IP 查询。所有改变虚拟机状态的工具都要求 `confirm=true`。

## 环境变量

- `VMWARE_VMRUN`：`vmrun.exe` 路径，默认 `D:\vmware\vmrun.exe`。
- `VMWARE_HOST_TYPE`：Workstation 类型，默认 `ws`。
- `VMWARE_INVENTORY`：虚拟机清单路径，默认 `%APPDATA%\VMware\inventory.vmls`。
- `VMWARE_VM_ROOTS`：额外扫描 `.vmx` 的目录，使用系统路径分隔符。
- `VMWARE_ALLOWED_VMS`：允许操作的 VMX 路径，留空表示不额外限制。
- `VMWARE_ENABLE_GUEST_EXECUTION`：压力测试执行开关，默认 `false`。
- `VMWARE_GUEST_USER` / `VMWARE_GUEST_PASSWORD`：客户机凭据，不写入源码。
- `VMWARE_ALLOWED_GUEST_PROGRAMS`：客户机可执行程序白名单。

客户机执行默认关闭。开启前请确认虚拟机快照、网络隔离和测试数据均已准备。
