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

import org.apache.hadoop.fs.FileStatus

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.datasources.orc.OrcUtils
import org.apache.spark.sql.execution.datasources.v2.FileTable
import org.apache.spark.sql.sources.v2.DataSourceOptions
import org.apache.spark.sql.sources.v2.writer.WriteBuilder
import org.apache.spark.sql.types.StructType

case class OrcTable(
    name: String,
    sparkSession: SparkSession,
    options: DataSourceOptions,
    userSpecifiedSchema: Option[StructType])
  extends FileTable(sparkSession, options, userSpecifiedSchema) {
  override def newScanBuilder(options: DataSourceOptions): OrcScanBuilder =
    new OrcScanBuilder(sparkSession, fileIndex, schema, dataSchema, options)

  override def inferSchema(files: Seq[FileStatus]): Option[StructType] =
    OrcUtils.readSchema(sparkSession, files)

  override def newWriteBuilder(options: DataSourceOptions): WriteBuilder =
    new OrcWriteBuilder(options)
}
