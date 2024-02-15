/*
 * Copyright (2023) The Delta Lake Project Authors.
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
package io.delta.kernel.defaults.internal.expressions

import java.lang.{Boolean => BooleanJ}
import java.math.{BigDecimal => BigDecimalJ}
import java.sql.{Date, Timestamp}
import java.util
import java.util.Optional

import io.delta.kernel.data.{ColumnarBatch, ColumnVector}
import io.delta.kernel.defaults.internal.data.DefaultColumnarBatch
import io.delta.kernel.defaults.internal.data.vector.{DefaultIntVector, DefaultStructVector}
import io.delta.kernel.defaults.utils.DefaultKernelTestUtils.getValueAsObject
import io.delta.kernel.expressions._
import io.delta.kernel.expressions.AlwaysFalse.ALWAYS_FALSE
import io.delta.kernel.expressions.AlwaysTrue.ALWAYS_TRUE
import io.delta.kernel.expressions.Literal._
import io.delta.kernel.internal.util.InternalUtils
import io.delta.kernel.types._
import org.scalatest.funsuite.AnyFunSuite

class DefaultExpressionEvaluatorSuite extends AnyFunSuite with ExpressionSuiteBase {
  test("evaluate expression: literal") {
    val testLiterals = Seq(
      Literal.ofBoolean(true),
      Literal.ofBoolean(false),
      Literal.ofNull(BooleanType.BOOLEAN),
      ofByte(24.toByte),
      Literal.ofNull(ByteType.BYTE),
      Literal.ofShort(876.toShort),
      Literal.ofNull(ShortType.SHORT),
      Literal.ofInt(2342342),
      Literal.ofNull(IntegerType.INTEGER),
      Literal.ofLong(234234223L),
      Literal.ofNull(LongType.LONG),
      Literal.ofFloat(23423.4223f),
      Literal.ofNull(FloatType.FLOAT),
      Literal.ofDouble(23423.422233d),
      Literal.ofNull(DoubleType.DOUBLE),
      Literal.ofString("string_val"),
      Literal.ofNull(StringType.STRING),
      Literal.ofBinary("binary_val".getBytes),
      Literal.ofNull(BinaryType.BINARY),
      Literal.ofDate(4234),
      Literal.ofNull(DateType.DATE),
      Literal.ofTimestamp(2342342342232L),
      Literal.ofNull(TimestampType.TIMESTAMP))

    val inputBatches: Seq[ColumnarBatch] = Seq[ColumnarBatch](
      zeroColumnBatch(rowCount = 0),
      zeroColumnBatch(rowCount = 25),
      zeroColumnBatch(rowCount = 128))

    for (literal <- testLiterals) {
      val outputDataType = literal.getDataType
      for (inputBatch <- inputBatches) {
        val outputVector: ColumnVector =
          evaluator(inputBatch.getSchema, literal, literal.getDataType)
            .eval(inputBatch)

        assert(inputBatch.getSize === outputVector.getSize)
        assert(outputDataType === outputVector.getDataType)

        for (rowId <- 0 until outputVector.getSize) {
          if (literal.getValue == null) {
            assert(
              outputVector.isNullAt(rowId),
              s"expected a null at $rowId for $literal expression")
          } else {
            assert(
              literal.getValue === getValueAsObject(outputVector, rowId),
              s"invalid value at $rowId for $literal expression"
            )
          }
        }
      }
    }
  }

  SIMPLE_TYPES.foreach { dataType =>
    test(s"evaluate expression: column of type $dataType") {
      val batchSize = 78;
      val batchSchema = new StructType().add("col1", dataType)
      val batch = new DefaultColumnarBatch(
        batchSize,
        batchSchema,
        Array[ColumnVector](testColumnVector(batchSize, dataType)))

      val outputVector = evaluator(batchSchema, new Column("col1"), dataType)
        .eval(batch)

      assert(batchSize === outputVector.getSize)
      assert(dataType === outputVector.getDataType)
      Seq.range(0, outputVector.getSize).foreach { rowId =>
        assert(
          testIsNullValue(dataType, rowId) === outputVector.isNullAt(rowId),
          s"unexpected nullability at $rowId for $dataType type vector")
        if (!outputVector.isNullAt(rowId)) {
          assert(
            testColumnValue(dataType, rowId) === getValueAsObject(outputVector, rowId),
            s"unexpected value at $rowId for $dataType type vector")
        }
      }
    }
  }

  test("evaluate expression: nested column reference") {
    val col3Type = IntegerType.INTEGER
    val col2Type = new StructType().add("col3", col3Type)
    val col1Type = new StructType().add("col2", col2Type)
    val batchSchema = new StructType().add("col1", col1Type)

    val numRows = 5
    val col3Nullability = Seq(false, true, false, true, false).toArray
    val col3Values = Seq(27, 24, 29, 100, 125).toArray
    val col3Vector =
      new DefaultIntVector(col3Type, numRows, Optional.of(col3Nullability), col3Values)

    val col2Nullability = Seq(false, true, true, true, false).toArray
    val col2Vector =
      new DefaultStructVector(numRows, col2Type, Optional.of(col2Nullability), Array(col3Vector))

    val col1Nullability = Seq(false, false, false, true, false).toArray
    val col1Vector =
      new DefaultStructVector(numRows, col1Type, Optional.of(col1Nullability), Array(col2Vector))

    val batch = new DefaultColumnarBatch(numRows, batchSchema, Array(col1Vector))

    def assertTypeAndNullability(
      actVector: ColumnVector, expType: DataType, expNullability: Array[Boolean]): Unit = {
      assert(actVector.getDataType === expType)
      assert(actVector.getSize === numRows)
      Seq.range(0, numRows).foreach { rowId =>
        assert(actVector.isNullAt(rowId) === expNullability(rowId))
      }
    }

    val col3Ref = new Column(Array("col1", "col2", "col3"))
    val col3RefResult = evaluator(batchSchema, col3Ref, col3Type).eval(batch)
    assertTypeAndNullability(col3RefResult, col3Type, col3Nullability);
    Seq.range(0, numRows).foreach { rowId =>
      assert(col3RefResult.getInt(rowId) === col3Values(rowId))
    }

    val col2Ref = new Column(Array("col1", "col2"))
    val col2RefResult = evaluator(batchSchema, col2Ref, col2Type).eval(batch)
    assertTypeAndNullability(col2RefResult, col2Type, col2Nullability)

    val col1Ref = new Column(Array("col1"))
    val col1RefResult = evaluator(batchSchema, col1Ref, col1Type).eval(batch)
    assertTypeAndNullability(col1RefResult, col1Type, col1Nullability)

    // try to reference non-existent nested column
    val colNotValid = new Column(Array("col1", "colX`X"))
    val ex = intercept[IllegalArgumentException] {
      evaluator(batchSchema, colNotValid, col1Type).eval(batch)
    }
    assert(ex.getMessage.contains("column(`col1`.`colX``X`) doesn't exist in input data schema"))
  }

  test("evaluate expression: always true, always false") {
    Seq(ALWAYS_TRUE, ALWAYS_FALSE).foreach { expr =>
      val batch = zeroColumnBatch(rowCount = 87)
      val outputVector = evaluator(batch.getSchema, expr, BooleanType.BOOLEAN).eval(batch)
      assert(outputVector.getSize === 87)
      assert(outputVector.getDataType === BooleanType.BOOLEAN)
      Seq.range(0, 87).foreach { rowId =>
        assert(!outputVector.isNullAt(rowId))
        assert(outputVector.getBoolean(rowId) == (expr == ALWAYS_TRUE))
      }
    }
  }

  test("evaluate expression: and, or") {
    val leftColumn = booleanVector(
      Seq[BooleanJ](true, true, false, false, null, true, null, false, null))
    val rightColumn = booleanVector(
      Seq[BooleanJ](true, false, false, true, true, null, false, null, null))
    val expAndOutputVector = booleanVector(
      Seq[BooleanJ](true, false, false, false, null, null, false, false, null))
    val expOrOutputVector = booleanVector(
      Seq[BooleanJ](true, true, false, true, true, true, null, null, null))

    val schema = new StructType()
      .add("left", BooleanType.BOOLEAN)
      .add("right", BooleanType.BOOLEAN)
    val batch = new DefaultColumnarBatch(leftColumn.getSize, schema, Array(leftColumn, rightColumn))

    val left = comparator("=", new Column("left"), Literal.ofBoolean(true))
    val right = comparator("=", new Column("right"), Literal.ofBoolean(true))

    // And
    val andExpression = and(left, right)
    val actAndOutputVector = evaluator(schema, andExpression, BooleanType.BOOLEAN).eval(batch)
    checkBooleanVectors(actAndOutputVector, expAndOutputVector)

    // Or
    val orExpression = or(left, right)
    val actOrOutputVector = evaluator(schema, orExpression, BooleanType.BOOLEAN).eval(batch)
    checkBooleanVectors(actOrOutputVector, expOrOutputVector)
  }

  test("evaluate expression: not") {
    val childColumn = booleanVector(Seq[BooleanJ](true, false, null))

    val schema = new StructType().add("child", BooleanType.BOOLEAN)
    val batch = new DefaultColumnarBatch(childColumn.getSize, schema, Array(childColumn))

    val notExpression = new Predicate(
      "NOT",
      comparator("=", new Column("child"), Literal.ofBoolean(true))
    )
    val expOutputVector = booleanVector(Seq[BooleanJ](false, true, null))
    val actOutputVector = evaluator(schema, notExpression, BooleanType.BOOLEAN).eval(batch)
    checkBooleanVectors(actOutputVector, expOutputVector)
  }

  test("evaluate expression: is not null") {
    val childColumn = booleanVector(Seq[BooleanJ](true, false, null))

    val schema = new StructType().add("child", BooleanType.BOOLEAN)
    val batch = new DefaultColumnarBatch(childColumn.getSize, schema, Array(childColumn))

    val isNotNullExpression = new Predicate("IS_NOT_NULL", new Column("child"))
    val expOutputVector = booleanVector(Seq[BooleanJ](true, true, false))
    val actOutputVector = evaluator(schema, isNotNullExpression, BooleanType.BOOLEAN).eval(batch)
    checkBooleanVectors(actOutputVector, expOutputVector)
  }

  test("evaluate expression: is null") {
    val childColumn = booleanVector(Seq[BooleanJ](true, false, null))

    val schema = new StructType().add("child", BooleanType.BOOLEAN)
    val batch = new DefaultColumnarBatch(childColumn.getSize, schema, Array(childColumn))

    val isNullExpression = new Predicate("IS_NULL", new Column("child"))
    val expOutputVector = booleanVector(Seq[BooleanJ](false, false, true))
    val actOutputVector = evaluator(schema, isNullExpression, BooleanType.BOOLEAN).eval(batch)
    checkBooleanVectors(actOutputVector, expOutputVector)
  }

  test("evaluate expression: coalesce") {
    val col1 = booleanVector(Seq[BooleanJ](true, null, null, null))
    val col2 = booleanVector(Seq[BooleanJ](false, false, null, null))
    val col3 = booleanVector(Seq[BooleanJ](true, true, true, null))

    val schema = new StructType()
      .add("col1", BooleanType.BOOLEAN)
      .add("col2", BooleanType.BOOLEAN)
      .add("col3", BooleanType.BOOLEAN)

    val batch = new DefaultColumnarBatch(col1.getSize, schema, Array(col1, col2, col3))

    val coalesceEpxr1 = new ScalarExpression(
      "COALESCE",
      util.Arrays.asList(new Column("col1")))
    val expOutputVector1 = booleanVector(Seq[BooleanJ](true, null, null, null))
    val actOutputVector1 = evaluator(schema, coalesceEpxr1, BooleanType.BOOLEAN).eval(batch)
    checkBooleanVectors(actOutputVector1, expOutputVector1)

    val coalesceEpxr3 = new ScalarExpression(
      "COALESCE",
      util.Arrays.asList(
        new Column("col1"), new Column("col2"), new Column("col3")))
    val expOutputVector3 = booleanVector(Seq[BooleanJ](true, false, true, null))
    val actOutputVector3 = evaluator(schema, coalesceEpxr3, BooleanType.BOOLEAN).eval(batch)
    checkBooleanVectors(actOutputVector3, expOutputVector3)

    def checkUnsupportedTypes(
          col1Type: DataType, col2Type: DataType, messageContains: String): Unit = {
      val schema = new StructType()
        .add("col1", col1Type)
        .add("col2", col2Type)
      val batch = new DefaultColumnarBatch(5, schema,
        Array(testColumnVector(5, col1Type), testColumnVector(5, col2Type)))
      val e = intercept[UnsupportedOperationException] {
        evaluator(
          schema,
          new ScalarExpression("COALESCE",
            util.Arrays.asList(new Column("col1"), new Column("col2"))),
          col1Type
        ).eval(batch)
      }
      assert(e.getMessage.contains(messageContains))
    }
    // TODO support least-common-type resolution
    checkUnsupportedTypes(LongType.LONG, IntegerType.INTEGER,
      "Coalesce is only supported for arguments of the same type")
    // TODO support other types besides boolean
    checkUnsupportedTypes(IntegerType.INTEGER, IntegerType.INTEGER,
      "Coalesce is only supported for boolean type expressions")
  }

  test("evaluate expression: comparators (=, <, <=, >, >=)") {
    // Literals for each data type from the data type value range, used as inputs to comparator
    // (small, big, small, null)
    val literals = Seq(
      (ofByte(1.toByte), ofByte(2.toByte), ofByte(1.toByte), ofNull(ByteType.BYTE)),
      (ofShort(1.toShort), ofShort(2.toShort), ofShort(1.toShort), ofNull(ShortType.SHORT)),
      (ofInt(1), ofInt(2), ofInt(1), ofNull(IntegerType.INTEGER)),
      (ofLong(1L), ofLong(2L), ofLong(1L), ofNull(LongType.LONG)),
      (ofFloat(1.0F), ofFloat(2.0F), ofFloat(1.0F), ofNull(FloatType.FLOAT)),
      (ofDouble(1.0), ofDouble(2.0), ofDouble(1.0), ofNull(DoubleType.DOUBLE)),
      (ofBoolean(false), ofBoolean(true), ofBoolean(false), ofNull(BooleanType.BOOLEAN)),
      (
        ofTimestamp(343L),
        ofTimestamp(123212312L),
        ofTimestamp(343L),
        ofNull(TimestampType.TIMESTAMP)
      ),
      (ofDate(-12123), ofDate(123123), ofDate(-12123), ofNull(DateType.DATE)),
      (ofString("apples"), ofString("oranges"), ofString("apples"), ofNull(StringType.STRING)),
      (
        ofBinary("apples".getBytes()),
        ofBinary("oranges".getBytes()),
        ofBinary("apples".getBytes()),
        ofNull(BinaryType.BINARY)
      ),
      (
        ofDecimal(BigDecimalJ.valueOf(1.12), 7, 3),
        ofDecimal(BigDecimalJ.valueOf(5233.232), 7, 3),
        ofDecimal(BigDecimalJ.valueOf(1.12), 7, 3),
        ofNull(new DecimalType(7, 3))
      )
    )

    // Mapping of comparator to expected results for:
    // comparator(small, big)
    // comparator(big, small)
    // comparator(small, small)
    // comparator(small, null)
    // comparator(big, null)
    // comparator(null, null)
    val comparatorToExpResults = Map[String, Seq[BooleanJ]](
      "<" -> Seq(true, false, false, null, null, null),
      "<=" -> Seq(true, false, true, null, null, null),
      ">" -> Seq(false, true, false, null, null, null),
      ">=" -> Seq(false, true, true, null, null, null),
      "=" -> Seq(false, false, true, null, null, null)
    )

    literals.foreach {
      case (small1, big, small2, nullLit) =>
        comparatorToExpResults.foreach {
          case (comparator, expectedResults) =>
            testComparator(comparator, small1, big, expectedResults(0))
            testComparator(comparator, big, small1, expectedResults(1))
            testComparator(comparator, small1, small2, expectedResults(2))
            testComparator(comparator, small1, nullLit, expectedResults(3))
            testComparator(comparator, nullLit, big, expectedResults(4))
            testComparator(comparator, nullLit, nullLit, expectedResults(5))
        }
    }
  }

  // Literals for each data type from the data type value range, used as inputs to comparator
  // (byte, short, int, float, double)
  val literals = Seq(
    ofByte(1.toByte),
    ofShort(223),
    ofInt(-234),
    ofLong(223L),
    ofFloat(-2423423.9f),
    ofNull(DoubleType.DOUBLE)
  )

  test("evaluate expression: comparators `byte` with other implicit types") {
    // Mapping of comparator to expected results for:
    // (byte, short), (byte, int), (byte, long), (byte, float), (byte, double)
    val comparatorToExpResults = Map[String, Seq[BooleanJ]](
      "<" -> Seq(true, false, true, false, null),
      "<=" -> Seq(true, false, true, false, null),
      ">" -> Seq(false, true, false, true, null),
      ">=" -> Seq(false, true, false, true, null),
      "=" -> Seq(false, false, false, false, null)
    )

    // Left operand is first literal in [[literal]] which a byte type
    // Right operands are the remaining literals to the left side of it in [[literal]]
    val right = literals(0)
    Seq.range(1, literals.length).foreach { idx =>
      comparatorToExpResults.foreach {
        case (comparator, expectedResults) =>
          testComparator(comparator, right, literals(idx), expectedResults(idx - 1))
      }
    }
  }

  test("evaluate expression: comparators `short` with other implicit types") {
    // Mapping of comparator to expected results for:
    // (short, int), (short, long), (short, float), (short, double)
    val comparatorToExpResults = Map[String, Seq[BooleanJ]](
      "<" -> Seq(false, false, false, null),
      "<=" -> Seq(false, true, false, null),
      ">" -> Seq(true, false, true, null),
      ">=" -> Seq(true, true, true, null),
      "=" -> Seq(false, true, false, null)
    )

    // Left operand is first literal in [[literal]] which a short type
    // Right operands are the remaining literals to the left side of it in [[literal]]
    val right = literals(1)
    Seq.range(2, literals.length).foreach { idx =>
      comparatorToExpResults.foreach {
        case (comparator, expectedResults) =>
          testComparator(comparator, right, literals(idx), expectedResults(idx - 2))
      }
    }
  }

  test("evaluate expression: comparators `int` with other implicit types") {
    // Mapping of comparator to expected results for: (int, long), (int, float), (int, double)
    val comparatorToExpResults = Map[String, Seq[BooleanJ]](
      "<" -> Seq(true, false, null),
      "<=" -> Seq(true, false, null),
      ">" -> Seq(false, true, null),
      ">=" -> Seq(false, true, null),
      "=" -> Seq(false, false, null)
    )

    // Left operand is first literal in [[literal]] which a int type
    // Right operands are the remaining literals to the left side of it in [[literal]]
    val right = literals(2)
    Seq.range(3, literals.length).foreach { idx =>
      comparatorToExpResults.foreach {
        case (comparator, expectedResults) =>
          testComparator(comparator, right, literals(idx), expectedResults(idx - 3))
      }
    }
  }

  test("evaluate expression: comparators `long` with other implicit types") {
    // Mapping of comparator to expected results for: (long, float), (long, double)
    val comparatorToExpResults = Map[String, Seq[BooleanJ]](
      "<" -> Seq(false, null),
      "<=" -> Seq(false, null),
      ">" -> Seq(true, null),
      ">=" -> Seq(true, null),
      "=" -> Seq(false, null)
    )

    // Left operand is fourth literal in [[literal]] which a long type
    // Right operands are the remaining literals to the left side of it in [[literal]]
    val right = literals(3)
    Seq.range(4, literals.length).foreach { idx =>
      comparatorToExpResults.foreach {
        case (comparator, expectedResults) =>
          testComparator(comparator, right, literals(idx), expectedResults(idx - 4))
      }
    }
  }

  test("evaluate expression: unsupported implicit casts") {
    intercept[UnsupportedOperationException] {
      testComparator("<", ofInt(21), ofDate(123), null)
    }
  }

  test("evaluate expression: comparators `float` with other implicit types") {
    // Comparator results for: (float, double) is always null as one of the operands is null
    val comparatorToExpResults = Seq("<", "<=", ">", ">=", "=")

    // Left operand is fifth literal in [[literal]] which is a float type
    // Right operands are the remaining literals to the left side of it in [[literal]]
    val right = literals(4)
    Seq.range(5, literals.length).foreach { idx =>
      comparatorToExpResults.foreach { comparator =>
        testComparator(comparator, right, literals(idx), null)
      }
    }
  }

  test("evaluate expression: element_at") {
    val nullStr = null.asInstanceOf[String]
    val testMapValues: Seq[Map[AnyRef, AnyRef]] = Seq(
      Map("k0" -> "v00", "k1" -> "v01", "k3" -> nullStr, nullStr -> "v04"),
      Map("k0" -> "v10", "k1" -> nullStr, "k3" -> "v13", nullStr -> "v14"),
      Map("k0" -> nullStr, "k1" -> "v21", "k3" -> "v23", nullStr -> "v24"),
      null
    )
    val testMapVector = buildMapVector(
      testMapValues,
      new MapType(StringType.STRING, StringType.STRING, true))

    val inputBatch = new DefaultColumnarBatch(
      testMapVector.getSize,
      new StructType().add("partitionValues", testMapVector.getDataType),
      Seq(testMapVector).toArray
    )
    Seq("k0", "k1", "k2", null).foreach { lookupKey =>
      val expOutput = testMapValues.map(map => {
        if (map == null) null
        else map.getOrElse(lookupKey, null)
      })

      val lookupKeyExpr = if (lookupKey == null) {
        Literal.ofNull(StringType.STRING)
      } else {
        Literal.ofString(lookupKey)
      }
      val elementAtExpr = new ScalarExpression(
        "element_at",
        util.Arrays.asList(new Column("partitionValues"), lookupKeyExpr))

      val outputVector = evaluator(inputBatch.getSchema, elementAtExpr, StringType.STRING)
        .eval(inputBatch)
      assert(outputVector.getSize === testMapValues.size)
      assert(outputVector.getDataType === StringType.STRING)
      Seq.range(0, testMapValues.size).foreach { rowId =>
        val expNull = expOutput(rowId) == null
        assert(outputVector.isNullAt(rowId) == expNull)
        if (!expNull) {
          assert(outputVector.getString(rowId) === expOutput(rowId))
        }
      }
    }
  }

  test("evaluate expression: element_at - unsupported map type input") {
    val inputSchema = new StructType()
      .add("as_map", new MapType(IntegerType.INTEGER, BooleanType.BOOLEAN, true))
    val elementAtExpr = new ScalarExpression(
      "element_at",
      util.Arrays.asList(new Column("as_map"), Literal.ofString("empty")))

    val ex = intercept[UnsupportedOperationException] {
      evaluator(inputSchema, elementAtExpr, StringType.STRING)
    }
    assert(ex.getMessage.contains(
      "ELEMENT_AT(column(`as_map`), empty): Supported only on type map(string, string) input data"))
  }

  test("evaluate expression: element_at - unsupported lookup type input") {
    val inputSchema = new StructType()
      .add("as_map", new MapType(StringType.STRING, StringType.STRING, true))
    val elementAtExpr = new ScalarExpression(
      "element_at",
      util.Arrays.asList(new Column("as_map"), Literal.ofShort(24)))

    val ex = intercept[UnsupportedOperationException] {
      evaluator(inputSchema, elementAtExpr, StringType.STRING)
    }
    assert(ex.getMessage.contains("ELEMENT_AT(column(`as_map`), 24): " +
      "lookup key type (short) is different from the map key type (string)"))
  }

  test("evaluate expression: partition_value") {
    // (serialized partition value, partition col type, expected deserialized partition value)
    val testCases = Seq(
      ("true", BooleanType.BOOLEAN, true),
      ("false", BooleanType.BOOLEAN, false),
      (null, BooleanType.BOOLEAN, null),
      ("24", ByteType.BYTE, 24.toByte),
      ("null", ByteType.BYTE, null),
      ("876", ShortType.SHORT, 876.toShort),
      ("null", ShortType.SHORT, null),
      ("2342342", IntegerType.INTEGER, 2342342),
      ("null", IntegerType.INTEGER, null),
      ("234234223", LongType.LONG, 234234223L),
      ("null", LongType.LONG, null),
      ("23423.4223", FloatType.FLOAT, 23423.4223f),
      ("null", FloatType.FLOAT, null),
      ("23423.422233", DoubleType.DOUBLE, 23423.422233d),
      ("null", DoubleType.DOUBLE, null),
      ("234.422233", new DecimalType(10, 6), new BigDecimalJ("234.422233")),
      ("null", DoubleType.DOUBLE, null),
      ("string_val", StringType.STRING, "string_val"),
      ("null", StringType.STRING, null),
      ("binary_val", BinaryType.BINARY, "binary_val".getBytes()),
      ("null", BinaryType.BINARY, null),
      ("2021-11-18", DateType.DATE, InternalUtils.daysSinceEpoch(Date.valueOf("2021-11-18"))),
      ("null", DateType.DATE, null),
      ("2020-02-18 22:00:10", TimestampType.TIMESTAMP,
        InternalUtils.microsSinceEpoch(Timestamp.valueOf("2020-02-18 22:00:10"))),
      ("2020-02-18 00:00:10.023", TimestampType.TIMESTAMP,
        InternalUtils.microsSinceEpoch(Timestamp.valueOf("2020-02-18 00:00:10.023"))),
      ("null", TimestampType.TIMESTAMP, null)
    )

    val inputBatch = zeroColumnBatch(rowCount = 1)
    testCases.foreach { testCase =>
      val (serializedPartVal, partType, deserializedPartVal) = testCase
      val literalSerializedPartVal = if (serializedPartVal == "null") {
        Literal.ofNull(StringType.STRING)
      } else {
        Literal.ofString(serializedPartVal)
      }
      val expr = new PartitionValueExpression(literalSerializedPartVal, partType)
      val outputVector = evaluator(inputBatch.getSchema, expr, partType).eval(inputBatch)
      assert(outputVector.getSize === 1)
      assert(outputVector.getDataType === partType)
      assert(outputVector.isNullAt(0) === (deserializedPartVal == null))
      if (deserializedPartVal != null) {
        assert(getValueAsObject(outputVector, 0) === deserializedPartVal)
      }
    }
  }

  test("evaluate expression: partition_value - invalid serialize value") {
    val inputBatch = zeroColumnBatch(rowCount = 1)
    val (serializedPartVal, partType) = ("23423sdfsdf", IntegerType.INTEGER)
    val expr = new PartitionValueExpression(Literal.ofString(serializedPartVal), partType)
    val ex = intercept[IllegalArgumentException] {
      val outputVector = evaluator(inputBatch.getSchema, expr, partType).eval(inputBatch)
      outputVector.getInt(0)
    }
    assert(ex.getMessage.contains(serializedPartVal))
  }

  private def evaluator(inputSchema: StructType, expression: Expression, outputType: DataType)
  : DefaultExpressionEvaluator = {
    new DefaultExpressionEvaluator(inputSchema, expression, outputType)
  }

  private def testComparator(
    comparator: String, left: Expression, right: Expression, expResult: BooleanJ): Unit = {
    val expression = new Predicate(comparator, left, right)
    val batch = zeroColumnBatch(rowCount = 1)
    val outputVector = evaluator(batch.getSchema, expression, BooleanType.BOOLEAN).eval(batch)

    assert(outputVector.getSize === 1)
    assert(outputVector.getDataType === BooleanType.BOOLEAN)
    assert(
      outputVector.isNullAt(0) === (expResult == null),
      s"Unexpected null value: $comparator($left, $right)")
    if (expResult != null) {
      assert(
        outputVector.getBoolean(0) === expResult,
        s"Unexpected value: $comparator($left, $right)")
    }
  }
}
