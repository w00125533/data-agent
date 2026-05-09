#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[1/4] Uploading sample CSVs to HDFS..."
docker compose --env-file .env exec -T hdfs-namenode bash -c "
  /opt/hadoop/bin/hdfs dfs -mkdir -p /user/hive/warehouse/dw.db/mr_5g_15min &&
  /opt/hadoop/bin/hdfs dfs -mkdir -p /user/hive/warehouse/dim.db/engineering_param
"
docker cp samples/data/mr_5g_15min.csv da-hdfs-namenode:/tmp/mr_5g_15min.csv
docker cp samples/data/engineering_param.csv da-hdfs-namenode:/tmp/engineering_param.csv
docker compose --env-file .env exec -T hdfs-namenode bash -c "
  /opt/hadoop/bin/hdfs dfs -put -f /tmp/mr_5g_15min.csv /user/hive/warehouse/dw.db/mr_5g_15min/ &&
  /opt/hadoop/bin/hdfs dfs -put -f /tmp/engineering_param.csv /user/hive/warehouse/dim.db/engineering_param/
"

echo "[2/4] Creating Hive databases & tables via Spark..."
for sql in samples/ddl/*.sql; do
  echo "  $sql"
  docker compose --env-file .env exec -T spark-master \
    spark-sql --master spark://spark-master:7077 \
    -f - < "$sql"
done

echo "[3/4] Creating Kafka topic & loading signaling events..."
docker compose --env-file .env exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic signaling_events \
    --partitions 1 --replication-factor 1
docker cp samples/kafka/signaling_events.jsonl da-kafka:/tmp/signaling_events.jsonl
docker compose --env-file .env exec -T kafka bash -c "
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic signaling_events < /tmp/signaling_events.jsonl
"

echo "[4/4] Done. Verify with:"
echo "  docker compose --env-file .env exec spark-master spark-sql -e 'SELECT COUNT(*) FROM dw.mr_5g_15min;'"
