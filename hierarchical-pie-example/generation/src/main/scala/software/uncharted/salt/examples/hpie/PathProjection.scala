package software.uncharted.salt.examples.hpie

import software.uncharted.salt.core.projection.Projection

import org.apache.spark.sql.Row

/**
 * This Projection projects an input string (./path/to/filename) into
 * just the path component (./path/to). In this case, 'tiles' are
 * directories which contain a single bin, and aggregate all their
 * contained files' metadata. Zoom levels are irrelevant, since
 * each file can only belong to one directory, and thus to only one
 * zoom level.
 */
class PathProjection extends Projection[String, String, Int] {

  override def project(dc: Option[String], z: Int, maxBin: Int): Option[(String, Int)] = {
    if (!dc.isDefined) {
      None
    } else if (dc.get.indexOf("/") < 0) {
      None
    } else {
      //extract path from input string
      val path = dc.get.substring(0, dc.get.lastIndexOf("/"))
      Some((path,0))
    }
  }

  override def binTo1D(bin: Int, maxBin: Int): Int = {
    bin
  }
}
