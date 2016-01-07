/*
 * Copyright 2015 Uncharted Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.uncharted.salt.examples.path

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row

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
  val layerName = "paths"

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

    val conf = new SparkConf().setAppName("salt-path-example")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    sqlContext.read.format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load(s"file://$inputPath")
      .sample(false,  0.25) //sample only 25% of the data so that the demo works on a laptop
      .registerTempTable("taxi_micro")

    // Construct an RDD of Rows containing only the fields we need. Cache the result
    val input = sqlContext.sql("select pickup_lon, pickup_lat, cast(dropoff_lon as double), cast(dropoff_lat as double) from taxi_micro")
      .rdd.cache()

    // Given an input row, return pickup lon/lat and dropoff lon/lat as a tuple
    val pathExtractor = (r: Row) => {
      if (r.isNullAt(0) || r.isNullAt(1) || r.isNullAt(2) || r.isNullAt(3)) {
        None
      } else {
        Some((r.getDouble(0), r.getDouble(1), r.getDouble(2), r.getDouble(3)))
      }
    }

    // Tile Generator object, which houses the generation logic
    val gen = new MapReduceTileGenerator(sc)

    // Break levels into batches. Process several higher levels at once because the
    // number of tile outputs is quite low. Lower levels done individually due to high tile counts.
    val levelBatches = List(List(0, 1, 2, 3), List(4, 5), List(6), List(7), List(8), List(9), List(10), List(11), List(12), List(13))

    // Iterate over sets of levels to generate.
    val levelMeta = levelBatches.map(level => {

      println("------------------------------")
      println(s"Generating level $level")
      println("------------------------------")

      // Construct the definition of the tiling jobs: paths
      val paths = new Series((tileSize - 1, tileSize - 1),
        pathExtractor,
        new MercatorLineProjection(1024, level),
        None,
        CountAggregator,
        Some(MinMaxAggregator))

      // Create a request for all tiles on these levels, generate
      val request = new TileLevelRequest(level, (coord: (Int, Int, Int)) => coord._1)
      val rdd = gen.generate(input, paths, request)

      // Translate RDD of Tiles to RDD of (coordinate,byte array), collect to master for serialization
      val output = rdd
        .map(s => paths(s))
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
        .map(s => paths(s))
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
