#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 如需重置数据(删除 volumes),传入 -v: ./scripts/down.sh -v
docker compose --env-file .env down "$@"
