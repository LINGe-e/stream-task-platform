# HTTP 压力测试准备

本目录使用 Python 标准库，不需要安装 `k6`、`wrk` 或 `ab`。

## 基线示例

```powershell
python D:\a22\tools\stress-test\http_load.py `
  --url http://127.0.0.1:8080/api/auth/login `
  --method POST `
  --header "Content-Type: application/json" `
  --body-file D:\a22\tools\stress-test\login-body.json `
  --concurrency 5 `
  --duration 30 `
  --output D:\a22\outputs\stress-baseline.json
```

`login-body.json` 不应提交到仓库；测试结束后应删除或放入忽略目录。

## 建议阶段

1. 先启动项目服务并执行单请求健康检查。
2. 以低并发运行 30 秒基线，记录 RPS、p95、失败率。
3. 再按 2 倍、4 倍并发递增，观察后端日志、数据库、Flink 和 Kafka。
4. 达到资源瓶颈或错误率明显上升时停止，不继续盲目加压。

如果从 VMware 客户机发压，目标地址应改为宿主机在 NAT 网络中的地址，并先确认宿主机防火墙允许 8080 入站。
