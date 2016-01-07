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

/**
 * Class containing utility functions to convert two endpoints to a line
 * (i.e., pixels as a sequence of bins)
 *
 * Borrowed from aperture-tiles. Original source:
 * https://github.com/unchartedsoftware/aperture-tiles/blob/master/tile-generation/src/main/scala/com/oculusinfo/tilegen/util/EndPointsToLine.scala
 *
 */
class EndPointsToLine(
  val maxLenThresh: Int = 256*4,
  val minLenThresh: Int = 16,
  val xBins: Int = 256,
  val yBins: Int = 256
) extends Serializable {

  private val _tileLen = Math.min(xBins,yBins)

  /**
   * Determine all bins that are required to draw a line two endpoint bins.
   *
   * Uses Bresenham's algorithm for filling in the intermediate pixels in a line.
   *
   * From wikipedia
   *
   * @param start
   *        The start bin, in universal bin index coordinates (not tile bin
   *        coordinates)
   * @param end
   *        The end bin, in universal bin index coordinates (not tile bin
   *        coordinates)
   * @return Pairs of all (bin indices, bin values), in universal bin coordinates, falling on the direct
   *         line between the two endoint bins.  If the line segment is longer than _maxLen then
   *         the line is omitted
   */
  def endpointsToLineBins(start: (Int, Int), end: (Int, Int)): IndexedSeq[(Int, Int)] = {
    val len = calcLen(start, end)

    if (len > maxLenThresh) {
      IndexedSeq[(Int, Int)]()
    } else if (len < minLenThresh) {
      //if the line is too short, just put the points in instead
      IndexedSeq(start, end)
    } else {
      val endsLength = _tileLen

      // Bresenham's algorithm
      val (steep, x0, y0, x1, y1) = getPoints(start, end)

      var x0_mid = 0
      var x1_mid = 0
      var x0_slope = 0.0
      var x1_slope = 0.0

      val lenXends = ((x1-x0)*(endsLength.toDouble/len)).toInt
      x0_mid = lenXends + x0
      x1_mid = x1 - lenXends
      x0_slope = -18.4/lenXends  // -18.4 == log(1e-8) -- need to use a very small number here, but > 0 (b/c log(0) = -Inf)
        //1.0/lenXends  // slope used for fading-out line pixels for long lines
        x1_slope = x0_slope //-x0_slope

      val deltax = x1-x0
      val deltay = math.abs(y1-y0)
      var error = deltax>>1
      var y = y0
      val ystep = if (y0 < y1) 1 else -1

      // x1+1 needed here so that "end" bin is included in Sequence
      Range(x0, x1+1).map(x =>
        {
          val ourY = y
          error = error - deltay
          if (error < 0) {
            y = y + ystep
            error = error + deltax
          }

          if (steep) (ourY, x)
          else (x, ourY)
        }
      )
    }
  }


  /**
   * Re-order coords of two endpoints for efficient implementation of Bresenham's line algorithm
   */
  def getPoints (start: (Int, Int), end: (Int, Int)): (Boolean, Int, Int, Int, Int) = {
    val xs = start._1
    val xe = end._1
    val ys = start._2
    val ye = end._2
    val steep = (math.abs(ye - ys) > math.abs(xe - xs))

    if (steep) {
      if (ys > ye) {
        (steep, ye, xe, ys, xs)
      } else {
        (steep, ys, xs, ye, xe)
      }
    } else {
      if (xs > xe) {
        (steep, xe, ye, xs, ys)
      } else {
        (steep, xs, ys, xe, ye)
      }
    }
  }


  /**
   * Calc length of a line from two endpoints
   */
  def calcLen(start: (Int, Int), end: (Int, Int)): Int = {
    //calc integer length between to bin indices
    var (x0, y0, x1, y1) = (start._1, start._2, end._1, end._2)
    val dx = x1-x0
    val dy = y1-y0
    Math.sqrt(dx*dx + dy*dy).toInt
  }
}
