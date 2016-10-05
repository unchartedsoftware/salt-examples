package software.uncharted.salt.examples.torque

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row

import software.uncharted.salt.core.projection.numeric._
import software.uncharted.salt.core.generation.request._
import software.uncharted.salt.core.generation.Series
import software.uncharted.salt.core.generation.TileGenerator
import software.uncharted.salt.core.generation.output.SeriesData
import software.uncharted.salt.core.analytic.Aggregator

import java.util.Calendar
import java.util.GregorianCalendar

import scala.util.parsing.json._
import scala.collection.JavaConversions._
import java.io._

object Main {

  // Defines the tile size in both x and y bin dimensions
  val tileSize = 64

  // Custom bin aggregator, accepts integers [0,288) representing 5-minute increments of the day
  // and tracks count per time bucket using an Array. Uninitialized buckets are left as None to
  // preserve memory. The finished value of the aggregator is a List of (time, count) pairs where
  // the count is non-zero
  object TimeBucketAggregator extends Aggregator[Int, Option[Array[Int]], List[(Int,Int)]] {
    def default(): Option[Array[Int]] = {
      None
    }

    override def add(current: Option[Array[Int]], next: Option[Int]): Option[Array[Int]] = {
      var acc = current
      if (next.isDefined) {
        if (acc.isEmpty) {
          acc = Some(Array.fill[Int](288)(0))
        }
        acc.get(next.get) += 1
      }
      acc
    }

    override def merge(left: Option[Array[Int]], right: Option[Array[Int]]): Option[Array[Int]] = {
      (left, right) match {
        case (Some(l), Some(r)) => Some( (l, r).zipped.map(_ + _) )
        case (None, x) => x
        case (x, None) => x
      }
    }

    def finish(intermediate: Option[Array[Int]]): List[(Int, Int)] = {
      intermediate match {
        case Some(result) => result.zipWithIndex.filter( { case (v,i) => v > 0 } ).map(_.swap).toList
        case None => List()
      }
    }
  }

  // Given a SeriesData object with bins of List((time,count)) create a TileJSON object
  // to the spec given here: https://github.com/CartoDB/tilecubes/blob/master/2.0/spec.md
  def createTileJSON(seriesData: SeriesData[(Int,Int,Int),(Int, Int),_,_]) = {
    val bins = seriesData.bins.zipWithIndex.flatMap(x => {
      val data = x._1.asInstanceOf[List[(Int,Int)]]
      // Only create records for bins with data
      if (data.nonEmpty) {
        // Generate Torque formatted JSON
        val times: java.util.List[Int] = data.map(_._1)
        val values: java.util.List[Int] = data.map(_._2)
        Some(JSONObject(Map(
          "x__uint8" -> x._2 % tileSize,
          "y__uint8" -> (tileSize - Math.floor(x._2/tileSize).toInt - 1),
          "vals__uint8" -> values,
          "dates__uint16" -> times
        )))
      } else {
        None
      }
    })
    JSONArray(bins.toList)
  }

  // Save JSON to local filesystem under a given path.
  def writeJsonFile(path: String, coord: (Int,Int,Int), json: String) = {
    val limit = (1 << coord._1) - 1
    // Use standard TMS path structure and file naming
    val file = new File( s"$path/${coord._1}/${coord._2}/${limit - coord._3}.json" )
    file.getParentFile.mkdirs()
    val pw = new PrintWriter(file)
    pw.write(json)
    pw.close()
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Requires commandline: <spark-submit command> inputFilePath outputPath")
      System.exit(-1)
    }

    val inputPath = args(0)
    val outputPath = args(1)

    val sparkSession = SparkSession.builder.appName("salt-torque-example").getOrCreate()
    val sc = sparkSession.sparkContext

    sparkSession.read.format("csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load(inputPath)
      .createOrReplaceTempView("taxi_micro")

    // Construct an RDD of Rows containing only the fields we need. Cache the result
    // (must cast a few incorrectly detected columns to double)
    val input = sparkSession.sql("select pickup_lon, pickup_lat, CAST(dropoff_lon as double), CAST(dropoff_lat as double), CAST(pickup_time as TIMESTAMP), CAST(dropoff_time as TIMESTAMP) from taxi_micro")
      .rdd.cache()

    // Given an input row, return pickup longitude, latitude as a tuple
    val pickupExtractor = (r: Row) => {
      if (r.isNullAt(0) || r.isNullAt(1)) {
        None
      } else {
        Some((r.getDouble(0), r.getDouble(1)))
      }
    }
    // Given an input row, return dropoff longitude, latitude as a tuple
    val dropoffExtractor = (r: Row) => {
      if (r.isNullAt(2) || r.isNullAt(3)) {
        None
      } else {
        Some((r.getDouble(2), r.getDouble(3)))
      }
    }

    // Given an input row, return pickup time in 5-minute increments of the day
    val pickupTimeExtractor = (r: Row) => {
      if (r.isNullAt(4)) {
        None
      } else {
        val cal = new GregorianCalendar()
        cal.setTimeInMillis( r.getAs[java.sql.Timestamp](4).getTime )
        Some( ((cal.get(Calendar.HOUR_OF_DAY)*60 + cal.get(Calendar.MINUTE)) / 5).toInt )
      }
    }
    // Same for drop off time
    val dropoffTimeExtractor = (r: Row) => {
      if (r.isNullAt(4)) {
        None
      } else {
        val cal = new GregorianCalendar()
        cal.setTimeInMillis( r.getAs[java.sql.Timestamp](5).getTime )
        Some( ((cal.get(Calendar.HOUR_OF_DAY)*60 + cal.get(Calendar.MINUTE)) / 5).toInt )
      }
    }

    // Tile Generator object, which houses the generation logic
    val gen = TileGenerator(sc)

    // Iterate over sets of levels to generate. Process several higher levels at once because the
    // number of tile outputs is quite low. Lower levels done individually due to high tile counts.
    for( level <- List(List(0,1,2,3,4,5,6,7,8), List(9, 10, 11), List(12), List(13), List(14)) ) {
      println("------------------------------")
      println(s"Generating level $level")
      println("------------------------------")

      // Construct the definition of the tiling jobs: pickups and dropoffs
      val pickups = new Series((tileSize-1, tileSize-1),
        pickupExtractor,
        new MercatorProjection(level),
        pickupTimeExtractor,
        TimeBucketAggregator,
        None)

      val dropoffs = new Series((tileSize-1, tileSize-1),
        dropoffExtractor,
        new MercatorProjection(level),
        dropoffTimeExtractor,
        TimeBucketAggregator,
        None)

      // Create a request for all tiles on these levels, generate
      val request = new TileLevelRequest(level, (coord: (Int,Int,Int)) => coord._1)
      val result = gen.generate(input, Seq(pickups, dropoffs), request)

      // Translate RDD of Tiles to RDD of (coordinate,pickupsJSON,dropoffsJSON), collect to master for serialization
      val output = result.map(t => {
        (t.coords, createTileJSON(pickups(t).get).toString(), createTileJSON(dropoffs(t).get).toString())
      }).collect()

      // Save JSON to local filesystem
      output.foreach(tile => {
        val coord = tile._1
        val pickupsJson = tile._2
        val dropoffsJson = tile._3
        writeJsonFile(s"$outputPath/pickups", coord, pickupsJson)
        writeJsonFile(s"$outputPath/dropoffs", coord, dropoffsJson)
      })

    }

  }
}
