USE dim;
CREATE TABLE IF NOT EXISTS engineering_param (
  cell_id     STRING,
  site_id     STRING,
  district    STRING,
  longitude   DOUBLE,
  latitude    DOUBLE,
  rat         STRING,
  azimuth     INT,
  downtilt    INT
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
TBLPROPERTIES ("skip.header.line.count"="1");
