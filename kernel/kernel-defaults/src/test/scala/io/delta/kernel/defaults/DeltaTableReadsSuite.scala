/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.defaults

import java.io.File
import java.math.BigDecimal
import java.sql.Date

import scala.collection.JavaConverters._

import io.delta.golden.GoldenTableUtils.goldenTablePath
import io.delta.kernel.{Table, TableNotFoundException}
import io.delta.kernel.defaults.internal.DefaultKernelUtils
import io.delta.kernel.defaults.utils.{TestRow, TestUtils}
import io.delta.kernel.internal.util.InternalUtils.daysSinceEpoch
import org.apache.hadoop.shaded.org.apache.commons.io.FileUtils
import org.scalatest.funsuite.AnyFunSuite

class DeltaTableReadsSuite extends AnyFunSuite with TestUtils {

  //////////////////////////////////////////////////////////////////////////////////
  // Timestamp type tests
  //////////////////////////////////////////////////////////////////////////////////

  // Below table is written in either UTC or PDT for the golden tables
  // Kernel always interprets partition timestamp columns in UTC
  /*
  id: int  | Part (TZ agnostic): timestamp     | time : timestamp
  ------------------------------------------------------------------------
  0        | 2020-01-01 08:09:10.001           | 2020-02-01 08:09:10
  1        | 2021-10-01 08:09:20               | 1999-01-01 09:00:00
  2        | 2021-10-01 08:09:20               | 2000-01-01 09:00:00
  3        | 1969-01-01 00:00:00               | 1969-01-01 00:00:00
  4        | null                              | null
  */

  def row0: TestRow = TestRow(
    0,
    1577866150001000L, // 2020-01-01 08:09:10.001 UTC to micros since the epoch
    1580544550000000L // 2020-02-01 08:09:10 UTC to micros since the epoch
  )

  def row1: TestRow = TestRow(
    1,
    1633075760000000L, // 2021-10-01 08:09:20 UTC to micros since the epoch
    915181200000000L // 1999-01-01 09:00:00 UTC to micros since the epoch
  )

  def row2: TestRow = TestRow(
    2,
    1633075760000000L, // 2021-10-01 08:09:20 UTC to micros since the epoch
    946717200000000L // 2000-01-01 09:00:00 UTC to micros since the epoch
  )

  def row3: TestRow = TestRow(
    3,
    -31536000000000L, // 1969-01-01 00:00:00  UTC to micros since the epoch
    -31536000000000L // 1969-01-01 00:00:00 UTC to micros since the epoch
  )

  def row4: TestRow = TestRow(
    4,
    null,
    null
  )

  def utcTableExpectedResult: Seq[TestRow] = Seq(row0, row1, row2, row3, row4)

  def testTimestampTable(
    goldenTableName: String,
    timeZone: String,
    expectedResult: Seq[TestRow]): Unit = {
    withTimeZone(timeZone) {
      checkTable(
        path = goldenTablePath(goldenTableName),
        expectedAnswer = expectedResult
      )
    }
  }

  for (timestampType <- Seq("INT96", "TIMESTAMP_MICROS", "TIMESTAMP_MILLIS")) {
    for (timeZone <- Seq("UTC", "Iceland", "PST", "America/Los_Angeles")) {
      test(
        s"end-to-end usage: timestamp table parquet timestamp format $timestampType tz $timeZone") {
        testTimestampTable("kernel-timestamp-" + timestampType, timeZone, utcTableExpectedResult)
      }
    }
  }

  // PST table - all the "time" col timestamps are + 8 hours
  def pstTableExpectedResult: Seq[TestRow] = utcTableExpectedResult.map { testRow =>
    val values = testRow.toSeq
    TestRow(
      values(0),
      // Partition columns are written as the local date time without timezone information and then
      // interpreted by Kernel in UTC --> so the written partition value (& the read value) is the
      // same as the UTC table
      values(1),
      if (values(2) == null) {
        null
      } else {
        values(2).asInstanceOf[Long] + DefaultKernelUtils.DateTimeConstants.MICROS_PER_HOUR * 8
      }
    )
  }

  for (timeZone <- Seq("UTC", "Iceland", "PST", "America/Los_Angeles")) {
    test(s"end-to-end usage: timestamp in written in PST read in $timeZone") {
      testTimestampTable("kernel-timestamp-PST", timeZone, pstTableExpectedResult)
    }
  }

  //////////////////////////////////////////////////////////////////////////////////
  // Decimal type tests
  //////////////////////////////////////////////////////////////////////////////////

