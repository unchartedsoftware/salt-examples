package software.uncharted.salt.examples.hpie

import software.uncharted.salt.core.generation.request.TileRequest

/**
 * A simple Request for filtering source data paths based on a desired root
 * directory. Not checking that String format matches paths for simplicity.
 */
class PathRequest(root: String = "./") extends TileRequest[String] {
  def inRequest(path: String): Boolean = {
    path.indexOf(root) == 0
  }

  def levels(): Seq[Int] = {
    Seq(0)
  }
}
