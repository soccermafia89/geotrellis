package geotrellis.spark.io.hadoop

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop.formats._
import geotrellis.spark.io.index._
import geotrellis.spark.op.stats._
import geotrellis.raster._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.SequenceFile
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat
import org.apache.hadoop.mapreduce.lib.output.{MapFileOutputFormat, SequenceFileOutputFormat}
import org.apache.hadoop.mapreduce.{JobContext, Job}
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.SparkContext._
import scala.reflect._
import spray.json._

case class HadoopRasterCatalogConfig(
  /** Compression factor for determining how many tiles can fit into
    * one block on a Hadoop-readable file system. */
  compressionFactor: Double,

  /** Name of the splits file that contains the partitioner data */
  splitsFile: String,

  /** Name of file that will contain the metadata under the layer path. */
  metaDataFileName: String,

  /** Name of the subdirectory under the catalog root that will hold the attributes. */
  attributeDir: String,

  /** Creates a subdirectory path based on a layer id. */
  layerDataDir: LayerId => String
) {
  /** Sequence file data directory for reading data.
    * Don't see a reason why the API would allow this to be modified
    */
  final val SEQFILE_GLOB = "/*[0-9]*/data"
}

object HadoopRasterCatalogConfig {
  val DEFAULT =
    HadoopRasterCatalogConfig(
      compressionFactor = 1.3, // Assume tiles can be compressed 30% (so, compressionFactor - 1)
      splitsFile = "splits",
      metaDataFileName = "metadata.json",
      attributeDir = "attributes",
      layerDataDir = { layerId: LayerId => s"${layerId.name}/${layerId.zoom}" }
    )
}


object HadoopRasterCatalog {
  def apply(
    rootPath: Path,
    paramsConfig: DefaultParams[String] = BaseParams,
    catalogConfig: HadoopRasterCatalogConfig = HadoopRasterCatalogConfig.DEFAULT)(implicit sc: SparkContext
  ): HadoopRasterCatalog = {
    HdfsUtils.ensurePathExists(rootPath, sc.hadoopConfiguration)
    val metaDataCatalog = new HadoopLayerMetaDataCatalog(sc.hadoopConfiguration, rootPath, catalogConfig.metaDataFileName)
    val attributeStore = new HadoopAttributeStore(sc.hadoopConfiguration, new Path(rootPath, catalogConfig.attributeDir))
    new HadoopRasterCatalog(rootPath, metaDataCatalog, attributeStore, paramsConfig, catalogConfig)
  }

  lazy val BaseParams = new DefaultParams[String](Map.empty.withDefaultValue(""), Map.empty)
}

class HadoopRasterCatalog(
  rootPath: Path,
  val metaDataCatalog: Store[LayerId, HadoopLayerMetaData],
  attributeStore: HadoopAttributeStore,
  paramsConfig: DefaultParams[String],
  catalogConfig: HadoopRasterCatalogConfig)(implicit sc: SparkContext
) {

  def defaultPath[K: ClassTag](layerId: LayerId, subDir: String): Path = {
    val firstPart =
      if(subDir == "") {
        paramsConfig.paramsFor[K](layerId) match {
          case Some(configSubDir) if configSubDir != "" =>
            new Path(rootPath, configSubDir)
          case _ => rootPath
        }
      } else { 
        new Path(rootPath, subDir)
      }

    new Path(firstPart, catalogConfig.layerDataDir(layerId))
  }

  def reader[K: RasterRDDReaderProvider: JsonFormat](): RasterRDDReader[K] =
    new RasterRDDReader[K] {
      def read(layerId: LayerId): RasterRDD[K] = {
        val keyBounds = attributeStore.read[KeyBounds[K]](layerId, "keyBounds")
        val metaData = metaDataCatalog.read(layerId)
        val provider = implicitly[RasterRDDReaderProvider[K]]
        val index = provider.index(metaData.rasterMetaData.tileLayout, keyBounds)
        val rddReader = provider.reader(catalogConfig, metaData, index)
        rddReader.read(layerId)
      }
    }

  def writer[K: RasterRDDWriterProvider: Ordering: JsonFormat: ClassTag](): Writer[LayerId, RasterRDD[K]] =
    writer[K]("")

  def writer[K: RasterRDDWriterProvider: Ordering: JsonFormat: ClassTag](clobber: Boolean): Writer[LayerId, RasterRDD[K]] =
    writer("", clobber)

  def writer[K: RasterRDDWriterProvider: Ordering: JsonFormat: ClassTag](subDir: Path): Writer[LayerId, RasterRDD[K]] =
    writer[K](subDir.toString)

  def writer[K: RasterRDDWriterProvider: Ordering: JsonFormat: ClassTag](subDir: Path, clobber: Boolean): Writer[LayerId, RasterRDD[K]] =
    writer[K](subDir.toString, clobber)

  def writer[K: RasterRDDWriterProvider: Ordering: JsonFormat: ClassTag](subDir: String): Writer[LayerId, RasterRDD[K]] =
    writer[K](subDir, clobber = true)

  def writer[K: RasterRDDWriterProvider: Ordering: JsonFormat: ClassTag](subDir: String, clobber: Boolean): Writer[LayerId, RasterRDD[K]] =
    new Writer[LayerId, RasterRDD[K]] {
      def write(layerId: LayerId, rdd: RasterRDD[K]): Unit = {
        rdd.persist()

        val layerPath = defaultPath[K](layerId, subDir)
        val md = HadoopLayerMetaData(layerId, rdd.metaData, layerPath)
        val minKey = rdd.map(_._1).min
        val maxKey = rdd.map(_._1).max
        val keyBounds = KeyBounds(minKey, maxKey)

        val provider = implicitly[RasterRDDWriterProvider[K]]
        val index = provider.index(md.rasterMetaData.tileLayout, keyBounds)
        val rddWriter = provider.writer(catalogConfig, md, index, clobber)

        rddWriter.write(layerId, rdd)

        attributeStore.write[KeyBounds[K]](layerId, "keyBounds", keyBounds)

        // Write metadata afer raster, since writing the raster could clobber the directory
        metaDataCatalog.write(layerId, md)

        rdd.unpersist(blocking = false)
      }
    }

  def tileReader[K: TileReaderProvider](layerId: LayerId): Reader[K, Tile] = {
    val layerMetaData = metaDataCatalog.read(layerId)
    implicitly[TileReaderProvider[K]].reader(layerMetaData)
  }
}