  for (tablePath <- Seq("basic-decimal-table", "basic-decimal-table-legacy")) {
    test(s"end to end: reading $tablePath") {
      val expectedResult = Seq(
        ("234.00000", "1.00", "2.00000", "3.0000000000"),
        ("2342222.23454", "111.11", "22222.22222", "3333333333.3333333333"),
        ("0.00004", "0.00", "0.00000", "0E-10"),
        ("-2342342.23423", "-999.99", "-99999.99999", "-9999999999.9999999999")
      ).map { tup =>
        (new BigDecimal(tup._1), new BigDecimal(tup._2), new BigDecimal(tup._3),
          new BigDecimal(tup._4))
      }

      checkTable(
        path = goldenTablePath(tablePath),
        expectedAnswer = expectedResult.map(TestRow.fromTuple(_))
      )
    }
  }

  //////////////////////////////////////////////////////////////////////////////////
  // Table/Snapshot tests
  //////////////////////////////////////////////////////////////////////////////////

  test("invalid path") {
    val invalidPath = "/path/to/non-existent-directory"
    val ex = intercept[TableNotFoundException] {
      Table.forPath(defaultTableClient, invalidPath)
    }
    assert(ex.getMessage().contains(s"Delta table at path `$invalidPath` is not found"))
  }

  test("table deleted after the `Table` creation") {
    withTempDir { temp =>
      val source = new File(goldenTablePath("data-reader-primitives"))
      val target = new File(temp.getCanonicalPath)
      FileUtils.copyDirectory(source, target)

      val table = Table.forPath(defaultTableClient, target.getCanonicalPath)
      // delete the table and try to get the snapshot. Expect a failure.
      FileUtils.deleteDirectory(target)
      val ex = intercept[TableNotFoundException] {
        table.getLatestSnapshot(defaultTableClient)
      }
      assert(ex.getMessage.contains(
        s"Delta table at path `file:${target.getCanonicalPath}` is not found"))
    }
  }

  // TODO for the below, when should we throw an exception? #2253
  //   - on Table creation?
  //   - on Snapshot creation?

  test("empty _delta_log folder") {
    withTempDir { dir =>
      new File(dir, "_delta_log").mkdirs()
      intercept[TableNotFoundException] {
        latestSnapshot(dir.getAbsolutePath)
      }
    }
  }

  test("empty folder with no _delta_log dir") {
    withTempDir { dir =>
      intercept[TableNotFoundException] {
        latestSnapshot(dir.getAbsolutePath)
      }
    }
  }

  test("non-empty folder not a delta table") {
    intercept[TableNotFoundException] {
      latestSnapshot(goldenTablePath("no-delta-log-folder"))
    }
  }

  //////////////////////////////////////////////////////////////////////////////////
  // Misc tests
  //////////////////////////////////////////////////////////////////////////////////

  test("end to end: multi-part checkpoint") {
    checkTable(
      path = goldenTablePath("multi-part-checkpoint"),
      expectedAnswer = (Seq(0L) ++ (0L until 30L)).map(TestRow(_))
    )
  }

  test("read partitioned table") {
    val path = "file:" + goldenTablePath("data-reader-partition-values")

    // for now we don't support timestamp type partition columns so remove from read columns
    val readCols = Table.forPath(defaultTableClient, path).getLatestSnapshot(defaultTableClient)
      .getSchema(defaultTableClient)
      .withoutField("as_timestamp")
      .fields()
      .asScala
      .map(_.getName)

    val expectedAnswer = Seq(0, 1).map { i =>
      TestRow(
        i,
        i.toLong,
        i.toByte,
        i.toShort,
        i % 2 == 0,
        i.toFloat,
        i.toDouble,
        i.toString,
        "null",
        daysSinceEpoch(Date.valueOf("2021-09-08")),
        new BigDecimal(i),
        Seq(TestRow(i), TestRow(i), TestRow(i)),
        TestRow(i.toString, i.toString, TestRow(i, i.toLong)),
        i.toString
      )
    } ++ (TestRow(
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      Seq(TestRow(2), TestRow(2), TestRow(2)),
      TestRow("2", "2", TestRow(2, 2L)),
      "2"
    ) :: Nil)

    checkTable(
      path = path,
      expectedAnswer = expectedAnswer,
      readCols = readCols
    )
  }

  test("table with complex array types") {
    val path = "file:" + goldenTablePath("data-reader-array-complex-objects")

    val expectedAnswer = (0 until 10).map { i =>
      TestRow(
        i,
        Seq(Seq(Seq(i, i, i), Seq(i, i, i)), Seq(Seq(i, i, i), Seq(i, i, i))),
        Seq(
          Seq(Seq(Seq(i, i, i), Seq(i, i, i)), Seq(Seq(i, i, i), Seq(i, i, i))),
          Seq(Seq(Seq(i, i, i), Seq(i, i, i)), Seq(Seq(i, i, i), Seq(i, i, i)))
        ),
        Seq(
          Map[String, Long](i.toString -> i.toLong),
          Map[String, Long](i.toString -> i.toLong)
        ),
        Seq(TestRow(i), TestRow(i), TestRow(i))
      )
    }

    checkTable(
      path = path,
      expectedAnswer = expectedAnswer
    )
  }

