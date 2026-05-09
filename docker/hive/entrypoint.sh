#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for postgres..."
until nc -z postgres 5432; do
  sleep 2
done

# Initialize schema (idempotent — only runs once)
SCHEMA_INIT_FLAG=/tmp/.hms-schema-initialized
if [[ ! -f "$SCHEMA_INIT_FLAG" ]]; then
  echo "Initializing HMS schema in postgres..."
  /opt/hive/bin/schematool -dbType postgres -initSchema || true
  touch "$SCHEMA_INIT_FLAG"
fi

echo "Starting Hive Metastore on port 9083..."
exec /opt/hive/bin/hive --service metastore
