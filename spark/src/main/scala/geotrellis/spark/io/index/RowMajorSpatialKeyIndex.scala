package geotrellis.spark.io.index

import geotrellis.spark._

import spire.syntax.cfor._

/** Represents a row major ordering for SpatialKey */
class RowMajorSpatialKeyIndex(layoutCols: Int) extends KeyIndex[SpatialKey] {
  def toIndex(key: SpatialKey): Long = 
    toIndex(key.col, key.row)

  def toIndex(col: Int, row: Int): Long =
    (layoutCols * row + col).toLong

  def indexRanges(keyRange: (SpatialKey, SpatialKey)): Seq[(Long, Long)] = {
    val SpatialKey(colMin, rowMin) = keyRange._1
    val SpatialKey(colMax, rowMax) = keyRange._2

    val cols = colMax - colMin + 1
    val rows = rowMax - rowMin

    val result = Array.ofDim[(Long, Long)](rowMax - rowMin + 1)

    cfor(0)(_ <= rows, _ + 1) { i =>
      val row = rowMin + i
      val min = toIndex(colMin, row)
      result(i) = (min, min + cols)
    }
    result
  }
}
