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
package org.apache.spark.sql.execution.datasources.v2.orc

import java.net.URI

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.{JobID, TaskAttemptID, TaskID, TaskType}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.orc.{OrcConf, OrcFile}
import org.apache.orc.mapred.OrcStruct
import org.apache.orc.mapreduce.OrcInputFormat

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.JoinedRow
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.execution.datasources.{PartitionedFile, PartitioningUtils}
import org.apache.spark.sql.execution.datasources.orc.{OrcColumnarBatchReader, OrcDeserializer, OrcUtils}
import org.apache.spark.sql.execution.datasources.v2._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.v2.reader.{InputPartition, PartitionReader}
import org.apache.spark.sql.types.{AtomicType, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.SerializableConfiguration

/**
 * A factory used to create Orc readers.
 *
 * @param sqlConf SQL configuration.
 * @param broadcastedConf Broadcast serializable Hadoop Configuration.
 * @param dataSchema Schema of orc files.
 * @param partitionSchema Schema of partitions.
 * @param readSchema Required schema in the batch scan.
 */
case class OrcPartitionReaderFactory(
    sqlConf: SQLConf,
    broadcastedConf: Broadcast[SerializableConfiguration],
    dataSchema: StructType,
    partitionSchema: StructType,
    readSchema: StructType) extends FilePartitionReaderFactory {
  private val isCaseSensitive = sqlConf.caseSensitiveAnalysis
  private val capacity = sqlConf.orcVectorizedReaderBatchSize

  override def supportColumnarReads(partition: InputPartition): Boolean = {
    sqlConf.orcVectorizedReaderEnabled && sqlConf.wholeStageEnabled &&
      readSchema.length <= sqlConf.wholeStageMaxNumFields &&
      readSchema.forall(_.dataType.isInstanceOf[AtomicType])
  }

  override def buildReader(file: PartitionedFile): PartitionReader[InternalRow] = {
    val conf = broadcastedConf.value.value

    val filePath = new Path(new URI(file.filePath))

    val fs = filePath.getFileSystem(conf)
    val readerOptions = OrcFile.readerOptions(conf).filesystem(fs)
    val reader = OrcFile.createReader(filePath, readerOptions)

    val requestedColIdsOrEmptyFile = OrcUtils.requestedColumnIds(
      isCaseSensitive, dataSchema, readSchema, reader, conf)

    if (requestedColIdsOrEmptyFile.isEmpty) {
      new EmptyPartitionReader[InternalRow]
    } else {
      val requestedColIds = requestedColIdsOrEmptyFile.get
      assert(requestedColIds.length == readSchema.length,
        "[BUG] requested column IDs do not match required schema")
      val taskConf = new Configuration(conf)
      taskConf.set(OrcConf.INCLUDE_COLUMNS.getAttribute,
        requestedColIds.filter(_ != -1).sorted.mkString(","))

      val fileSplit = new FileSplit(filePath, file.start, file.length, Array.empty)
      val attemptId = new TaskAttemptID(new TaskID(new JobID(), TaskType.MAP, 0), 0)
      val taskAttemptContext = new TaskAttemptContextImpl(taskConf, attemptId)

      val requiredDataSchema = subtractSchema(readSchema, partitionSchema)
      val orcRecordReader = new OrcInputFormat[OrcStruct]
        .createRecordReader(fileSplit, taskAttemptContext)

      val fullSchema = requiredDataSchema.toAttributes ++ partitionSchema.toAttributes
      val unsafeProjection = GenerateUnsafeProjection.generate(fullSchema, fullSchema)
      val deserializer = new OrcDeserializer(dataSchema, requiredDataSchema, requestedColIds)

      val projection = if (partitionSchema.length == 0) {
        (value: OrcStruct) => unsafeProjection(deserializer.deserialize(value))
      } else {
        val joinedRow = new JoinedRow()
        (value: OrcStruct) =>
          unsafeProjection(joinedRow(deserializer.deserialize(value), file.partitionValues))
      }
      new PartitionRecordReaderWithProject(orcRecordReader, projection)
    }
  }

  override def buildColumnarReader(file: PartitionedFile): PartitionReader[ColumnarBatch] = {
    val conf = broadcastedConf.value.value

    val filePath = new Path(new URI(file.filePath))

    val fs = filePath.getFileSystem(conf)
    val readerOptions = OrcFile.readerOptions(conf).filesystem(fs)
    val reader = OrcFile.createReader(filePath, readerOptions)

    val requestedColIdsOrEmptyFile = OrcUtils.requestedColumnIds(
      isCaseSensitive, dataSchema, readSchema, reader, conf)

    if (requestedColIdsOrEmptyFile.isEmpty) {
      new EmptyPartitionReader
    } else {
      val requestedColIds = requestedColIdsOrEmptyFile.get
      assert(requestedColIds.length == readSchema.length,
        "[BUG] requested column IDs do not match required schema")
      val taskConf = new Configuration(conf)
      taskConf.set(OrcConf.INCLUDE_COLUMNS.getAttribute,
        requestedColIds.filter(_ != -1).sorted.mkString(","))

      val fileSplit = new FileSplit(filePath, file.start, file.length, Array.empty)
      val attemptId = new TaskAttemptID(new TaskID(new JobID(), TaskType.MAP, 0), 0)
      val taskAttemptContext = new TaskAttemptContextImpl(taskConf, attemptId)

      val batchReader = new OrcColumnarBatchReader(capacity)
      batchReader.initialize(fileSplit, taskAttemptContext)
      val columnNameMap = partitionSchema.fields.map(
        PartitioningUtils.getColName(_, isCaseSensitive)).zipWithIndex.toMap
      val requestedPartitionColIds = readSchema.fields.map { field =>
        columnNameMap.getOrElse(PartitioningUtils.getColName(field, isCaseSensitive), -1)
      }

      batchReader.initBatch(
        reader.getSchema,
        readSchema.fields,
        requestedColIds,
        requestedPartitionColIds,
        file.partitionValues)
      new PartitionRecordReader(batchReader)
    }
  }

  /**
   * Returns a new StructType that is a copy of the original StructType, removing any items that
   * also appear in other StructType. The order is preserved from the original StructType.
   */
  private def subtractSchema(original: StructType, other: StructType): StructType = {
    val otherNameSet = other.fields.map(PartitioningUtils.getColName(_, isCaseSensitive)).toSet
    val fields = original.fields.filterNot { field =>
      otherNameSet.contains(PartitioningUtils.getColName(field, isCaseSensitive))
    }

    StructType(fields)
  }

}
