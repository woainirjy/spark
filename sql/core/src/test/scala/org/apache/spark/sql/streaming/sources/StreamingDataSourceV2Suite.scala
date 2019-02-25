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

package org.apache.spark.sql.streaming.sources

import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.execution.streaming.{RateStreamOffset, Sink, StreamingQueryWrapper}
import org.apache.spark.sql.execution.streaming.continuous.ContinuousTrigger
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.{DataSourceRegister, StreamSinkProvider}
import org.apache.spark.sql.sources.v2._
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.sources.v2.reader.streaming._
import org.apache.spark.sql.sources.v2.writer.WriteBuilder
import org.apache.spark.sql.streaming.{OutputMode, StreamingQuery, StreamTest, Trigger}
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils

class FakeDataStream extends MicroBatchStream with ContinuousStream {
  override def deserializeOffset(json: String): Offset = RateStreamOffset(Map())
  override def commit(end: Offset): Unit = {}
  override def stop(): Unit = {}
  override def initialOffset(): Offset = RateStreamOffset(Map())
  override def latestOffset(): Offset = RateStreamOffset(Map())
  override def mergeOffsets(offsets: Array[PartitionOffset]): Offset = RateStreamOffset(Map())
  override def planInputPartitions(start: Offset, end: Offset): Array[InputPartition] = {
    throw new IllegalStateException("fake source - cannot actually read")
  }
  override def planInputPartitions(start: Offset): Array[InputPartition] = {
    throw new IllegalStateException("fake source - cannot actually read")
  }
  override def createReaderFactory(): PartitionReaderFactory = {
    throw new IllegalStateException("fake source - cannot actually read")
  }
  override def createContinuousReaderFactory(): ContinuousPartitionReaderFactory = {
    throw new IllegalStateException("fake source - cannot actually read")
  }
}

class FakeScanBuilder extends ScanBuilder with Scan {
  override def build(): Scan = this
  override def readSchema(): StructType = StructType(Seq())
  override def toMicroBatchStream(checkpointLocation: String): MicroBatchStream = new FakeDataStream
  override def toContinuousStream(checkpointLocation: String): ContinuousStream = new FakeDataStream
}

trait FakeMicroBatchReadTable extends Table with SupportsMicroBatchRead {
  override def name(): String = "fake"
  override def schema(): StructType = StructType(Seq())
  override def newScanBuilder(options: DataSourceOptions): ScanBuilder = new FakeScanBuilder
}

trait FakeContinuousReadTable extends Table with SupportsContinuousRead {
  override def name(): String = "fake"
  override def schema(): StructType = StructType(Seq())
  override def newScanBuilder(options: DataSourceOptions): ScanBuilder = new FakeScanBuilder
}

trait FakeStreamingWriteTable extends Table with SupportsStreamingWrite {
  override def name(): String = "fake"
  override def schema(): StructType = StructType(Seq())
  override def newWriteBuilder(options: DataSourceOptions): WriteBuilder = {
    throw new IllegalStateException("fake sink - cannot actually write")
  }
}

class FakeReadMicroBatchOnly
    extends DataSourceRegister
    with TableProvider
    with SessionConfigSupport {
  override def shortName(): String = "fake-read-microbatch-only"

  override def keyPrefix: String = shortName()

  override def getTable(options: DataSourceOptions): Table = {
    LastReadOptions.options = options
    new FakeMicroBatchReadTable {}
  }
}

class FakeReadContinuousOnly
    extends DataSourceRegister
    with TableProvider
    with SessionConfigSupport {
  override def shortName(): String = "fake-read-continuous-only"

  override def keyPrefix: String = shortName()

  override def getTable(options: DataSourceOptions): Table = {
    LastReadOptions.options = options
    new FakeContinuousReadTable {}
  }
}

class FakeReadBothModes extends DataSourceRegister with TableProvider {
  override def shortName(): String = "fake-read-microbatch-continuous"

  override def getTable(options: DataSourceOptions): Table = {
    new Table with FakeMicroBatchReadTable with FakeContinuousReadTable {}
  }
}

class FakeReadNeitherMode extends DataSourceRegister with TableProvider {
  override def shortName(): String = "fake-read-neither-mode"

  override def getTable(options: DataSourceOptions): Table = {
    new Table {
      override def name(): String = "fake"
      override def schema(): StructType = StructType(Nil)
    }
  }
}

