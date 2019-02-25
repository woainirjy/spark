/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.datasources.v2

import java.util.UUID

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat

import org.apache.spark.internal.io.FileCommitProtocol
import org.apache.spark.sql.{AnalysisException, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.{CaseInsensitiveMap, DateTimeUtils}
import org.apache.spark.sql.execution.datasources.{BasicWriteJobStatsTracker, DataSource, OutputWriterFactory, WriteJobDescription}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.v2.DataSourceOptions
import org.apache.spark.sql.sources.v2.writer.{BatchWrite, SupportsSaveMode, WriteBuilder}
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.util.SerializableConfiguration

abstract class FileWriteBuilder(options: DataSourceOptions)
  extends WriteBuilder with SupportsSaveMode {
  private var schema: StructType = _
  private var queryId: String = _
  private var mode: SaveMode = _

  override def withInputDataSchema(schema: StructType): WriteBuilder = {
    this.schema = schema
    this
  }

  override def withQueryId(queryId: String): WriteBuilder = {
    this.queryId = queryId
    this
  }

  override def mode(mode: SaveMode): WriteBuilder = {
    this.mode = mode
    this
  }

  override def buildForBatch(): BatchWrite = {
    validateInputs()
    val pathName = options.paths().head
    val path = new Path(pathName)
    val sparkSession = SparkSession.active
    val optionsAsScala = options.asMap().asScala.toMap
    val hadoopConf = sparkSession.sessionState.newHadoopConfWithOptions(optionsAsScala)
    val job = getJobInstance(hadoopConf, path)
    val committer = FileCommitProtocol.instantiate(
      sparkSession.sessionState.conf.fileCommitProtocolClass,
      jobId = java.util.UUID.randomUUID().toString,
      outputPath = pathName)
    lazy val description =
      createWriteJobDescription(sparkSession, hadoopConf, job, pathName, optionsAsScala)

    val fs = path.getFileSystem(hadoopConf)
    mode match {
      case SaveMode.ErrorIfExists if fs.exists(path) =>
        val qualifiedOutputPath = path.makeQualified(fs.getUri, fs.getWorkingDirectory)
        throw new AnalysisException(s"path $qualifiedOutputPath already exists.")

      case SaveMode.Ignore if fs.exists(path) =>
        null

      case SaveMode.Overwrite =>
        committer.deleteWithJob(fs, path, true)
        committer.setupJob(job)
        new FileBatchWrite(job, description, committer)

      case _ =>
        committer.setupJob(job)
        new FileBatchWrite(job, description, committer)
    }
  }

  /**
   * Prepares a write job and returns an [[OutputWriterFactory]].  Client side job preparation can
   * be put here.  For example, user defined output committer can be configured here
   * by setting the output committer class in the conf of spark.sql.sources.outputCommitterClass.
   */
  def prepareWrite(
      sqlConf: SQLConf,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory

  /**
   * Returns whether this format supports the given [[DataType]] in write path.
   * By default all data types are supported.
   */
  def supportsDataType(dataType: DataType): Boolean = true

  /**
   * The string that represents the format that this data source provider uses. This is
   * overridden by children to provide a nice alias for the data source. For example:
   *
   * {{{
   *   override def formatName(): String = "ORC"
   * }}}
   */
  def formatName: String

  private def validateInputs(): Unit = {
    assert(schema != null, "Missing input data schema")
    assert(queryId != null, "Missing query ID")
    assert(mode != null, "Missing save mode")
    assert(options.paths().length == 1)
    DataSource.validateSchema(schema)
    schema.foreach { field =>
      if (!supportsDataType(field.dataType)) {
        throw new AnalysisException(
          s"$formatName data source does not support ${field.dataType.catalogString} data type.")
      }
    }
  }

  private def getJobInstance(hadoopConf: Configuration, path: Path): Job = {
    val job = Job.getInstance(hadoopConf)
    job.setOutputKeyClass(classOf[Void])
    job.setOutputValueClass(classOf[InternalRow])
    FileOutputFormat.setOutputPath(job, path)
    job
  }

  private def createWriteJobDescription(
      sparkSession: SparkSession,
      hadoopConf: Configuration,
      job: Job,
      pathName: String,
      options: Map[String, String]): WriteJobDescription = {
    val caseInsensitiveOptions = CaseInsensitiveMap(options)
    // Note: prepareWrite has side effect. It sets "job".
    val outputWriterFactory =
      prepareWrite(sparkSession.sessionState.conf, job, caseInsensitiveOptions, schema)
    val allColumns = schema.toAttributes
    val metrics: Map[String, SQLMetric] = BasicWriteJobStatsTracker.metrics
    val serializableHadoopConf = new SerializableConfiguration(hadoopConf)
    val statsTracker = new BasicWriteJobStatsTracker(serializableHadoopConf, metrics)
    // TODO: after partitioning is supported in V2:
    //       1. filter out partition columns in `dataColumns`.
    //       2. Don't use Seq.empty for `partitionColumns`.
    new WriteJobDescription(
      uuid = UUID.randomUUID().toString,
      serializableHadoopConf = new SerializableConfiguration(job.getConfiguration),
      outputWriterFactory = outputWriterFactory,
      allColumns = allColumns,
      dataColumns = allColumns,
      partitionColumns = Seq.empty,
      bucketIdExpression = None,
      path = pathName,
      customPartitionLocations = Map.empty,
      maxRecordsPerFile = caseInsensitiveOptions.get("maxRecordsPerFile").map(_.toLong)
        .getOrElse(sparkSession.sessionState.conf.maxRecordsPerFile),
      timeZoneId = caseInsensitiveOptions.get(DateTimeUtils.TIMEZONE_OPTION)
        .getOrElse(sparkSession.sessionState.conf.sessionLocalTimeZone),
      statsTrackers = Seq(statsTracker)
    )
  }
}