  Seq("name", "id").foreach { columnMappingMode =>
    test(s"table with `$columnMappingMode` column mapping mode") {
      val path = goldenTablePath(s"table-with-columnmapping-mode-$columnMappingMode")

      val expectedAnswer = (0 until 5).map { i =>
        TestRow(
          i.byteValue(),
          i.shortValue(),
          i,
          i.longValue(),
          i.floatValue(),
          i.doubleValue(),
          new java.math.BigDecimal(i),
          i % 2 == 0,
          i.toString,
          i.toString.getBytes,
          daysSinceEpoch(Date.valueOf("2021-11-18")), // date in days
          (i * 1000).longValue(), // timestamp in micros
          TestRow(i.toString, TestRow(i)), // nested_struct
          Seq(i, i + 1), // array_of_prims
          Seq(Seq(i, i + 1), Seq(i + 2, i + 3)), // array_of_arrays
          Seq(TestRow(i.longValue()), null), // array_of_structs
          Map(
            i -> (i + 1).longValue(),
            (i + 2) -> (i + 3).longValue()
          ), // map_of_prims
          Map(i + 1 -> TestRow((i * 20).longValue())), // map_of_rows
          {
            val val1 = Seq(i, null, i + 1)
            val val2 = Seq[Integer]()
            Map(
              i.longValue() -> val1,
              (i + 1).longValue() -> val2
            ) // map_of_arrays
          }
        )
      } ++ (TestRow(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      ) :: Nil)

      checkTable(
        path = path,
        expectedAnswer = expectedAnswer
      )
    }
  }

  Seq("name", "id").foreach { columnMappingMode =>
    test(s"table with `$columnMappingMode` column mapping mode - read subset of columns") {
      val path = goldenTablePath(s"table-with-columnmapping-mode-$columnMappingMode")

      val expectedAnswer = (0 until 5).map { i =>
        TestRow(
          i.byteValue(),
          new java.math.BigDecimal(i),
          TestRow(i.toString, TestRow(i)), // nested_struct
          Seq(i, i + 1), // array_of_prims
          Map(
            i -> (i + 1).longValue(),
            (i + 2) -> (i + 3).longValue()
          ) // map_of_prims
        )
      } ++ (TestRow(
        null,
        null,
        null,
        null,
        null
      ) :: Nil)

      checkTable(
        path = path,
        expectedAnswer = expectedAnswer,
        readCols = Seq("ByteType", "decimal", "nested_struct", "array_of_prims", "map_of_prims")
      )
    }
  }

  test("table with complex map types") {
    val path = "file:" + goldenTablePath("data-reader-map")

    val expectedAnswer = (0 until 10).map { i =>
      TestRow(
        i,
        Map(i -> i),
        Map(i.toLong -> i.toByte),
        Map(i.toShort -> (i % 2 == 0)),
        Map(i.toFloat -> i.toDouble),
        Map(i.toString -> new BigDecimal(i)),
        Map(i -> Seq(TestRow(i), TestRow(i), TestRow(i)))
      )
    }

    checkTable(
      path = path,
      expectedAnswer = expectedAnswer
    )
  }

  test("table with array of primitives") {
    val expectedAnswer = (0 until 10).map { i =>
      TestRow(
        Seq(i), Seq(i.toLong), Seq(i.toByte), Seq(i.toShort),
        Seq(i % 2 == 0), Seq(i.toFloat), Seq(i.toDouble), Seq(i.toString),
        Seq(Array(i.toByte, i.toByte)), Seq(new BigDecimal(i))
      )
    }
    checkTable(
      path = goldenTablePath("data-reader-array-primitives"),
      expectedAnswer = expectedAnswer
    )
  }

  test("table with nested struct") {
    val expectedAnswer = (0 until 10).map { i =>
      TestRow(TestRow(i.toString, i.toString, TestRow(i, i.toLong)), i)
    }
    checkTable(
      path = goldenTablePath("data-reader-nested-struct"),
      expectedAnswer = expectedAnswer
    )
  }

  test("table with empty parquet files") {
    checkTable(
      path = goldenTablePath("125-iterator-bug"),
      expectedAnswer = (1 to 5).map(TestRow(_))
    )
  }

  test("handle corrupted '_last_checkpoint' file") {
    checkTable(
      path = goldenTablePath("corrupted-last-checkpoint-kernel"),
      expectedAnswer = (0L until 100L).map(TestRow(_))
    )
  }

  test("error - version not contiguous") {
    val e = intercept[IllegalStateException] {
      latestSnapshot(goldenTablePath("versions-not-contiguous"))
    }
    assert(e.getMessage.contains("Versions ([0, 2]) are not continuous"))
  }

  test("table protocol version greater than reader protocol version") {
    val e = intercept[Exception] {
      latestSnapshot(goldenTablePath("deltalog-invalid-protocol-version"))
        .getScanBuilder(defaultTableClient)
        .build()
    }
    assert(e.getMessage.contains("Unsupported reader protocol version"))
  }
}