class FakeWriteOnly
    extends DataSourceRegister
    with TableProvider
    with SessionConfigSupport {
  override def shortName(): String = "fake-write-microbatch-continuous"

  override def keyPrefix: String = shortName()

  override def getTable(options: DataSourceOptions): Table = {
    LastWriteOptions.options = options
    new Table with FakeStreamingWriteTable {
      override def name(): String = "fake"
      override def schema(): StructType = StructType(Nil)
    }
  }
}

class FakeNoWrite extends DataSourceRegister with TableProvider {
  override def shortName(): String = "fake-write-neither-mode"
  override def getTable(options: DataSourceOptions): Table = {
    new Table {
      override def name(): String = "fake"
      override def schema(): StructType = StructType(Nil)
    }
  }
}

case class FakeWriteV1FallbackException() extends Exception

class FakeSink extends Sink {
  override def addBatch(batchId: Long, data: DataFrame): Unit = {}
}

class FakeWriteSupportProviderV1Fallback extends DataSourceRegister
  with TableProvider with StreamSinkProvider {

  override def createSink(
      sqlContext: SQLContext,
      parameters: Map[String, String],
      partitionColumns: Seq[String],
      outputMode: OutputMode): Sink = {
    new FakeSink()
  }

  override def shortName(): String = "fake-write-v1-fallback"

  override def getTable(options: DataSourceOptions): Table = {
    new Table with FakeStreamingWriteTable {
      override def name(): String = "fake"
      override def schema(): StructType = StructType(Nil)
    }
  }
}

object LastReadOptions {
  var options: DataSourceOptions = _

  def clear(): Unit = {
    options = null
  }
}

object LastWriteOptions {
  var options: DataSourceOptions = _

  def clear(): Unit = {
    options = null
  }
}

