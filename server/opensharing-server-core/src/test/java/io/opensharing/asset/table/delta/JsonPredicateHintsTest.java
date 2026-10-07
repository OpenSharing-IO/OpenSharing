package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JsonPredicateHintsTest {

  private static final StructType SCHEMA =
      new StructType()
          .add("active", BooleanType.BOOLEAN)
          .add("age", IntegerType.INTEGER)
          .add("id", LongType.LONG)
          .add("name", StringType.STRING)
          .add("hireDate", DateType.DATE)
          .add("score", FloatType.FLOAT)
          .add("salary", DoubleType.DOUBLE)
          .add("updatedAt", TimestampType.TIMESTAMP);

  private static final String ID = column("id", "long");

  @ParameterizedTest
  @CsvSource({
    "equal, =",
    "lessThan, <",
    "lessThanOrEqual, <=",
    "greaterThan, >",
    "greaterThanOrEqual, >="
  })
  void convertsEachComparison(String op, String kernelOp) {
    assertConverts("(column(`id`) " + kernelOp + " 10)", op(op, ID, literal("10", "long")));
  }

  @ParameterizedTest
  @CsvSource({
    "active, bool, true, true",
    "age, int, 42, 42",
    "id, long, 9000000000, 9000000000",
    "name, string, Alice, Alice",
    // Days since the epoch.
    "hireDate, date, 2021-04-29, 18746",
    "score, float, 1.5, 1.5",
    "salary, double, 2.25, 2.25",
    // Microseconds since the epoch; the offset is applied.
    "updatedAt, timestamp, 2021-04-29T01:00:00+01:00, 1619654400000000"
  })
  void parsesEachLiteralType(String column, String type, String value, String kernelValue) {
    assertConverts(
        "(column(`" + column + "`) = " + kernelValue + ")",
        op("equal", column(column, type), literal(value, type)));
  }

  @Test
  void convertsIsNullAndNot() {
    assertConverts(
        "NOT(IS_NULL(column(`name`)))", op("not", op("isNull", column("name", "string"))));
  }

  @Test
  void foldsAndFromTheLeft() {
    assertConverts(
        "(((column(`id`) >= 10) AND (column(`id`) < 20)) AND (column(`age`) = 30))",
        op(
            "and",
            op("greaterThanOrEqual", ID, literal("10", "long")),
            op("lessThan", ID, literal("20", "long")),
            op("equal", column("age", "int"), literal("30", "int"))));
  }

  @Test
  void foldsOrFromTheLeft() {
    assertConverts(
        "(((column(`id`) = 1) OR (column(`id`) = 2)) OR (column(`id`) = 3))",
        op(
            "or",
            op("equal", ID, literal("1", "long")),
            op("equal", ID, literal("2", "long")),
            op("equal", ID, literal("3", "long"))));
  }

  @Test
  void treatsABoolColumnAsColumnEqualsTrue() {
    assertConverts("(column(`active`) = true)", column("active", "bool"));
    assertConverts("NOT((column(`active`) = true))", op("not", column("active", "bool")));
    assertConverts(
        "((column(`active`) = true) AND (column(`id`) = 1))",
        op("and", column("active", "bool"), op("equal", ID, literal("1", "long"))));
  }

  @Test
  void treatsABoolLiteralAsAlwaysTrueOrAlwaysFalse() {
    assertConverts("ALWAYS_TRUE()", literal("true", "bool"));
    assertConverts("ALWAYS_FALSE()", literal("FALSE", "bool"));
  }

  @Test
  void skipsEmptyOrOversizedInput() {
    assertSkipped(null);
    assertSkipped(" ");
    assertSkipped("not json");
    // Valid JSON, but past the size limit.
    assertSkipped(literal("true", "bool") + " ".repeat(JsonPredicateHints.MAX_SIZE));
  }

  @Test
  void skipsUnsupportedOpsAndTypes() {
    assertSkipped(op("like", ID, ID));
    assertSkipped(op("equal", column("id", "binary"), literal("1", "binary")));
    // op must be a string.
    assertSkipped("{\"op\":1}");
    // A column of a supported type, but without op.
    assertSkipped("{\"name\":\"id\",\"valueType\":\"long\"}");
  }

  @Test
  void skipsColumnsThatDoNotMatchTheSchema() {
    assertSkipped(op("equal", column("missing", "long"), literal("1", "long")));
    // id is a long in the schema.
    assertSkipped(op("equal", column("id", "int"), literal("1", "int")));
  }

  @Test
  void skipsComparisonsWhoseSidesDiffer() {
    assertSkipped(op("equal", ID, literal("1", "string")));
    assertSkipped(op("lessThan", ID, op("isNull", ID)));
  }

  @Test
  void skipsUnparsableLiterals() {
    assertSkipped(op("equal", ID, literal("x", "long")));
    assertSkipped(op("equal", column("active", "bool"), literal("yes", "bool")));
    assertSkipped(op("equal", column("hireDate", "date"), literal("04/29/2021", "date")));
    // Timestamps need an offset.
    assertSkipped(
        op("equal", column("updatedAt", "timestamp"), literal("2021-04-29T01:00:00", "timestamp")));
  }

  @Test
  void skipsWrongChildCounts() {
    assertSkipped("{\"op\":\"not\"}");
    assertSkipped(op("equal", ID));
    assertSkipped(op("isNull", ID, ID));
    assertSkipped(op("and", literal("true", "bool")));
    assertSkipped(op("or", literal("true", "bool")));
  }

  @Test
  void skipsNonBooleanConditions() {
    assertSkipped(ID);
    assertSkipped(op("not", ID));
    assertSkipped(op("and", ID, literal("true", "bool")));
  }

  @Test
  void skipsHintsOverTheDepthLimit() {
    String deep = literal("true", "bool");
    for (int depth = 1; depth < JsonPredicateHints.MAX_DEPTH; depth++) {
      deep = op("not", deep);
    }
    assertTrue(JsonPredicateHints.toPredicate(deep, SCHEMA).isPresent());
    assertSkipped(op("not", deep));
  }

  private static String column(String name, String valueType) {
    return "{\"op\":\"column\",\"name\":\"" + name + "\",\"valueType\":\"" + valueType + "\"}";
  }

  private static String literal(String value, String valueType) {
    return "{\"op\":\"literal\",\"value\":\"" + value + "\",\"valueType\":\"" + valueType + "\"}";
  }

  private static String op(String op, String... children) {
    return "{\"op\":\"" + op + "\",\"children\":[" + String.join(",", children) + "]}";
  }

  private static void assertConverts(String expected, String json) {
    Optional<Predicate> predicate = JsonPredicateHints.toPredicate(json, SCHEMA);
    assertTrue(predicate.isPresent(), json);
    assertEquals(expected, predicate.get().toString(), json);
  }

  private static void assertSkipped(String json) {
    assertEquals(Optional.empty(), JsonPredicateHints.toPredicate(json, SCHEMA), json);
  }
}
