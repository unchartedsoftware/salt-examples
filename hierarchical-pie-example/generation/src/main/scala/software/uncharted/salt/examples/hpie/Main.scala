package software.uncharted.salt.examples.hpie

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row

import java.sql.{Connection,DriverManager,Statement};

import software.uncharted.salt.core.generation.request._
import software.uncharted.salt.core.generation.Series
import software.uncharted.salt.core.generation.TileGenerator
import software.uncharted.salt.core.generation.output.Tile
import software.uncharted.salt.core.generation.output.SeriesData
import software.uncharted.salt.core.analytic.numeric.{SumAggregator, CountAggregator}

import software.uncharted.sparkpipe.Pipe
import software.uncharted.sparkpipe.{ops => ops}

import scala.collection.mutable.ArrayBuffer

object Main {

  val MAX_TILING_DEPTH = 6 //how many directories deep should we allow the visualization to go?
  val INSERT_BATCH_SIZE = 1000 //batch insert size for SQLite. Probably don't need to change this.

  /*
   * Create our database table where we'll store the resultant tiles.
   * We need to use a database in this example because we need range queries to
   * select directories within a directory.
   */
  def initDatabase(outputPath: String): Unit = {
    // connect to output sqlite database
    Class.forName("org.sqlite.JDBC");
    val db: Connection = DriverManager.getConnection(s"jdbc:sqlite:${outputPath}/fs_stats.sqlite");
    val stmt = db.createStatement();
    try {
      stmt.executeUpdate("DROP TABLE IF EXISTS fs_stats;")
      val sql = """CREATE TABLE fs_stats
                    (path                   TEXT    NOT NULL,
                     full_path              TEXT    NOT NULL,
                     filename               TEXT    NOT NULL,
                     dirdepth               INT     NOT NULL,
                     items                  INT     NOT NULL,
                     executable_items       INT     NOT NULL,
                     directory_items        INT     NOT NULL,
                     cumulative_size_bytes  INT     NOT NULL,
                     PRIMARY KEY (path, filename)
                    );"""
      stmt.executeUpdate(sql);
    } finally {
      stmt.close();
      db.close()
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Requires commandline: <spark-submit command> inputFilePath outputPath")
      System.exit(-1)
    }

    val inputPath = args(0)
    val outputPath = args(1)

    // initialize output database
    initDatabase(outputPath)

    // spark setup
    val conf = new SparkConf().setAppName("salt-hierarchical-pie-example")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    // use our custom PathProjection
    val projection = new PathProjection(MAX_TILING_DEPTH)

    // create Series for tracking cumulative bytes
    val pathExtractor = (r: Row) => {
      val fieldIndex = r.fieldIndex("path")
      if (r.isNullAt(fieldIndex)) {
        None
      } else {
        Some(r.getString(fieldIndex))
      }
    }
    val bytesExtractor = (r: Row) => {
      val fieldIndex = r.fieldIndex("bytes")
      if (r.isNullAt(fieldIndex)) {
        None
      } else {
        Some(r.getLong(fieldIndex).toDouble)
      }
    }
    val sBytes = new Series(0, pathExtractor, projection, bytesExtractor, SumAggregator, None)
    // create Series for tracking total items
    val ssItems = new Series(0, pathExtractor, projection, (r: Row) => Some(1), CountAggregator, None)
    // create Series for tracking total directory items
    val dirChildExtractor = (r: Row) => {
      val fieldIndex = r.fieldIndex("permissions_string")
      if (r.isNullAt(fieldIndex)) {
        None
      } else if (r.getString(fieldIndex).substring(0,1).equals("d")) {
        Some(1D)
      } else {
        Some(0D)
      }
    }
    val sDirectories = new Series(0, pathExtractor, projection, dirChildExtractor, SumAggregator, None)
    // create Series for tracking total executable items
    val exChildExtractor = (r: Row) => {
      val fieldIndex = r.fieldIndex("permissions_string")
      if (r.isNullAt(fieldIndex)) {
        None
      } else if (!r.getString(fieldIndex).substring(0,1).equals("d") && r.getString(fieldIndex).indexOf("x") >= 0) {
        Some(1D)
      } else {
        Some(0D)
      }
    }
    val sExecutables = new Series(0, pathExtractor, projection, exChildExtractor, SumAggregator, None)

    // using the Uncharted Spark Pipeline for ETL
    val inputDataPipe = Pipe(() => {
      // load source data
      sqlContext.read.format("com.databricks.spark.csv")
        .option("header", "true")
        .option("inferSchema", "true")
        .load(s"file://${inputPath}")
    })
    // convert to rdd
    .to(ops.core.dataframe.toRDD)
    // pipe to salt
    .to(rdd => {
      val gen = TileGenerator(sc)
      gen.generate(rdd, Seq(sBytes, ssItems, sDirectories, sExecutables), new PathRequest())
    })
    // to simplify example, eliminate SQLite lock contention issue by collecting tiles to master
    .to(_.collect)
    // pipe results to SQLite database
    .to(tiles => {
      Class.forName("org.sqlite.JDBC");
      val db: Connection = DriverManager.getConnection(s"jdbc:sqlite:${outputPath}/fs_stats.sqlite");
      val stmt = db.prepareStatement("INSERT INTO fs_stats (path, full_path, filename, dirdepth, items, executable_items, directory_items, cumulative_size_bytes) VALUES (?,?,?,?,?,?,?,?)");
      try {
        db.setAutoCommit(false)
        var i = 0
        tiles.foreach(tile => {
          stmt.setString(1, tile.coords.substring(0, tile.coords.lastIndexOf("/")))
          stmt.setString(2, tile.coords)
          stmt.setString(3, tile.coords.substring(tile.coords.lastIndexOf("/")+1))
          stmt.setInt(4, tile.coords.split("/").length - 1)
          stmt.setInt(5, sBytes(tile).get.bins(0).toInt)
          stmt.setInt(6, ssItems(tile).get.bins(0).toInt)
          stmt.setInt(7, sDirectories(tile).get.bins(0).toInt)
          stmt.setInt(8, sExecutables(tile).get.bins(0).toInt)
          stmt.addBatch()
          i+=1
          if (i % INSERT_BATCH_SIZE == 0 || i == tiles.length) {
            stmt.executeBatch()
          }
        })
      } finally {
        db.commit()
        stmt.close()
        db.setAutoCommit(true)
        // index at the end
        val stmt2 = db.createStatement();
        stmt2.executeUpdate("CREATE INDEX fs_stats_path_idx ON fs_stats (path)")
        stmt2.executeUpdate("CREATE INDEX fs_stats_items_idx ON fs_stats (items DESC)")
        stmt2.executeUpdate("CREATE INDEX fs_stats_exec_items_idx ON fs_stats (executable_items DESC)")
        stmt2.executeUpdate("CREATE INDEX fs_stats_dir_items_idx ON fs_stats (directory_items DESC)")
        stmt2.executeUpdate("CREATE INDEX fs_stats_size_idx ON fs_stats (cumulative_size_bytes DESC)")
        stmt2.close()
        // close connections
        db.close()
      }
    })
    // run pipeline
    .run
  }
}
