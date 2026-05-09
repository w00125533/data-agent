#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for postgres..."
MAX_RETRIES=30
RETRY=0
until bash -c "echo > /dev/tcp/postgres/5432" 2>/dev/null; do
  RETRY=$((RETRY + 1))
  if [[ $RETRY -ge $MAX_RETRIES ]]; then
    echo "ERROR: postgres not reachable after ${MAX_RETRIES} retries" >&2
    exit 1
  fi
  sleep 2
done

# Initialize schema (idempotent — only runs once)
SCHEMA_INIT_FLAG=/tmp/.hms-schema-initialized
if [[ ! -f "$SCHEMA_INIT_FLAG" ]]; then
  echo "Initializing HMS schema in postgres..."
  if /opt/hive/bin/schematool -dbType postgres -initSchema; then
    touch "$SCHEMA_INIT_FLAG"
  else
    echo "WARNING: schematool returned non-zero (may be already applied). Continuing..." >&2
    touch "$SCHEMA_INIT_FLAG"
  fi
fi

echo "Starting Hive Metastore on port 9083..."
exec /opt/hive/bin/hive --service metastore
