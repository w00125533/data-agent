#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "ERROR: .env not found. Run: cp .env.example .env" >&2
  exit 1
fi

docker compose --env-file .env up -d "$@"
echo "Stack starting. Run ./scripts/verify.sh to check health."
