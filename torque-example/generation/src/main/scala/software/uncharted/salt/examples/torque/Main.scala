package software.uncharted.salt.examples.torque

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row

import software.uncharted.salt.core.projection.numeric._
import software.uncharted.salt.core.generation.request._
import software.uncharted.salt.core.generation.Series
import software.uncharted.salt.core.generation.mapreduce.MapReduceTileGenerator
import software.uncharted.salt.core.generation.output.TileData
import software.uncharted.salt.core.analytic._
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
  // preseve memory. The finished value of the aggregator is a List of (time, count) pairs where
  // the count is non-zero
  object TimeBucketAggregator extends Aggregator[Int, Option[Array[Int]], List[(Int,Int)]] {
    def default(): Option[Array[Int]] = {
      None
    }

    override def add(current: Option[Array[Int]], next: Option[Int]): Option[Array[Int]] = {
      var acc = current
      if (next.isDefined) {
        if (!acc.isDefined) {
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

  // Given a TileData object with bins of List((time,count)) create a TileJSON object
  // to the spec given here: https://github.com/CartoDB/tilecubes/blob/master/2.0/spec.md
  def createTileJSON(tile: TileData[(Int,Int,Int), _, _]) = {
    val bins = tile.bins.zipWithIndex.flatMap(x => {
      val data = x._1.asInstanceOf[List[(Int,Int)]]
      // Only create records for bins with data
      if (data.length > 0) {
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


  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Requires commandline: <spark-submit command> inputFilePath outputPath")
      System.exit(-1)
    }

    val inputPath = args(0)
    val outputPath = args(1)

    val conf = new SparkConf().setAppName("salt-torque-example")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    sqlContext.read.format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load(s"file://${inputPath}")
      .registerTempTable("taxi_micro")

    // Construct an RDD of Rows containing only the fields we need. Cache the result
    // (must cast a few incorrectly detected columns to double)
    val input = sqlContext.sql("select pickup_lon, pickup_lat, CAST(dropoff_lon as double), CAST(dropoff_lat as double), CAST(pickup_time as TIMESTAMP), CAST(dropoff_time as TIMESTAMP) from taxi_micro")
      .rdd.cache

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

    // Construct the definition of the tiling jobs: pickups and dropoffs
    val pickups = new Series((tileSize-1, tileSize-1),
      pickupExtractor,
      new MercatorProjection(),
      Some(pickupTimeExtractor),
      TimeBucketAggregator,
      None)

    val dropoffs = new Series((tileSize-1, tileSize-1),
      dropoffExtractor,
      new MercatorProjection(),
      Some(dropoffTimeExtractor),
      TimeBucketAggregator,
      None)

    // Tile Generator object, which houses the generation logic
    val gen = new MapReduceTileGenerator(sc)

    // Iterate over sets of levels to generate. Process several higher levels at once because the
    // number of tile outputs is quite low. Lower levels done individually due to high tile counts.
    for( level <- List(List(0,1,2,3,4,5,6,7,8), List(9, 10, 11), List(12), List(13), List(14)) ) {
      println("------------------------------")
      println(s"Generating level ${level}")
      println("------------------------------")

      // Create a request for all tiles on these levels, generate
      val request = new TileLevelRequest(level, (coord: (Int,Int,Int)) => coord._1)
      val result = gen.generate(input, Seq(pickups, dropoffs), request)

      // Translate RDD of TileData to RDD of (coordinate,JSON), collect to master for serialization
      val output = result.map(t => {
        t.map( tile => {
          // Return tuples of tile coordinate, json string
          (tile.coords, createTileJSON(tile).toString())
        })
      }).collect

      // Save JSON to local filesystem
      val layerNames = List("pickups", "dropoffs")
      output.foreach(tileSet => {
        tileSet.view.zipWithIndex.foreach(tile => {
          val layerName = layerNames(tile._2)
          val coord = tile._1._1
          val json = tile._1._2

          val limit = (1 << coord._1) - 1

          // Use standard TMS path structure and file naming
          val file = new File( s"${outputPath}/${layerName}/${coord._1}/${coord._2}/${limit - coord._3}.json" )
          file.getParentFile().mkdirs()

          val pw = new PrintWriter(file)
          pw.write(json)
          pw.close
        })
      })
    }

  }
}