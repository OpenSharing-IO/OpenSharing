package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JsonPredicateHintsTest {

  private static final StructType SCHEMA =
      new StructType()
          .add("id", LongType.LONG)
          .add("name", StringType.STRING)
          .add("hireDate", DateType.DATE)
          .add("active", BooleanType.BOOLEAN);

  @Test
  void convertsComparisonsToKernelPredicates() {
    Predicate date =
        convert(
            """
            {"op":"equal","children":[
              {"op":"column","name":"hireDate","valueType":"date"},
              {"op":"literal","value":"2021-04-29","valueType":"date"}]}
            """);
    assertEquals("=", date.getName());
    assertEquals("column(`hireDate`)", date.getChildren().get(0).toString());
    assertEquals("18746", date.getChildren().get(1).toString());

    Predicate range =
        convert(
            """
            {"op":"and","children":[
              {"op":"greaterThanOrEqual","children":[
                {"op":"column","name":"id","valueType":"long"},
                {"op":"literal","value":"10","valueType":"long"}]},
              {"op":"lessThan","children":[
                {"op":"column","name":"id","valueType":"long"},
                {"op":"literal","value":"20","valueType":"long"}]},
              {"op":"not","children":[
                {"op":"isNull","children":[{"op":"column","name":"name","valueType":"string"}]}]}]}
            """);
    assertEquals(
        "(((column(`id`) >= 10) AND (column(`id`) < 20)) AND NOT(IS_NULL(column(`name`))))",
        range.toString());
  }

  @Test
  void treatsBoolLeavesAsPredicates() {
    assertEquals(
        "(column(`active`) = true)",
        convert("{\"op\":\"column\",\"name\":\"active\",\"valueType\":\"bool\"}").toString());
    assertEquals(
        "ALWAYS_FALSE",
        convert("{\"op\":\"literal\",\"value\":\"false\",\"valueType\":\"bool\"}").getName());
  }

  @Test
  void skipsInvalidOrUnsupportedHints() {
    String id = "{\"op\":\"column\",\"name\":\"id\",\"valueType\":\"long\"}";
    assertSkipped(null);
    assertSkipped("not json");
    // Unknown column.
    assertSkipped(
        "{\"op\":\"equal\",\"children\":[{\"op\":\"column\",\"name\":\"missing\",\"valueType\":"
            + "\"long\"},{\"op\":\"literal\",\"value\":\"1\",\"valueType\":\"long\"}]}");
    // Column type differs from the table schema.
    assertSkipped(
        "{\"op\":\"equal\",\"children\":[{\"op\":\"column\",\"name\":\"id\",\"valueType\":\"int\"}"
            + ",{\"op\":\"literal\",\"value\":\"1\",\"valueType\":\"int\"}]}");
    // Children types differ.
    assertSkipped(
        "{\"op\":\"equal\",\"children\":["
            + id
            + ",{\"op\":\"literal\",\"value\":\"1\",\"valueType\":\"string\"}]}");
    // Binary op with one child, and with a non-leaf child.
    assertSkipped("{\"op\":\"equal\",\"children\":[" + id + "]}");
    assertSkipped(
        "{\"op\":\"lessThan\",\"children\":[" + id + ",{\"op\":\"isNull\",\"children\":[" + id
            + "]}]}");
    // and needs at least two children; unknown ops and non-boolean roots are rejected.
    assertSkipped("{\"op\":\"and\",\"children\":[{\"op\":\"literal\",\"value\":\"true\","
        + "\"valueType\":\"bool\"}]}");
    assertSkipped("{\"op\":\"like\",\"children\":[" + id + "," + id + "]}");
    assertSkipped(id);
    // Unparseable literal.
    assertSkipped(
        "{\"op\":\"equal\",\"children\":["
            + id
            + ",{\"op\":\"literal\",\"value\":\"x\",\"valueType\":\"long\"}]}");
  }

  @Test
  void skipsHintsOverTheDepthLimit() {
    String deep = "{\"op\":\"literal\",\"value\":\"true\",\"valueType\":\"bool\"}";
    for (int i = 1; i < JsonPredicateHints.MAX_DEPTH; i++) {
      deep = "{\"op\":\"not\",\"children\":[" + deep + "]}";
    }
    assertTrue(JsonPredicateHints.toPredicate(deep, SCHEMA).isPresent());
    assertSkipped("{\"op\":\"not\",\"children\":[" + deep + "]}");
  }

  private static Predicate convert(String json) {
    Optional<Predicate> predicate = JsonPredicateHints.toPredicate(json, SCHEMA);
    assertTrue(predicate.isPresent(), json);
    return predicate.get();
  }

  private static void assertSkipped(String json) {
    assertEquals(Optional.empty(), JsonPredicateHints.toPredicate(json, SCHEMA), json);
  }
}
