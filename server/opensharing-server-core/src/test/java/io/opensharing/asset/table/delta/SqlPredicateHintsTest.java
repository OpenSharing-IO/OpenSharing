package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlPredicateHintsTest {

  private static final StructType SCHEMA =
      new StructType()
          .add("id", LongType.LONG)
          .add("date", DateType.DATE)
          .add("region", StringType.STRING)
          .add("bucket", IntegerType.INTEGER);
  private static final Set<String> PARTITIONS = Set.of("date", "region", "bucket");

  @Test
  void convertsSparkFilterSqlOnPartitionColumns() {
    assertEquals("(column(`date`) = 18628)", convert("(date = DATE '2021-01-01')"));
    assertEquals("(column(`date`) >= 18628)", convert("`date` >= '2021-01-01'"));
    assertEquals("(column(`region`) = it's)", convert("(REGION = 'it''s')"));
    assertEquals("(3 < column(`bucket`))", convert("(3 < bucket)"));
    assertEquals("(column(`bucket`) <= 3)", convert("(CAST(bucket AS INT) <= 3)"));
    assertEquals("IS NOT DISTINCT FROM(column(`bucket`), 3)", convert("(bucket <=> 3)"));
    assertEquals("NOT((column(`bucket`) = 3))", convert("(bucket <> 3)"));
    assertEquals("NOT((column(`bucket`) > 3))", convert("(NOT (bucket > 3))"));
    assertEquals("IS_NULL(column(`region`))", convert("(region IS NULL)"));
    assertEquals("IS_NOT_NULL(column(`region`))", convert("(region IS NOT NULL)"));
  }

  @Test
  void andsSupportedHintsAndDropsTheRest() {
    assertEquals(
        "((column(`date`) = 18628) AND (column(`bucket`) > 1))",
        SqlPredicateHints.toPredicate(
                List.of(
                    "(date = DATE '2021-01-01')",
                    // Not a partition column.
                    "(id > 5L)",
                    // AND, OR, and functions are outside the supported subset.
                    "((bucket = 1) AND (bucket = 2))",
                    "((bucket = 1) OR (bucket = 2))",
                    "(lower(region) = 'us')",
                    // CAST that changes the column type, NULL comparison, and malformed SQL.
                    "(CAST(bucket AS STRING) = '1')",
                    "(bucket = NULL)",
                    "(bucket = ",
                    "(bucket > 1)"),
                SCHEMA,
                PARTITIONS)
            .orElseThrow()
            .toString());
    assertEquals(
        Optional.empty(), SqlPredicateHints.toPredicate(List.of("(id > 5L)"), SCHEMA, PARTITIONS));
    assertEquals(
        Optional.empty(),
        SqlPredicateHints.toPredicate(List.of("(date = DATE '2021-01-01')"), SCHEMA, Set.of()));
  }

  private static String convert(String hint) {
    Optional<Predicate> predicate =
        SqlPredicateHints.toPredicate(List.of(hint), SCHEMA, PARTITIONS);
    return predicate.map(Predicate::toString).orElse("dropped: " + hint);
  }
}
