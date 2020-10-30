package software.uncharted.salt.examples.png

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
import software.uncharted.salt.core.analytic.numeric._

import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

import javax.imageio.ImageIO

import java.io._

object Main {

  // Defines the tile size in both x and y bin dimensions
  val tileSize = 256

  // Defines the two color values to interpolate between
  val fromColor = new Color(150, 0, 0, 150)
  val toColor = new Color(255, 255, 50, 255)

  // Due to the distribution of values, a logarithmic transform is applied
  // to give a more 'gradual' gradient
  def logTransform(value: Double, min: Double, max: Double): Double = {
    val logMin = Math.log10(Math.max(1, min))
    val logMax = Math.log10(Math.max(1, max))
    val oneOverLogRange = 1 / (logMax - logMin)
    Math.log10(value - logMin) * oneOverLogRange
  }

  // Interpolates the color value between the minimum and maximum values provided
  def interpolateColor(value: Double, min: Double, max: Double): Int = {
    val alpha = logTransform(value, min, max)
    if (value == 0) {
      new Color(255, 255, 255, 0).getRGB
    } else {
      new Color(
        (toColor.getRed * alpha + fromColor.getRed * (1 - alpha)).toInt,
        (toColor.getGreen * alpha + fromColor.getGreen * (1 - alpha)).toInt,
        (toColor.getBlue * alpha + fromColor.getBlue * (1 - alpha)).toInt,
        (toColor.getAlpha * alpha + fromColor.getAlpha * (1 - alpha)).toInt
      ).getRGB
    }
  }

  // Creates and returns an Array of RGBA values encoded as 32bit integers
  def createRGBABuffer(tile: SeriesData[(Int, Int, Int), (Int, Int), Double, (Double, Double)], min: Double, max: Double): Array[Int] = {
    val rgbArray = new Array[Int](tileSize * tileSize)
    tile.bins.seq.zipWithIndex.foreach(b => {
      val count = b._1
      val index = b._2
      val color = interpolateColor(count, min, max)
      rgbArray(index) = color
    })
    rgbArray
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Requires commandline: <spark-submit command> inputFilePath outputPath")
      System.exit(-1)
    }

    val inputPath = args(0)
    val outputPath = args(1)

    val sparkSession = SparkSession.builder.appName("salt-png-example").getOrCreate()
    val sc = sparkSession.sparkContext

    sparkSession.read.option("header", "true")
      .option("inferSchema", "true")
      .csv(inputPath)
      .createOrReplaceTempView("taxi_micro")

    // Construct an RDD of Rows containing only the fields we need. Cache the result
    val input = sparkSession.sql("select pickup_lon, pickup_lat from taxi_micro")
      .rdd.cache()

    // Given an input row, return pickup longitude, latitude as a tuple
    val pickupExtractor = (r: Row) => {
      if (r.isNullAt(0) || r.isNullAt(1)) {
        None
      } else {
        Some((r.getDouble(0), r.getDouble(1)))
      }
    }

    // Tile Generator object, which houses the generation logic
    val gen = TileGenerator(sc)

    // Iterate over sets of levels to generate. Process several higher levels at once because the
    // number of tile outputs is quite low. Lower levels done individually due to high tile counts.
    for (level <- List(List(0, 1, 2, 3, 4, 5, 6, 7, 8), List(9, 10, 11), List(12), List(13))) {
      println("------------------------------")
      println(s"Generating level $level")
      println("------------------------------")

      // Construct the definition of the tiling jobs: pickups
      val pickups = new Series((tileSize - 1, tileSize - 1),
        pickupExtractor,
        new MercatorProjection(level),
        (r: Row) => Some(1),
        CountAggregator,
        Some(MinMaxAggregator))

      // Create a request for all tiles on these levels, generate
      val request = new TileLevelRequest(level, (coord: (Int, Int, Int)) => coord._1)
      val rdd = gen.generate(input, pickups, request)

      // In order to properly interpolate the color ranges, the minimum and maximum
      // counts for each zoom level must be known.
      // Create map from each level to min / max values.
      val levelInfo = rdd
        .map(s => pickups(s).get)
        .map(t => (t.coords._1, t.tileMeta.get))
        .reduceByKey((l, r) => {
          (Math.min(l._1, r._1), Math.max(l._2, r._2))
        })
        .collect()
        .toMap

      // Broadcast the level info to workers
      val bLevelInfo = sc.broadcast(levelInfo)

      // Translate RDD of Tiles to RDD of (coordinate,RGBA Array), collect to master for serialization
      val output = rdd
        .mapPartitions(partition => {
          val levelInfo = bLevelInfo.value
          partition
            .map(s => pickups(s).get)
            .map(tile => {
              val level = tile.coords._1
              val minMax = levelInfo(level)
              // Return tuples of tile coordinate, RGBA Array
              (tile.coords, createRGBABuffer(tile, minMax._1, minMax._2))
            })
        })
        .collect()

      // Save PNG to local filesystem
      output.foreach(tile => {
        val layerName = "pickups"
        val coord = tile._1
        val rgbArray = tile._2
        val bi = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB)
        val buffer = bi.getRaster.getDataBuffer.asInstanceOf[DataBufferInt].getData
        System.arraycopy(rgbArray, 0, buffer, 0, rgbArray.length)
        val limit = (1 << coord._1) - 1
        // Use standard TMS path structure and file naming
        val file = new File(s"$outputPath/$layerName/${coord._1}/${coord._2}/${limit - coord._3}.png")
        file.getParentFile.mkdirs()
        ImageIO.write(bi, "png", file)
      })
    }

  }
}
