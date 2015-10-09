package software.uncharted.salt.examples.torque

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row

import com.unchartedsoftware.salt.core.projection.numeric._
import com.unchartedsoftware.salt.core.generation.request._
import com.unchartedsoftware.salt.core.generation.Series
import com.unchartedsoftware.salt.core.generation.mapreduce.MapReduceTileGenerator
import com.unchartedsoftware.salt.core.generation.output.TileData
import com.unchartedsoftware.salt.core.analytic._
import com.unchartedsoftware.salt.core.analytic.collection._
import com.unchartedsoftware.salt.core.analytic.Aggregator

import scala.util.parsing.json._
import scala.collection.JavaConversions._
import java.io._

object Main {

  val tileSize = 64

  // Given a TileData object with bins of List((Int,Int)) create a TileJSON object
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
    val conf = new SparkConf().setAppName("salt-torque-example")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    // TODO replace source file with arg
    sqlContext.read.format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load("file:///opt/data/taxi_one_day.csv")
      .registerTempTable("taxi_micro")

    val input = sqlContext.sql("select pickup_lon, pickup_lat, CAST(pickup_time as TIMESTAMP) from taxi_micro").rdd.cache

    // Given an input row, return longitude, latitude as a tuple
    val coordExtractor = (r: Row) => {
      Some((r.getDouble(0), r.getDouble(1)))
    }

    // Given an input row, return the value column
    val valueExtractor = (r: Row) => {
      if (r.isNullAt(2)) {
        None
      } else {
        val time = r.getAs[java.sql.Timestamp](2)
        Some(List( (time.getHours()*60 + time.getMinutes()) / 5 ))
      }
    }

    // Construct the definition of the tiling job
    val series = new Series((tileSize-1, tileSize-1),
      coordExtractor,
      new MercatorProjection(),
      Some(valueExtractor),
      new TopElementsAggregator[Int](288),
      None)

    // Tile Generator object, which houses the generation logic
    val gen = new MapReduceTileGenerator(sc)


    for( level <- 0 to 14 ) {
      println("------------------------------")
      println(s"Generating level ${level}")
      println("------------------------------")

      // Create a request for all tiles on this level, generate
      val request = new TileLevelRequest(Seq(level), (coord: (Int,Int,Int)) => coord._1)
      val result = gen.generate(input, Seq(series), request)

      // Translate RDD of TileData to RDD of JSON, collect to master for serialization
      val output = result.map(t => {
        val tile = t(0)
        // Return tuples of tile coordinate, json string
        (tile.coords, createTileJSON(tile).toString())
      }).collect

      // Save JSON to filesystem
      output.foreach(tile => {
        val coord = tile._1
        val json = tile._2

        val limit = (1 << coord._1) - 1

        // TODO replace dest path with arg
        val file = new File( s"/opt/output/${coord._1}/${coord._2}/${limit - coord._3}.json" )
        file.getParentFile().mkdirs()

        val pw = new PrintWriter(file)
        pw.write(json)
        pw.close
      })
    }

  }
}