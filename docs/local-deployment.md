# A22 local deployment

This deployment runs the platform on the local machine. It does not require an ECS host or an SSH tunnel.

## 1. Start local infrastructure

From the project root, start only the unified CSV baseline services:

```powershell
docker compose up -d mysql redis
```

Start Kafka and Flink only when a streaming test is explicitly required:

```powershell
docker compose --profile streaming up -d kafka jobmanager taskmanager
```

Start OceanBase only when the canvas uses the OceanBase output node. It needs about 8 GB of available Docker memory:

```powershell
$env:OCEANBASE_PASSWORD = 'root'
docker compose --profile oceanbase up -d oceanbase
```

The local ports are:

| Service | Address |
|---|---|
| MySQL platform database | `127.0.0.1:3307` |
| OceanBase output database | `127.0.0.1:2881` |
| Redis | `127.0.0.1:6379` |
| Kafka | `127.0.0.1:9092` |
| Flink | `127.0.0.1:8081` |

## 2. Start the application

The default startup script now uses local MySQL on port `3307`:

```powershell
$env:MYSQL_ROOT_PASSWORD = 'root'
powershell -ExecutionPolicy Bypass -File .\start-a22.ps1
```

H2 is test-only and is not part of the unified runtime baseline. Do not use an H2 profile for CSV or performance tests.

The application is then available at:

```text
http://127.0.0.1:5173
```

## 3. Local HDFS mode

HDFS nodes use Hadoop's local filesystem by default. No NameNode or SSH tunnel is needed.

The application variable is:

```text
HDFS_DEFAULT_FS=file:///
```

For the default canvas nodes, input files are read from `backend/hdfs-data/input` and output files are written to `backend/hdfs-data/output` when the backend is started by `start-a22.ps1`.

## 4. OceanBase canvas output

Add the `OceanBase Output` node and use the default connection, or configure it as:

```json
{
  "url": "jdbc:mysql://127.0.0.1:2881/a22",
  "username": "root@test",
  "password": "root",
  "table": "a22_demo_output"
}
```

Create the tenant database before the first write if it does not exist:

```powershell
docker compose exec oceanbase obclient -h127.0.0.1 -P2881 -uroot@test -proot -e "CREATE DATABASE IF NOT EXISTS a22;"
```

## 5. Stop local services

```powershell
docker compose --profile streaming --profile oceanbase down
```

Do not add `-v` unless the local MySQL or OceanBase data volumes should be deleted.
