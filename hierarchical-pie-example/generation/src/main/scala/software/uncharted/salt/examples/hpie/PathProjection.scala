package software.uncharted.salt.examples.hpie

import software.uncharted.salt.core.projection.Projection

import org.apache.spark.sql.Row

/**
 * This Projection projects an input string (./path/to/filename) into
 * relevant parent path components (./, ./path, ./path/to). In this case,
 * 'tiles' are directories which contain a single bin, and aggregate all their
 * contained files' metadata.
 */
class PathProjection(maxDepth: Int) extends Projection[String, String, Int] {

  private val depths = Seq.range(0,maxDepth+1)

  override def project(dc: Option[String], maxBin: Int): Option[Seq[(String, Int)]] = {
    if (!dc.isDefined) {
      None
    } else {
      if (dc.get.indexOf("/") < 0) {
        val path = dc.get
        Some(Seq((path,0)))
      } else {
        //map file path to all its parent path components
        val pathComponents = dc.get.split("/")
        Some(
          depths.map(z => {
            val path = pathComponents.slice(0,z+1).mkString("/")
            (path,0)
          })
        )
      }
    }
  }

  override def binTo1D(bin: Int, maxBin: Int): Int = {
    bin
  }

  override def binFrom1D (index: Int, maxBin: Int): Int = {
    index
  }
}
