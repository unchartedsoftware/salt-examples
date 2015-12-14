package software.uncharted.salt.examples.hpie

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row

import java.sql.{Connection,DriverManager,Statement};

import software.uncharted.salt.core.generation.request._
import software.uncharted.salt.core.generation.Series
import software.uncharted.salt.core.generation.mapreduce.MapReduceTileGenerator
import software.uncharted.salt.core.generation.output.Tile
import software.uncharted.salt.core.generation.output.SeriesData
import software.uncharted.salt.core.analytic.numeric.{SumAggregator, CountAggregator}

import software.uncharted.sparkpipe.Pipe
import software.uncharted.sparkpipe.{ops => ops}

object Main {

  def initDatabase(outputPath: String): Unit = {
    //connect to output sqlite database
    Class.forName("org.sqlite.JDBC");
    val db: Connection = DriverManager.getConnection(s"jdbc:sqlite:${outputPath}/fs_stats.sqlite");
    val stmt = db.createStatement();
    try {
      stmt.executeUpdate("DROP TABLE IF EXISTS fs_stats;")
      val sql = """CREATE TABLE fs_stats
                    (path                   TEXT    NOT NULL,
                     filename               TEXT    NOT NULL,
                     depth                  INT     NOT NULL,
                     children               INT     NOT NULL,
                     executable_children    INT     NOT NULL,
                     directory_children     INT     NOT NULL,
                     cumulative_size_bytes  INT     NOT NULL,
                     PRIMARY KEY (path, filename)
                    );"""
      stmt.executeUpdate(sql);
      stmt.executeUpdate("CREATE INDEX fs_stats_path_idx ON fs_stats (path)")
      stmt.executeUpdate("CREATE INDEX fs_stats_children_idx ON fs_stats (children DESC)")
      stmt.executeUpdate("CREATE INDEX fs_stats_exec_children_idx ON fs_stats (executable_children DESC)")
      stmt.executeUpdate("CREATE INDEX fs_stats_dir_children_idx ON fs_stats (directory_children DESC)")
      stmt.executeUpdate("CREATE INDEX fs_stats_size_idx ON fs_stats (cumulative_size_bytes DESC)")
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

    //initialize output database
    initDatabase(outputPath)


    val conf = new SparkConf().setAppName("salt-hierarchical-pie-example")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    //create Series for tracking cumulative bytes
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
    val sBytes = new Series(0, pathExtractor, new PathProjection(), Some(bytesExtractor), SumAggregator, None)
    //create Series for tracking total children
    val sChildren = new Series(0, pathExtractor, new PathProjection(), None, CountAggregator, None)
    //create Series for tracking total directory children
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
    val sDirectories = new Series(0, pathExtractor, new PathProjection(), Some(dirChildExtractor), SumAggregator, None)
    //create Series for tracking total executable children
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
    val sExecutables = new Series(0, pathExtractor, new PathProjection(), Some(exChildExtractor), SumAggregator, None)

    //using the Uncharted Spark Pipeline for ETL
    val inputDataPipe = Pipe(() => {
      //load source data
      sqlContext.read.format("com.databricks.spark.csv")
        .option("header", "true")
        .option("inferSchema", "true")
        .load(s"file://${inputPath}")
    })
    //convert to rdd
    .to(ops.core.dataframe.toRDD)
    //pipe to salt
    .to(rdd => {
      val gen = new MapReduceTileGenerator(sc)
      gen.generate(rdd, Seq(sBytes, sChildren, sDirectories, sExecutables), new PathRequest())
    })
    //to simplify example, eliminate SQLite lock contention issue by collecting tiles to master
    .to(_.collect)
    //pipe results to SQLite database
    .to(tiles => {
      Class.forName("org.sqlite.JDBC");
      val db: Connection = DriverManager.getConnection(s"jdbc:sqlite:${outputPath}/fs_stats.sqlite");
      val stmt = db.createStatement();
      try {
        tiles.foreach(tile => {
          val path = tile.coords.substring(0, tile.coords.lastIndexOf("/"))
          val filename = tile.coords.substring(tile.coords.lastIndexOf("/")-1)
          val depth = tile.coords.split("/").length - 1;
          val bytesData = sBytes(tile)
          val childrenData = sChildren(tile)
          val dirsData = sDirectories(tile)
          val execsData = sExecutables(tile)
          stmt.executeUpdate(s"""INSERT INTO fs_stats (path, filename, depth,children, executable_children, directory_children, cumulative_size_bytes)
            VALUES ('${path}', '${filename}', ${depth}, ${childrenData.bins(0).toInt}, ${execsData.bins(0).toInt}, ${dirsData.bins(0).toInt}, ${bytesData.bins(0).toInt})""")
        })
      } finally {
        stmt.close();
        db.close();
      }
    })
    .run
  }
}