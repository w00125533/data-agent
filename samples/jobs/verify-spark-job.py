"""
M0a end-to-end verification:
1. Read from Hive: dw.mr_5g_15min
2. Join dim.engineering_param, aggregate by district
3. Write back to Hive: dw.weak_cov_by_district_m0a_check
4. Print results
"""
from pyspark.sql import SparkSession

spark = (
    SparkSession.builder
    .appName("m0a-verify")
    .enableHiveSupport()
    .getOrCreate()
)

mr = spark.table("dw.mr_5g_15min")
ep = spark.table("dim.engineering_param")

result = (
    mr.alias("m")
    .join(ep.alias("e"), "cell_id")
    .groupBy("e.district")
    .agg(
        {"m.weak_cov_ratio": "avg", "m.sample_count": "sum"}
    )
    .withColumnRenamed("avg(weak_cov_ratio)", "avg_weak_cov_ratio")
    .withColumnRenamed("sum(sample_count)", "total_samples")
)

result.show(truncate=False)

spark.sql("DROP TABLE IF EXISTS dw.weak_cov_by_district_m0a_check")
result.write.mode("overwrite").saveAsTable("dw.weak_cov_by_district_m0a_check")

print("SUCCESS: m0a verify job completed.")
spark.stop()
