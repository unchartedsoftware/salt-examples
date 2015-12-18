package software.uncharted.salt.examples.bin

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row

import software.uncharted.salt.core.projection.numeric._
import software.uncharted.salt.core.generation.request._
import software.uncharted.salt.core.generation.Series
import software.uncharted.salt.core.generation.mapreduce.MapReduceTileGenerator
import software.uncharted.salt.core.generation.output.SeriesData
import software.uncharted.salt.core.analytic.numeric._

import java.io._

import scala.util.parsing.json.JSONObject

object Main {

  // Defines the tile size in both x and y bin dimensions
  val tileSize = 256

  // Defines the output layer name
  val layerName = "pickups"

  // Creates and returns an Array of Double values encoded as 64bit Integers
  def createByteBuffer(tile: SeriesData[(Int, Int, Int), Double, (Double, Double)]): Array[Byte] = {
    val byteArray = new Array[Byte](tileSize * tileSize * 8)
    var j = 0
    tile.bins.foreach(b => {
      val data = java.lang.Double.doubleToLongBits(b)
      for (i <- 0 to 7) {
        byteArray(j) = ((data >> (i * 8)) & 0xff).asInstanceOf[Byte]
        j += 1
      }
    })
    byteArray
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Requires commandline: <spark-submit command> inputFilePath outputPath")
      System.exit(-1)
    }

    val inputPath = args(0)
    val outputPath = args(1)

    val conf = new SparkConf().setAppName("salt-bin-example")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    sqlContext.read.format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load(s"file://$inputPath")
      .registerTempTable("taxi_micro")

    // Construct an RDD of Rows containing only the fields we need. Cache the result
    val input = sqlContext.sql("select pickup_lon, pickup_lat from taxi_micro")
      .rdd.cache()

    // Given an input row, return pickup longitude, latitude as a tuple
    val pickupExtractor = (r: Row) => {
      if (r.isNullAt(0) || r.isNullAt(1)) {
        None
      } else {
        Some((r.getDouble(0), r.getDouble(1)))
      }
    }

    // Construct the definition of the tiling jobs: pickups
    val pickups = new Series((tileSize - 1, tileSize - 1),
      pickupExtractor,
      new MercatorProjection(),
      None,
      CountAggregator,
      Some(MinMaxAggregator))

    // Tile Generator object, which houses the generation logic
    val gen = new MapReduceTileGenerator(sc)

    // Break levels into batches. Process several higher levels at once because the
    // number of tile outputs is quite low. Lower levels done individually due to high tile counts.
    val levelBatches = List(List(0, 1, 2, 3, 4, 5, 6, 7, 8), List(9, 10, 11), List(12), List(13))

    // Iterate over sets of levels to generate.
    val levelMeta = levelBatches.map(level => {

      println("------------------------------")
      println(s"Generating level $level")
      println("------------------------------")

      // Create a request for all tiles on these levels, generate
      val request = new TileLevelRequest(level, (coord: (Int, Int, Int)) => coord._1)
      val rdd = gen.generate(input, pickups, request)

      // Translate RDD of Tiles to RDD of (coordinate,byte array), collect to master for serialization
      val output = rdd
        .map(s => pickups(s))
        .map(tile => {
          // Return tuples of tile coordinate, byte array
          (tile.coords, createByteBuffer(tile))
        })
        .collect()

      // Save byte files to local filesystem
      output.foreach(tile => {
        val coord = tile._1
        val byteArray = tile._2
        val limit = (1 << coord._1) - 1
        // Use standard TMS path structure and file naming
        val file = new File(s"$outputPath/$layerName/${coord._1}/${coord._2}/${limit - coord._3}.bins")
        file.getParentFile.mkdirs()
        val output = new FileOutputStream(file)
        output.write(byteArray)
        output.close()
      })

      // Create map from each level to min / max values.
      rdd
        .map(s => pickups(s))
        .map(t => (t.coords._1.toString, t.tileMeta.get))
        .reduceByKey((l, r) => {
          (Math.min(l._1, r._1), Math.max(l._2, r._2))
        })
        .mapValues(minMax => {
          JSONObject(Map(
            "min" -> minMax._1,
            "max" -> minMax._2
          ))
        })
        .collect()
        .toMap
    })

    // Flatten array of maps into a single map
    val levelInfoJSON = JSONObject(levelMeta.reduce(_ ++ _)).toString()
    // Save level metadata to filesystem
    val pw = new PrintWriter(s"$outputPath/$layerName/meta.json")
    pw.write(levelInfoJSON)
    pw.close()

  }
}
