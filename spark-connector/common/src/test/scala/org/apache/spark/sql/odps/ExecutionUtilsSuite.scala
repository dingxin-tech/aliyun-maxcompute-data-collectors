package org.apache.spark.sql.odps

import com.aliyun.odps.`type`.TypeInfoFactory
import org.apache.spark.sql.catalyst.util.quoteIfNeeded
import org.apache.spark.sql.sources._
import org.scalatest.funsuite.AnyFunSuite

class ExecutionUtilsSuite extends AnyFunSuite {

  test("convertToOdpsPredicate should return NO_PREDICATE for an empty filter sequence") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.INT, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(Seq.empty[Filter], nameTypeMap)
    assert(result.toString === "")
  }

  test("convertToOdpsPredicate should convert AlwaysTrue to NO_PREDICATE") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.INT, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(Seq(AlwaysTrue()), nameTypeMap)
    assert(result.toString === "")
  }

  test("convertToOdpsPredicate should convert AlwaysFalse to NO_PREDICATE") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.INT, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(Seq(AlwaysFalse()), nameTypeMap)
    assert(result.toString === "")
  }

  test("convertToOdpsPredicate should convert EqualTo correctly") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.INT, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(Seq(EqualTo("column1", 42)), nameTypeMap)
    assert(result.toString === "`column1` = 42")
  }

  test("convertToOdpsPredicate should convert GreaterThan correctly") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.INT, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(Seq(GreaterThan("column1", 10)), nameTypeMap)
    assert(result.toString === "`column1` > 10")
  }

  test("convertToOdpsPredicate should handle AND conditions correctly") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.INT, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(Seq(And(EqualTo("column1", 42), GreaterThan("column2", 10))), nameTypeMap)
    assert(result.toString === "`column1` = 42 and `column2` > 10")
  }

  test("convertToOdpsPredicate should handle OR conditions correctly") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.INT, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(Seq(Or(EqualTo("column1", 42), LessThan("column2", 10))), nameTypeMap)
    assert(result.toString === "(`column1` = 42 or `column2` < 10)")
  }

  test("convertToOdpsPredicate should combine predicates correctly") {
    val filters = Seq(
      EqualTo("column1", "value"),
      GreaterThan("column2", 10),
      IsNotNull("column3")
    )
    val nameTypeMap = Map("column1" -> TypeInfoFactory.STRING, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(filters, nameTypeMap)
    assert(result.toString === "`column1` = 'value' and `column2` > 10 and `column3` is not null")
  }

  test("convertToOdpsPredicate should handle NOT conditions correctly") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.STRING, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(Seq(Not(IsNull("column1"))), nameTypeMap)
    assert(result.toString === "(not `column1` is null)")
  }


  test("convertToOdpsPredicate should handle multiple IsNull conditions correctly") {
    val filters = Seq(IsNull("column1"), IsNull("column2"))
    val nameTypeMap = Map("column1" -> TypeInfoFactory.STRING, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(filters, nameTypeMap)
    assert(result.toString === "`column1` is null and `column2` is null")
  }

  test("convertToOdpsPredicate cannot convert StringContains") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.STRING, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(Seq(StringContains("column1", "subString")), nameTypeMap)
    assert(result.toString === "")
  }

  test("convertToOdpsPredicate cannot convert StringEndsWith") {
    val nameTypeMap = Map("column1" -> TypeInfoFactory.STRING, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)

    val result = ExecutionUtils.convertToOdpsPredicate(Seq(StringEndsWith("column1", "suffix")), nameTypeMap)
    assert(result.toString === "")
  }

  test("convertToOdpsPredicate should handle deeply nested conditions correctly") {
    val filters = Seq(
      Or(
        And(EqualTo("column1", "value1"), GreaterThan("column2", 10)),
        And(EqualTo("column1", "value2"), LessThan("column3", 5)),
      )
    )
    val nameTypeMap = Map("column1" -> TypeInfoFactory.STRING, "column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(filters, nameTypeMap)
    assert(result.toString === "((`column1` = 'value1' and `column2` > 10) or (`column1` = 'value2' and `column3` < 5))")
  }

  test("convertToOdpsPredicate should handle unknown predicate correctly (known or unknown)") {
    val filters = Seq(
      And(
        Or(EqualTo("column1", "value"), StringStartsWith("column1", "pattern")),
        And(GreaterThan("column2", 10), IsNotNull("column3"))
      ),
      StringStartsWith("column1", "pattern2"),
      Or(GreaterThan("column3", 10), IsNotNull("column4"))
    )
    val nameTypeMap = Map("column2" -> TypeInfoFactory.INT, "column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(filters, nameTypeMap)

    assert(result.toString === "`column2` > 10 and `column3` is not null and (`column3` > 10 or `column4` is not null)")
  }

  test("convertToOdpsPredicate should handle unknown predicate correctly (Not(known and unknown))") {
    val filters = Seq(
      Not(
        And(GreaterThan("column2", 10), StringStartsWith("column1", "str"))
      ),
      StringStartsWith("column1", "pattern2"),
      Or(GreaterThan("column3", 10), IsNotNull("column4"))
    )
    val nameTypeMap = Map("column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(filters, nameTypeMap)

    assert(result.toString === "(`column3` > 10 or `column4` is not null)")
  }

  test("convertToOdpsPredicate should handle string correctly (escape)") {
    val filters = Seq(
      EqualTo("column2", "I'm fine.")
    )
    val nameTypeMap = Map("column2" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(filters, nameTypeMap)
    println(result)

    assert(result.toString === "`column2` = 'I\\'m fine.'")
  }

  test("convertToOdpsPredicate should handle timestamp correctly (fallback to unknown)") {
    val filters = Seq(
      Not(
        And(GreaterThan("column2", 10), StringStartsWith("column1", "str"))
      ),
      StringStartsWith("column1", "pattern2"),
      Or(GreaterThan("column3", 10), IsNotNull("column4"))
    )
    val nameTypeMap = Map("column3" -> TypeInfoFactory.INT, "column4" -> TypeInfoFactory.STRING)
    val result = ExecutionUtils.convertToOdpsPredicate(filters, nameTypeMap)

    assert(result.toString === "(`column3` > 10 or `column4` is not null)")
  }

  test("quoteAttribute with quoteIfNeeded") {
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("")) === "``")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("``")) === "``````")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("`")) === "````")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("ab")) === "`ab`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("a b")) === "`a b`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("a*b")) === "`a*b`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("123")) === "`123`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("1a")) === "`1a`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("`1a`")) === "```1a```")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("`_")) === "```_`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("`_`")) === "```_```")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("你好")) === "`你好`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("你`好")) === "`你``好`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("你``好")) === "`你````好`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("`你好")) === "```你好`")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("你好`")) === "`你好```")
    assert(ExecutionUtils.quoteAttribute(quoteIfNeeded("`你好`")) === "```你好```")
  }

  test("QuoteChines") {
    val greaterThan = GreaterThan("`你好`", 2)
    val nameTypeMap = Map("`你好`" -> TypeInfoFactory.INT)
    val result = ExecutionUtils.convertToOdpsPredicate(Seq(greaterThan), nameTypeMap)
    println(result.toString)
    assert(result.toString == "`你好` > 2")
  }

  test("QuoteSpecialCharacter") {
    val greaterThan = GreaterThan("你`好", 2)
    val nameTypeMap = Map("你`好" -> TypeInfoFactory.INT)
    val result = ExecutionUtils.convertToOdpsPredicate(Seq(greaterThan), nameTypeMap)
    println(result.toString)
    assert(result.toString == "`你``好` > 2")
  }
}

