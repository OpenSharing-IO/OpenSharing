package io.opensharing.asset.table.delta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.expressions.AlwaysFalse;
import io.delta.kernel.expressions.AlwaysTrue;
import io.delta.kernel.expressions.And;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Expression;
import io.delta.kernel.expressions.Literal;
import io.delta.kernel.expressions.Or;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts Delta Sharing {@code jsonPredicateHints} into a Kernel scan filter. Hints are best
 * effort: an oversized, malformed, or unsupported hint returns no filter, so every file is
 * returned.
 *
 * <p>Kernel uses the filter to prune partitions and to skip files whose min/max stats cannot match.
 * It never drops rows inside a file, so the client must still apply the predicate itself. Returning
 * no filter is therefore always safe; it only costs extra files.
 *
 * <p>A hint is a tree of {@code {"op": ..., "children": [...]}} nodes. Leaves are {@code column}
 * (top-level columns only, by {@code name}) and {@code literal} (a string {@code value}); both
 * carry a {@code valueType}. Supported ops are {@code and}, {@code or}, {@code not}, {@code
 * isNull}, {@code equal}, {@code lessThan}, {@code lessThanOrEqual}, {@code greaterThan} and
 * {@code greaterThanOrEqual}.
 */
final class JsonPredicateHints {

  /** Largest hint accepted, in characters; larger hints are ignored rather than parsed. */
  static final int MAX_SIZE = 1024 * 1024;

  /** Deepest op tree accepted, which also bounds the recursion in {@link #convert}. */
  static final int MAX_DEPTH = 100;

  private static final ObjectMapper JSON = new ObjectMapper();

  private JsonPredicateHints() {}

  /**
   * Returns the Kernel filter for {@code json}, or empty when there is no usable hint.
   *
   * @param json the request's {@code jsonPredicateHints}; may be null or blank
   * @param schema the snapshot schema; columns must exist in it with the hinted type
   */
  static Optional<Predicate> toPredicate(String json, StructType schema) {
    if (json == null || json.isBlank() || json.length() > MAX_SIZE) {
      return Optional.empty();
    }
    try {
      JsonNode root = JSON.readTree(json);
      if (depth(root) > MAX_DEPTH) {
        return Optional.empty();
      }
      return Optional.of(asPredicate(convert(root, schema), schema));
    } catch (Exception unsupported) {
      // Bad JSON, unknown ops or types, schema mismatches and unparsable literals all land here.
      return Optional.empty();
    }
  }

  /** Converts one op node. Throws on anything unsupported, so the whole hint is dropped. */
  private static Expression convert(JsonNode node, StructType schema) {
    String op = text(node, "op");
    return switch (op) {
      case "column", "literal" -> leaf(node, schema);
      case "isNull" -> new Predicate("IS_NULL", leaf(children(node, 1).get(0), schema));
      case "not" ->
          new Predicate("NOT", asPredicate(convert(children(node, 1).get(0), schema), schema));
      case "equal" -> comparison("=", node, schema);
      case "lessThan" -> comparison("<", node, schema);
      case "lessThanOrEqual" -> comparison("<=", node, schema);
      case "greaterThan" -> comparison(">", node, schema);
      case "greaterThanOrEqual" -> comparison(">=", node, schema);
      case "and", "or" -> {
        // Kernel's And and Or are binary, so fold two or more children from the left.
        List<JsonNode> children = children(node, -1);
        Predicate result = asPredicate(convert(children.get(0), schema), schema);
        for (JsonNode child : children.subList(1, children.size())) {
          Predicate next = asPredicate(convert(child, schema), schema);
          result = op.equals("and") ? new And(result, next) : new Or(result, next);
        }
        yield result;
      }
      default -> throw new IllegalArgumentException("unsupported op " + op);
    };
  }

  /** A binary comparison. Both sides must declare the same valueType; nothing is coerced. */
  private static Predicate comparison(String name, JsonNode node, StructType schema) {
    List<JsonNode> children = children(node, 2);
    if (!text(children.get(0), "valueType").equals(text(children.get(1), "valueType"))) {
      throw new IllegalArgumentException("type mismatch in " + node);
    }
    return new Predicate(name, leaf(children.get(0), schema), leaf(children.get(1), schema));
  }

