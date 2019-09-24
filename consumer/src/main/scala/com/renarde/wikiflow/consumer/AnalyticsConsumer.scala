package com.renarde.wikiflow.consumer

import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.from_json
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._

object AnalyticsConsumer extends App with LazyLogging {

  val appName: String = "analytics-consumer-example"

  val spark: SparkSession = SparkSession.builder()
    .appName(appName)
    .config("spark.driver.memory", "5g")
    .master("local[2]")
    .getOrCreate()

  import spark.implicits._

  spark.sparkContext.setLogLevel("WARN")

  logger.info("Initializing Structured consumer")


  val inputStream = spark.readStream
    .format("kafka")
    .option("kafka.bootstrap.servers", "kafka:9092")
    .option("subscribe", "wikiflow-topic")
    .option("startingOffsets", "earliest")
    .load()

  // please edit the code below
  /*
  - из прилетающего стрима выбрать только ключи и значения, провести преобразование типов к кортежу (String,String)
  - отфильтровать строки с пустыми значениями (value.isNotNull)
  - провести преобразование входного json-объекта (которым и является value) в структуру через функцию from_json
  - удалить всю активность ботов из входящего потока (bot !=true)
  - сгруппировать по полю "type", посчитать каунты, добавить текущий timestamp и записать из в выходной стрим (объект transformedStream).
   */

  val expectedSchema = new StructType()
    .add(StructField("bot", BooleanType))
    .add(StructField("comment", StringType))
    .add(StructField("id", LongType))
    .add("length", new StructType()
      .add(StructField("new", LongType))
      .add(StructField("old", LongType))
    )
    .add("meta", new StructType()
      .add(StructField("domain", StringType))
      .add(StructField("dt", StringType))
      .add(StructField("id", StringType))
      .add(StructField("offset", LongType))
      .add(StructField("partition", LongType))
      .add(StructField("request_id", StringType))
      .add(StructField("stream", StringType))
      .add(StructField("topic", StringType))
      .add(StructField("uri", StringType))
    )
    .add("minor", BooleanType)
    .add("namespace", LongType)
    .add("parsedcomment", StringType)
    .add("patrolled", BooleanType)
    .add("revision", new StructType()
      .add("new", LongType)
      .add("old", LongType)
    )
    .add("server_name", StringType)
    .add("server_script_path", StringType)
    .add("server_url", StringType)
    .add("timestamp", TimestampType)
    .add("title", StringType)
    .add("type", StringType)
    .add("user", StringType)
    .add("wiki", StringType)

  // https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html#window-operations-on-event-time

  val transformedStream: DataFrame = inputStream
    .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)").as[(String, String)]
    .filter($"value".isNotNull)
    .select(from_json($"value", expectedSchema).as("data")).select("data.*")
    .filter("bot <> true")
    .withWatermark("timestamp", "2 minutes")
    .groupBy(
      window($"timestamp", "2 minutes", "2 minutes"),
      $"type"
    )
    .count()
    .withColumn(
      "processing_timestamp",
      lit(current_timestamp())
    )
    .select($"type", $"count", $"processing_timestamp")

  transformedStream.writeStream
    .outputMode("append")
    .format("delta")
    .option("checkpointLocation", "/storage/analytics-consumer/checkpoints")
    .start("/storage/analytics-consumer/output")

  spark.streams.awaitAnyTermination()
}
