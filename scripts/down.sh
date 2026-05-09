#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# -v 删除 volumes(重置数据);如要保留数据,移除 -v
docker compose --env-file .env down "$@"
