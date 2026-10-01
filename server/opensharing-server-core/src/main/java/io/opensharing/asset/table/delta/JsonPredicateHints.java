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
 * effort: an oversized, malformed, or unsupported hint returns no filter, so all files are returned.
 */
final class JsonPredicateHints {

  static final int MAX_SIZE = 1024 * 1024;
  static final int MAX_DEPTH = 100;

  private static final ObjectMapper JSON = new ObjectMapper();

  private JsonPredicateHints() {}

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
      return Optional.empty();
    }
  }

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

  private static Predicate comparison(String name, JsonNode node, StructType schema) {
    List<JsonNode> children = children(node, 2);
    if (!text(children.get(0), "valueType").equals(text(children.get(1), "valueType"))) {
      throw new IllegalArgumentException("type mismatch in " + node);
    }
    return new Predicate(name, leaf(children.get(0), schema), leaf(children.get(1), schema));
  }

  private static Expression leaf(JsonNode node, StructType schema) {
    String op = text(node, "op");
    DataType type = type(text(node, "valueType"));
    if (op.equals("column")) {
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
      return Literal.ofDate(Math.toIntExact(LocalDate.parse(value).toEpochDay()));
    }
    if (type instanceof FloatType) {
      return Literal.ofFloat(Float.parseFloat(value));
    }
    if (type instanceof DoubleType) {
      return Literal.ofDouble(Double.parseDouble(value));
    }
    Instant instant =
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    return Literal.ofTimestamp(ChronoUnit.MICROS.between(Instant.EPOCH, instant));
  }

  // A bool column or literal may stand alone as a predicate.
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

  // expected < 0 means "at least two", for and/or.
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

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("missing " + field + " in " + node);
    }
    return value.asText();
  }

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
