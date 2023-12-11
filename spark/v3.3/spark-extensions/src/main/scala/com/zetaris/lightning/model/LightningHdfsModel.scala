/*
 * Copyright 2023 ZETARIS Pty Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.zetaris.lightning.model

import com.zetaris.lightning.execution.command.DataSourceType.FileTypeSource
import com.zetaris.lightning.model.LightningModel.LightningModel
import com.zetaris.lightning.model.serde.DataSource.DataSource
import com.zetaris.lightning.model.serde.DataSource.toJson
import com.zetaris.lightning.model.serde.mapToJson
import com.zetaris.lightning.util.FileSystemUtils
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

// TODO : Convert FileSystem API to HDFS API
class LightningHdfsModel(prop: CaseInsensitiveStringMap) extends LightningModel{
  if (!prop.containsKey(LightningModel.LIGHTNING_MODEL_WAREHOUSE_KEY)) {
    throw new RuntimeException(s"${LightningModel.LIGHTNING_MODEL_WAREHOUSE_KEY} is not set in spark conf")
  }

  private val modelDir = prop.get(LightningModel.LIGHTNING_MODEL_WAREHOUSE_KEY)
  val DATASOURCE_DIR = "datasource"
  val METASTORE_DIR = "metastore"

  createModelDirIfNotExist()

  private def nameSpaceToDir(namespace: Seq[String]) = namespace.mkString("/")

  private def createModelDirIfNotExist(): Unit = {
    FileSystemUtils.createFolderIfNotExist(modelDir)

    // datasource
    FileSystemUtils.createFolderIfNotExist(s"$modelDir/$DATASOURCE_DIR")

    // metastore definition
    FileSystemUtils.createFolderIfNotExist(s"$modelDir/$METASTORE_DIR")
  }

  /**
   * Save data source into sub directory of datasource
   *
   * @param dataSource
   * @param replace
   * @return saved file path
   */
  override def saveDataSource(dataSource: DataSource, replace: Boolean): String = {
    val json = toJson(dataSource)
    val dir = nameSpaceToDir(dataSource.namespace)
    val filePath = dataSource.dataSourceType match {
      case _: FileTypeSource =>
        s"$modelDir/$dir/${dataSource.name}_fs.json"
      case _ =>
        s"$modelDir/$dir/${dataSource.name}.json"
    }

    FileSystemUtils.saveFile(filePath, json, replace)
    filePath
  }

  override def loadDataSources(namespace: Array[String], name: String = null): List[DataSource] = {
    val subDir = nameSpaceToDir(namespace)
    if (name == null) {
      FileSystemUtils.listFiles(s"$modelDir/$subDir").filter(_.endsWith(".json")).map { file =>
        val json = FileSystemUtils.readFile(file)
        serde.DataSource(json)
      }.toList
    } else {
      val dataSourcePath = s"$modelDir/$subDir/$name.json"
      val fileSourcePath = s"$modelDir/$subDir/${name}_fs.json"

      if (FileSystemUtils.fileExists(dataSourcePath)) {
        val json = FileSystemUtils.readFile(dataSourcePath)
        List(serde.DataSource(json))
      } else if (FileSystemUtils.fileExists(fileSourcePath)) {
        val json = FileSystemUtils.readFile(fileSourcePath)
        List(serde.DataSource(json))
      } else {
        List.empty
      }
    }
  }

  override def listNamespaces(namespace: Seq[String]): Seq[String] = {
    val subDir = nameSpaceToDir(namespace)
    val fullPath = s"$modelDir/$subDir"
    FileSystemUtils.listDirectories(fullPath) ++
      FileSystemUtils.listFiles(fullPath).filter(file => file.endsWith(".json") && !file.endsWith("_fs.json"))
        .map(_.dropRight(5))

  }

  override def listTables(namespace: Array[String]): Seq[String] = {
    val subDir = nameSpaceToDir(namespace)
    val fullPath = s"$modelDir/$subDir"
    FileSystemUtils.listFiles(fullPath).filter(file => file.endsWith("_fs.json"))
      .map(_.dropRight(8))
  }

  override def createNamespace(namespace: Array[String], metadata: java.util.Map[String, String]): Unit = {
    import scala.collection.JavaConverters.mapAsScalaMap
    val subDir = nameSpaceToDir(namespace)
    val fullPath = s"$modelDir/$subDir"
    FileSystemUtils.createFolderIfNotExist(fullPath)

    val json = mapToJson(mapAsScalaMap(metadata).toMap)
    FileSystemUtils.saveFile(s"$fullPath/.properties", json)
  }

  def dropNamespace(namespace: Array[String], cascade: Boolean): Unit = {
    val subDir = nameSpaceToDir(namespace)
    val fullPath = s"$modelDir/$subDir"
    val subNamespaces = FileSystemUtils.listDirectories(fullPath)
    if (!cascade && subNamespaces.nonEmpty) {
      throw new RuntimeException(s"${LightningModel.toFqn(namespace)} has sub namespaces")
    }

    FileSystemUtils.deleteDirectory(fullPath)
  }

  /**
   * save table under the given namespace
   *
   * @param namespace
   * @param name
   * @param schema
   */
  override def saveTable(namespace: Array[String], name: String, schema: StructType): Unit = {
    val subDir = nameSpaceToDir(namespace)
    val fullPath = s"$modelDir/$subDir/$name.json"
    FileSystemUtils.saveFile(fullPath, schema.json)
  }
}
