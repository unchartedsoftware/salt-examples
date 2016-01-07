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

import software.uncharted.salt.core.projection.Projection
import software.uncharted.salt.core.projection.numeric.MercatorProjection
import org.apache.spark.sql.Row
import scala.collection.mutable.ArrayBuffer

/**
 * A projection for lines into 2D mercator (lon,lat) space
 *
 * @param maxLineLength the maximum length of a line in bins before we drop it
 * @param min the minimum value of a data-space coordinate (minLon, minLat)
 * @param max the maximum value of a data-space coordinate (maxLon, maxLat)
 * @param zoomLevels the TMS/WMS zoom levels to project into
 */
class MercatorLineProjection(
  maxLineLength: Int,
  zoomLevels: Seq[Int],
  min: (Double, Double) = (-180, -85.05112878),
  max: (Double, Double) = (180, 85.05112878),
  tms: Boolean = true
) extends Projection[(Double, Double, Double, Double), (Int, Int, Int), (Int, Int)] {

  private val mercatorProjection = new MercatorProjection(zoomLevels, min, max, tms)

  private def tileBinIndexToUniversalBinIndex(
    tile: (Int, Int, Int),
    bin: (Int, Int),
    maxBin: (Int, Int)
  ): (Int, Int) = {
    val pow2 = 1 << tile._1

    val tileLeft = tile._2 * (maxBin._1+1)

    val tileTop = tms match {
      case true => (pow2 - tile._3 - 1)*(maxBin._2+1)
      case false => tile._3*(maxBin._2+1)
    }

    (tileLeft + bin._1, tileTop + bin._2)
  }

  private def universalBinIndexToTileIndex(
    z: Int,
    ubin: (Int, Int),
    maxBin: (Int, Int)
  ) = {
    val pow2 = 1 << z

    val xBins = (maxBin._1+1)
    val yBins = (maxBin._2+1)

    val tileX = ubin._1/xBins
    val binX = ubin._1 - tileX * xBins;

    val tileY = tms match {
      case true => pow2 - (ubin._2/yBins) - 1;
      case false => ubin._2/yBins
    }

    val binY = tms match {
      case true => ubin._2 - ((pow2 - tileY - 1) * yBins)
      case false => ubin._2 - (tileY) * yBins
    }

    ((z, tileX, tileY), (binX, binY))
  }

  override def project(
    dc: Option[(Double, Double, Double, Double)],
    maxBin: (Int, Int)
  ): Option[Seq[((Int, Int, Int), (Int, Int))]] = {

    val endpointsToLine = new EndPointsToLine(xBins = maxBin._1+1, yBins = maxBin._2+1)
    val xBins = maxBin._1+1
    val yBins = maxBin._2+1

    if (!dc.isDefined) {
      None
    } else {
      // compute start and end-points of the line in WMS/TMS mercator space, for each zoomLevel
      val startdc = (dc.get._1, dc.get._2)
      val enddc = (dc.get._3, dc.get._4)
      val start = mercatorProjection.project(Some(startdc), maxBin)
      val end = mercatorProjection.project(Some(enddc), maxBin)

      if (start.isDefined && end.isDefined) {
        // we'll use Bresenham's algorithm to turn our line into a series of points
        // and append those points to this buffer
        val result = new ArrayBuffer[((Int,  Int, Int), (Int, Int))]()

        for (i <- Range(0, zoomLevels.length)) {
          val n = Math.pow(2, zoomLevels(i)).toInt;
          // convert start and end points of line into universal bin coordinates for use in EndPointsToLine
          val startUniversalBin = tileBinIndexToUniversalBinIndex(start.get(i)._1, start.get(i)._2, maxBin)
          val endUniversalBin =tileBinIndexToUniversalBinIndex(end.get(i)._1, end.get(i)._2, maxBin)

          result.appendAll(
            endpointsToLine
            .endpointsToLineBins(startUniversalBin, endUniversalBin)
            .map(ub => {
              //convert universal bin index back into tile coordinate and tile-relative bin index
              universalBinIndexToTileIndex(zoomLevels(i), ub, maxBin)
            })
          )
        }
        Some(result.toSeq)
      } else {
        None
      }
    }
  }

  override def binTo1D(bin: (Int, Int), maxBin: (Int, Int)): Int = {
    bin._1 + bin._2*(maxBin._1 + 1)
  }
}
