package software.uncharted.salt.examples.hpie

import software.uncharted.salt.core.generation.request.TileRequest

/**
 * A simple Request for filtering source data paths based on a desired root
 * directory. Not checking that String format matches paths for simplicity.
 */
class PathRequest(root: String = "./", maxDepth: Int) extends TileRequest[String] {
  def inRequest(path: String): Boolean = {
    path.indexOf(root) == 0
  }

  def levels(): Seq[Int] = {
    (0 to maxDepth).toSeq
  }
}
