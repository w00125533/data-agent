USE dw;
CREATE TABLE IF NOT EXISTS mr_5g_15min (
  cell_id          STRING,
  ts_15min         TIMESTAMP,
  rsrp_avg         DOUBLE,
  rsrq_avg         DOUBLE,
  sinr_avg         DOUBLE,
  sample_count     BIGINT,
  weak_cov_ratio   DOUBLE
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
TBLPROPERTIES ("skip.header.line.count"="1");