class StreamingDataSourceV2Suite extends StreamTest {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val fakeCheckpoint = Utils.createTempDir()
    spark.conf.set("spark.sql.streaming.checkpointLocation", fakeCheckpoint.getCanonicalPath)
  }

  override def afterEach(): Unit = {
    LastReadOptions.clear()
    LastWriteOptions.clear()
  }

  val readFormats = Seq(
    "fake-read-microbatch-only",
    "fake-read-continuous-only",
    "fake-read-microbatch-continuous",
    "fake-read-neither-mode")
  val writeFormats = Seq(
    "fake-write-microbatch-continuous",
    "fake-write-neither-mode")
  val triggers = Seq(
    Trigger.Once(),
    Trigger.ProcessingTime(1000),
    Trigger.Continuous(1000))

  private def testPositiveCase(readFormat: String, writeFormat: String, trigger: Trigger): Unit = {
    testPositiveCaseWithQuery(readFormat, writeFormat, trigger)(() => _)
  }

  private def testPositiveCaseWithQuery(
      readFormat: String,
      writeFormat: String,
      trigger: Trigger)(check: StreamingQuery => Unit): Unit = {
    val query = spark.readStream
      .format(readFormat)
      .load()
      .writeStream
      .format(writeFormat)
      .trigger(trigger)
      .start()
    check(query)
    query.stop()
  }

  private def testNegativeCase(
      readFormat: String,
      writeFormat: String,
      trigger: Trigger,
      errorMsg: String) = {
    val ex = intercept[UnsupportedOperationException] {
      testPositiveCase(readFormat, writeFormat, trigger)
    }
    assert(ex.getMessage.contains(errorMsg))
  }

  private def testPostCreationNegativeCase(
      readFormat: String,
      writeFormat: String,
      trigger: Trigger,
      errorMsg: String) = {
    val query = spark.readStream
      .format(readFormat)
      .load()
      .writeStream
      .format(writeFormat)
      .trigger(trigger)
      .start()

    eventually(timeout(streamingTimeout)) {
      assert(query.exception.isDefined)
      assert(query.exception.get.cause != null)
      assert(query.exception.get.cause.getMessage.contains(errorMsg))
    }
  }

  test("disabled v2 write") {
    // Ensure the V2 path works normally and generates a V2 sink..
    testPositiveCaseWithQuery(
      "fake-read-microbatch-continuous", "fake-write-v1-fallback", Trigger.Once()) { v2Query =>
      assert(v2Query.asInstanceOf[StreamingQueryWrapper].streamingQuery.sink
        .isInstanceOf[Table])
    }

    // Ensure we create a V1 sink with the config. Note the config is a comma separated
    // list, including other fake entries.
    val fullSinkName = classOf[FakeWriteSupportProviderV1Fallback].getName
    withSQLConf(SQLConf.DISABLED_V2_STREAMING_WRITERS.key -> s"a,b,c,test,$fullSinkName,d,e") {
      testPositiveCaseWithQuery(
        "fake-read-microbatch-continuous", "fake-write-v1-fallback", Trigger.Once()) { v1Query =>
        assert(v1Query.asInstanceOf[StreamingQueryWrapper].streamingQuery.sink
          .isInstanceOf[FakeSink])
      }
    }
  }

  Seq(
    Tuple2(classOf[FakeReadMicroBatchOnly], Trigger.Once()),
    Tuple2(classOf[FakeReadContinuousOnly], Trigger.Continuous(1000))
  ).foreach { case (source, trigger) =>
    test(s"SPARK-25460: session options are respected in structured streaming sources - $source") {
      // `keyPrefix` and `shortName` are the same in this test case
      val readSource = source.getConstructor().newInstance().shortName()
      val writeSource = "fake-write-microbatch-continuous"

      val readOptionName = "optionA"
      withSQLConf(s"spark.datasource.$readSource.$readOptionName" -> "true") {
        testPositiveCaseWithQuery(readSource, writeSource, trigger) { _ =>
          eventually(timeout(streamingTimeout)) {
            // Write options should not be set.
            assert(LastWriteOptions.options.getBoolean(readOptionName, false) == false)
            assert(LastReadOptions.options.getBoolean(readOptionName, false) == true)
          }
        }
      }

      val writeOptionName = "optionB"
      withSQLConf(s"spark.datasource.$writeSource.$writeOptionName" -> "true") {
        testPositiveCaseWithQuery(readSource, writeSource, trigger) { _ =>
          eventually(timeout(streamingTimeout)) {
            // Read options should not be set.
            assert(LastReadOptions.options.getBoolean(writeOptionName, false) == false)
            assert(LastWriteOptions.options.getBoolean(writeOptionName, false) == true)
          }
        }
      }
    }
  }

  // Get a list of (read, write, trigger) tuples for test cases.
  val cases = readFormats.flatMap { read =>
    writeFormats.flatMap { write =>
      triggers.map(t => (write, t))
    }.map {
      case (write, t) => (read, write, t)
    }
  }

  for ((read, write, trigger) <- cases) {
    testQuietly(s"stream with read format $read, write format $write, trigger $trigger") {
      val sourceTable = DataSource.lookupDataSource(read, spark.sqlContext.conf).getConstructor()
        .newInstance().asInstanceOf[TableProvider].getTable(DataSourceOptions.empty())

      val sinkTable = DataSource.lookupDataSource(write, spark.sqlContext.conf).getConstructor()
        .newInstance().asInstanceOf[TableProvider].getTable(DataSourceOptions.empty())

      (sourceTable, sinkTable, trigger) match {
        // Valid microbatch queries.
        case (_: SupportsMicroBatchRead, _: SupportsStreamingWrite, t)
            if !t.isInstanceOf[ContinuousTrigger] =>
          testPositiveCase(read, write, trigger)

        // Valid continuous queries.
        case (_: SupportsContinuousRead, _: SupportsStreamingWrite,
              _: ContinuousTrigger) =>
          testPositiveCase(read, write, trigger)

        // Invalid - can't read at all
        case (r, _, _) if !r.isInstanceOf[SupportsMicroBatchRead] &&
            !r.isInstanceOf[SupportsContinuousRead] =>
          testNegativeCase(read, write, trigger,
            s"Data source $read does not support streamed reading")

        // Invalid - can't write
        case (_, w, _) if !w.isInstanceOf[SupportsStreamingWrite] =>
          testNegativeCase(read, write, trigger,
            s"Data source $write does not support streamed writing")

        // Invalid - trigger is continuous but reader is not
        case (r, _: SupportsStreamingWrite, _: ContinuousTrigger)
            if !r.isInstanceOf[SupportsContinuousRead] =>
          testNegativeCase(read, write, trigger,
            s"Data source $read does not support continuous processing")

        // Invalid - trigger is microbatch but reader is not
        case (r, _, t) if !r.isInstanceOf[SupportsMicroBatchRead] &&
            !t.isInstanceOf[ContinuousTrigger] =>
          testPostCreationNegativeCase(read, write, trigger,
            s"Data source $read does not support microbatch processing")
      }
    }
  }
}
