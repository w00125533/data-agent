#!/usr/bin/env bash
# Integration health check: hit each service with a minimal probe.
# Non-zero exit = failure.
set -uo pipefail
cd "$(dirname "$0")/../.."

FAIL=0
ok()   { echo "  [OK]   $1"; }
fail() { echo "  [FAIL] $1" >&2; FAIL=1; }

echo "== 1. postgres =="
docker compose --env-file .env exec -T postgres \
  psql -U hive -d metastore -c "SELECT 1;" >/dev/null 2>&1 \
  && ok "postgres reachable" || fail "postgres unreachable"

echo "== 2. hdfs =="
docker compose --env-file .env exec -T hdfs-namenode \
  /opt/hadoop/bin/hdfs dfs -ls / >/dev/null 2>&1 \
  && ok "hdfs reachable" || fail "hdfs unreachable"

echo "== 3. hive metastore =="
docker compose --env-file .env exec -T hive-metastore \
  bash -c "echo > /dev/tcp/localhost/9083" 2>/dev/null \
  && ok "hms listening on 9083" || fail "hms not listening"

echo "== 4. spark =="
docker compose --env-file .env exec -T spark-master \
  curl -sf http://localhost:8080 >/dev/null \
  && ok "spark master ui up" || fail "spark master ui down"

echo "== 5. flink =="
docker compose --env-file .env exec -T flink-jobmanager \
  curl -sf http://localhost:8081/overview >/dev/null \
  && ok "flink jobmanager up" || fail "flink jobmanager down"

echo "== 6. kafka =="
docker compose --env-file .env exec -T kafka \
  /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 \
  && ok "kafka broker reachable" || fail "kafka broker unreachable"

echo "== 7. starrocks =="
docker compose --env-file .env exec -T starrocks \
  mysql -h127.0.0.1 -P9030 -uroot -e "SELECT 1;" >/dev/null 2>&1 \
  && ok "starrocks reachable" || fail "starrocks unreachable"

echo "== 8. deepseek api =="
python scripts/deepseek-ping.py >/dev/null 2>&1 \
  && ok "deepseek api reachable" || fail "deepseek api unreachable"

echo
if [[ $FAIL -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "ONE OR MORE CHECKS FAILED" >&2
  exit 1
fi
