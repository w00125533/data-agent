# Data Agent — 无线网络感知评估

**前置需求:** Docker + Docker Compose v2

## 快速开始

```bash
# 1. 准备配置
cp .env.example .env
# 编辑 .env,至少填入 DEEPSEEK_API_KEY

# 2. 拉起本地验证栈
./scripts/up.sh

# 3. 健康检查
./scripts/verify.sh

# 4. 准备样例数据
./scripts/load-sample-data.sh

# 5. 停止与清理
./scripts/down.sh
```

详见 `docs/superpowers/specs/2026-05-09-wireless-perception-data-agent-design.md`。