  /** A column or literal leaf, typed by its valueType. */
  private static Expression leaf(JsonNode node, StructType schema) {
    String op = text(node, "op");
    DataType type = type(text(node, "valueType"));
    if (op.equals("column")) {
      // The column must exist with the hinted type; otherwise drop the hint, not prune wrongly.
      String name = text(node, "name");
      if (!schema.fieldNames().contains(name) || !schema.get(name).getDataType().equals(type)) {
        throw new IllegalArgumentException("column " + name + " does not match the schema");
      }
      return new Column(name);
    }
    if (op.equals("literal")) {
      return literal(text(node, "value"), type);
    }
    throw new IllegalArgumentException("expected a column or literal but found " + op);
  }

  /**
   * Parses a literal's string value as {@code type}. Dates are ISO {@code yyyy-MM-dd}; timestamps
   * are ISO-8601 with an offset and become microseconds since the epoch, as Kernel stores them.
   */
  private static Literal literal(String value, DataType type) {
    if (type instanceof BooleanType) {
      if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
        throw new IllegalArgumentException("invalid bool " + value);
      }
      return Literal.ofBoolean(Boolean.parseBoolean(value));
    }
    if (type instanceof IntegerType) {
      return Literal.ofInt(Integer.parseInt(value));
    }
    if (type instanceof LongType) {
      return Literal.ofLong(Long.parseLong(value));
    }
    if (type instanceof StringType) {
      return Literal.ofString(value);
    }
    if (type instanceof DateType) {
      // Kernel dates are days since the epoch.
      return Literal.ofDate(Math.toIntExact(LocalDate.parse(value).toEpochDay()));
    }
    if (type instanceof FloatType) {
      return Literal.ofFloat(Float.parseFloat(value));
    }
    if (type instanceof DoubleType) {
      return Literal.ofDouble(Double.parseDouble(value));
    }
    // type() only yields the types above or TIMESTAMP, so this is a timestamp.
    Instant instant =
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    return Literal.ofTimestamp(ChronoUnit.MICROS.between(Instant.EPOCH, instant));
  }

  /**
   * Kernel only filters on predicates, but a hint may put a bare bool column or literal where a
   * condition goes: at the root or as a child of {@code and}, {@code or} or {@code not}. A bool
   * column {@code c} becomes {@code c = true}, which also drops rows where {@code c} is null. A
   * bool literal becomes {@code AlwaysTrue} or {@code AlwaysFalse}. Any other non-predicate fails,
   * which drops the hint.
   */
  private static Predicate asPredicate(Expression expression, StructType schema) {
    if (expression instanceof Predicate predicate) {
      return predicate;
    }
    if (expression instanceof Column column
        && schema.get(column.getNames()[0]).getDataType() instanceof BooleanType) {
      return new Predicate("=", column, Literal.ofBoolean(true));
    }
    if (expression instanceof Literal literal && literal.getDataType() instanceof BooleanType) {
      return Boolean.TRUE.equals(literal.getValue())
          ? AlwaysTrue.ALWAYS_TRUE
          : AlwaysFalse.ALWAYS_FALSE;
    }
    throw new IllegalArgumentException("not a boolean expression: " + expression);
  }

  /** Maps a protocol valueType to its Kernel type; any other valueType drops the hint. */
  private static DataType type(String valueType) {
    return switch (valueType) {
      case "bool" -> BooleanType.BOOLEAN;
      case "int" -> IntegerType.INTEGER;
      case "long" -> LongType.LONG;
      case "string" -> StringType.STRING;
      case "date" -> DateType.DATE;
      case "float" -> FloatType.FLOAT;
      case "double" -> DoubleType.DOUBLE;
      case "timestamp" -> TimestampType.TIMESTAMP;
      default -> throw new IllegalArgumentException("unsupported valueType " + valueType);
    };
  }

  /** The node's children: exactly {@code expected} of them, or at least two when it is negative. */
  private static List<JsonNode> children(JsonNode node, int expected) {
    JsonNode children = node.get("children");
    if (children == null || !children.isArray()) {
      throw new IllegalArgumentException("missing children in " + node);
    }
    List<JsonNode> list = new ArrayList<>();
    children.forEach(list::add);
    if (expected < 0 ? list.size() < 2 : list.size() != expected) {
      throw new IllegalArgumentException("wrong number of children in " + node);
    }
    return list;
  }

  /** A required string field; numbers and booleans are rejected, as the protocol sends strings. */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("missing " + field + " in " + node);
    }
    return value.asText();
  }

  /** Depth of the op tree, counting a leaf as 1; checked before {@link #convert} recurses. */
  private static int depth(JsonNode node) {
    int deepest = 0;
    JsonNode children = node.get("children");
    if (children != null) {
      for (JsonNode child : children) {
        deepest = Math.max(deepest, depth(child));
      }
    }
    return deepest + 1;
  }
}
