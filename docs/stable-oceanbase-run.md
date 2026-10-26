# A22 stable run guide

Run this setup once on the ECS server when OceanBase keeps failing because of memory.
It keeps OceanBase data volumes and only recreates the OceanBase container with a
low-memory configuration.

## One-time ECS setup

In Alibaba Cloud ECS terminal:

```bash
cd /opt/a22/a22
bash ops/setup-ecs-oceanbase-stable.sh
```

If the `ops` folder is not on ECS yet, copy `ops/setup-ecs-oceanbase-stable.sh`
from the project to `/opt/a22/a22/ops/` first.

Expected success output includes:

```text
OceanBase is ready.
```

## Daily local run

Open PowerShell window 1 and keep it open:

```powershell
powershell -ExecutionPolicy Bypass -File "D:\a22(3)\a22\start-oceanbase-tunnel.ps1"
```

Open PowerShell window 2:

```powershell
$env:A22_DB_PASSWORD='52ecyyyds'
powershell -ExecutionPolicy Bypass -File "D:\a22(3)\a22\start-a22.ps1" -DbUsername "root@test" -DbPassword "52ecyyyds"
```

Then open:

```text
http://127.0.0.1:5173
```

Do not remove Docker volumes unless you intentionally want to delete database data.
