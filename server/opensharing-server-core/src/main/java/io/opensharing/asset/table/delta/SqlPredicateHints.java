package io.opensharing.asset.table.delta;

import io.delta.kernel.expressions.And;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Expression;
import io.delta.kernel.expressions.Literal;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.ByteType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DecimalType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.ShortType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Converts legacy SQL {@code predicateHints} (Spark {@code Expression.sql} text) into a Kernel
 * partition filter, matching the Delta Sharing server: each hint is one comparison, {@code IS [NOT]
 * NULL}, or {@code NOT} over partition columns and literals. Unsupported hints are dropped; the
 * rest are ANDed.
 */
final class SqlPredicateHints {

  private static final DateTimeFormatter TIMESTAMP =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE)
          .optionalStart()
          .appendLiteral(' ')
          .append(DateTimeFormatter.ISO_LOCAL_TIME)
          .optionalEnd()
          .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
          .toFormatter(Locale.ROOT);

  private SqlPredicateHints() {}

  static Optional<Predicate> toPredicate(
      List<String> hints, StructType schema, Set<String> partitionColumns) {
    if (hints == null || partitionColumns.isEmpty()) {
      return Optional.empty();
    }
    Predicate result = null;
    for (String hint : hints) {
      Predicate predicate;
      try {
        predicate = convert(new Parser(hint).parseHint(), schema, partitionColumns);
      } catch (RuntimeException unsupported) {
        continue;
      }
      result = result == null ? predicate : new And(result, predicate);
    }
    return Optional.ofNullable(result);
  }

  private static Predicate convert(Node node, StructType schema, Set<String> partitions) {
    if (node instanceof Not not) {
      return new Predicate("NOT", convert(not.child(), schema, partitions));
    }
    if (node instanceof IsNull isNull) {
      Column column = column(isNull.child(), schema, partitions).column();
      return new Predicate(isNull.negated() ? "IS_NOT_NULL" : "IS_NULL", column);
    }
    if (node instanceof Comparison comparison) {
      Expression left;
      Expression right;
      if (isColumn(comparison.left())) {
        Typed column = column(comparison.left(), schema, partitions);
        left = column.column();
        right = operand(comparison.right(), column.type(), schema, partitions);
      } else if (isColumn(comparison.right())) {
        Typed column = column(comparison.right(), schema, partitions);
        right = column.column();
        left = operand(comparison.left(), column.type(), schema, partitions);
      } else {
        throw new IllegalArgumentException("comparison has no partition column");
      }
      return switch (comparison.op()) {
        case "=", "==" -> new Predicate("=", left, right);
        case "<", "<=", ">", ">=" -> new Predicate(comparison.op(), left, right);
        case "<=>" -> new Predicate("IS NOT DISTINCT FROM", left, right);
        case "<>", "!=" -> new Predicate("NOT", new Predicate("=", left, right));
        default -> throw new IllegalArgumentException("unsupported operator " + comparison.op());
      };
    }
    throw new IllegalArgumentException("unsupported hint");
  }

  private static boolean isColumn(Node node) {
    return node instanceof Identifier || (node instanceof Cast cast && isColumn(cast.child()));
  }

  // CAST over a column is only accepted when it does not change the column type.
  private static Typed column(Node node, StructType schema, Set<String> partitions) {
    if (node instanceof Cast cast) {
      Typed column = column(cast.child(), schema, partitions);
      if (!column.type().equals(cast.type())) {
        throw new IllegalArgumentException("cast changes the partition column type");
      }
      return column;
    }
    if (!(node instanceof Identifier identifier)) {
      throw new IllegalArgumentException("expected a partition column");
    }
    for (StructField field : schema.fields()) {
      if (field.getName().equalsIgnoreCase(identifier.name())
          && partitions.stream().anyMatch(p -> p.equalsIgnoreCase(field.getName()))) {
        return new Typed(new Column(field.getName()), field.getDataType());
      }
    }
    throw new IllegalArgumentException(identifier.name() + " is not a partition column");
  }

  private static Expression operand(
      Node node, DataType type, StructType schema, Set<String> partitions) {
    if (isColumn(node)) {
      Typed column = column(node, schema, partitions);
      if (!column.type().equals(type)) {
        throw new IllegalArgumentException("compared columns have different types");
      }
      return column.column();
    }
    Node value = node instanceof Cast cast ? cast.child() : node;
    if (!(value instanceof Value literal)) {
      throw new IllegalArgumentException("expected a literal");
    }
    return literal(literal.text(), type);
  }

  // Spark coerces the literal to the partition column type before comparing.
  private static Literal literal(String text, DataType type) {
    if (text == null) {
      throw new IllegalArgumentException("NULL comparisons never match");
    }
    if (type instanceof StringType) {
      return Literal.ofString(text);
    }
    if (type instanceof BooleanType) {
      if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
        throw new IllegalArgumentException("invalid boolean " + text);
      }
      return Literal.ofBoolean(Boolean.parseBoolean(text));
    }
    if (type instanceof ByteType) {
      return Literal.ofByte(Byte.parseByte(text));
    }
    if (type instanceof ShortType) {
      return Literal.ofShort(Short.parseShort(text));
    }
    if (type instanceof IntegerType) {
      return Literal.ofInt(Integer.parseInt(text));
    }
    if (type instanceof LongType) {
      return Literal.ofLong(Long.parseLong(text));
    }
    if (type instanceof FloatType) {
      return Literal.ofFloat(Float.parseFloat(text));
    }
    if (type instanceof DoubleType) {
      return Literal.ofDouble(Double.parseDouble(text));
    }
    if (type instanceof DecimalType decimal) {
      BigDecimal value =
          new BigDecimal(text).setScale(decimal.getScale(), RoundingMode.UNNECESSARY);
      return Literal.ofDecimal(value, decimal.getPrecision(), decimal.getScale());
    }
    if (type instanceof DateType) {
      return Literal.ofDate(Math.toIntExact(LocalDate.parse(text).toEpochDay()));
    }
    if (type instanceof TimestampType) {
      return Literal.ofTimestamp(ChronoUnit.MICROS.between(Instant.EPOCH, timestamp(text)));
    }
    throw new IllegalArgumentException("unsupported partition type " + type);
  }

  // Timestamps without an offset are read as UTC, the zone Kernel uses for partition values.
  private static Instant timestamp(String text) {
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException localTime) {
      return LocalDateTime.parse(text, TIMESTAMP).toInstant(ZoneOffset.UTC);
    }
  }

  private static DataType castType(String name) {
    return switch (name.toUpperCase(Locale.ROOT)) {
      case "BOOLEAN" -> BooleanType.BOOLEAN;
      case "TINYINT", "BYTE" -> ByteType.BYTE;
      case "SMALLINT", "SHORT" -> ShortType.SHORT;
      case "INT", "INTEGER" -> IntegerType.INTEGER;
      case "BIGINT", "LONG" -> LongType.LONG;
      case "FLOAT", "REAL" -> FloatType.FLOAT;
      case "DOUBLE" -> DoubleType.DOUBLE;
      case "STRING" -> StringType.STRING;
      case "DATE" -> DateType.DATE;
      case "TIMESTAMP" -> TimestampType.TIMESTAMP;
      default -> throw new IllegalArgumentException("unsupported cast type " + name);
    };
  }

  private sealed interface Node permits Identifier, Value, Cast, Comparison, IsNull, Not {}

  private record Identifier(String name) implements Node {}

  /** A literal's text; {@code null} for SQL NULL. */
  private record Value(String text) implements Node {}

  private record Cast(Node child, DataType type) implements Node {}

  private record Comparison(String op, Node left, Node right) implements Node {}

  private record IsNull(Node child, boolean negated) implements Node {}

  private record Not(Node child) implements Node {}

  private record Typed(Column column, DataType type) {}

  /**
   * Recursive-descent parser for one hint. AND, OR, functions, and anything else outside the
   * supported subset fail the parse, which drops the hint.
   */
  private static final class Parser {

    private static final List<String> OPERATORS =
        List.of("<=>", "<=", ">=", "<>", "!=", "==", "=", "<", ">");

    private final List<String> tokens;
    private int position;

    Parser(String hint) {
      this.tokens = tokenize(hint);
    }

    Node parseHint() {
      Node node = expression();
      if (position != tokens.size()) {
        throw new IllegalArgumentException("unsupported hint");
      }
      return node;
    }

    private Node expression() {
      if (keyword("NOT")) {
        return new Not(expression());
      }
      Node left = primary();
      if (keyword("IS")) {
        boolean negated = keyword("NOT");
        expect("NULL");
        return new IsNull(left, negated);
      }
      String next = peek();
      if (next != null && OPERATORS.contains(next)) {
        position++;
        return new Comparison(next, left, primary());
      }
      return left;
    }

    private Node primary() {
      String token = next();
      if (token.equals("(")) {
        Node inner = expression();
        expect(")");
        return inner;
      }
      if (token.equalsIgnoreCase("CAST")) {
        expect("(");
        Node child = expression();
        expect("AS");
        DataType type = castType(next());
        expect(")");
        return new Cast(child, type);
      }
      if ((token.equalsIgnoreCase("DATE") || token.equalsIgnoreCase("TIMESTAMP"))
          && peek() != null
          && peek().startsWith("'")) {
        return new Value(unquote(next()));
      }
      if (token.startsWith("'")) {
        return new Value(unquote(token));
      }
      if (token.startsWith("`")) {
        return new Identifier(token.substring(1, token.length() - 1).replace("``", "`"));
      }
      if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '-' || token.charAt(0) == '.') {
        return new Value(number(token));
      }
      if (token.equalsIgnoreCase("NULL")) {
        return new Value(null);
      }
      if (token.equalsIgnoreCase("TRUE") || token.equalsIgnoreCase("FALSE")) {
        return new Value(token.toLowerCase(Locale.ROOT));
      }
      if (isReserved(token) || !Character.isJavaIdentifierStart(token.charAt(0))) {
        throw new IllegalArgumentException("unexpected " + token);
      }
      return new Identifier(token);
    }

    private static boolean isReserved(String token) {
      return switch (token.toUpperCase(Locale.ROOT)) {
        case "AND", "OR", "NOT", "IS", "AS", "IN", "LIKE", "BETWEEN" -> true;
        default -> false;
      };
    }

    // Spark suffixes: L (bigint), S (smallint), Y (tinyint), D (double), F (float), BD (decimal).
    private static String number(String token) {
      String upper = token.toUpperCase(Locale.ROOT);
      if (upper.endsWith("BD")) {
        return token.substring(0, token.length() - 2);
      }
      if (upper.matches(".*[LSYDF]")) {
        return token.substring(0, token.length() - 1);
      }
      return token;
    }

    private static String unquote(String token) {
      StringBuilder value = new StringBuilder();
      for (int i = 1; i < token.length() - 1; i++) {
        char c = token.charAt(i);
        if (c == '\\' && i + 1 < token.length() - 1) {
          value.append(token.charAt(++i));
        } else if (c == '\'' && token.charAt(i + 1) == '\'') {
          value.append('\'');
          i++;
        } else {
          value.append(c);
        }
      }
      return value.toString();
    }

    private boolean keyword(String word) {
      String token = peek();
      if (token != null && token.equalsIgnoreCase(word)) {
        position++;
        return true;
      }
      return false;
    }

    private void expect(String token) {
      if (!next().equalsIgnoreCase(token)) {
        throw new IllegalArgumentException("expected " + token);
      }
    }

    private String peek() {
      return position < tokens.size() ? tokens.get(position) : null;
    }

    private String next() {
      if (position >= tokens.size()) {
        throw new IllegalArgumentException("unexpected end of hint");
      }
      return tokens.get(position++);
    }

    private static List<String> tokenize(String hint) {
      List<String> tokens = new ArrayList<>();
      int i = 0;
      while (i < hint.length()) {
        char c = hint.charAt(i);
        if (Character.isWhitespace(c)) {
          i++;
        } else if (c == '(' || c == ')' || c == ',') {
          tokens.add(String.valueOf(c));
          i++;
        } else if (c == '\'' || c == '`') {
          int end = closing(hint, i, c);
          tokens.add(hint.substring(i, end + 1));
          i = end + 1;
        } else if (Character.isDigit(c)
            || ((c == '-' || c == '.')
                && i + 1 < hint.length()
                && Character.isDigit(hint.charAt(i + 1)))) {
          int end = i + 1;
          while (end < hint.length()
              && (Character.isLetterOrDigit(hint.charAt(end))
                  || hint.charAt(end) == '.'
                  || ((hint.charAt(end) == '-' || hint.charAt(end) == '+')
                      && Character.toUpperCase(hint.charAt(end - 1)) == 'E'))) {
            end++;
          }
          tokens.add(hint.substring(i, end));
          i = end;
        } else if (Character.isJavaIdentifierStart(c)) {
          int end = i + 1;
          while (end < hint.length() && Character.isJavaIdentifierPart(hint.charAt(end))) {
            end++;
          }
          tokens.add(hint.substring(i, end));
          i = end;
        } else {
          int start = i;
          String operator =
              OPERATORS.stream()
                  .filter(op -> hint.startsWith(op, start))
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("unexpected " + c));
          tokens.add(operator);
          i += operator.length();
        }
      }
      return tokens;
    }

    // Quotes are escaped by doubling; string literals also allow backslash escapes.
    private static int closing(String hint, int start, char quote) {
      int i = start + 1;
      while (i < hint.length()) {
        char c = hint.charAt(i);
        if (quote == '\'' && c == '\\') {
          i += 2;
        } else if (c == quote && i + 1 < hint.length() && hint.charAt(i + 1) == quote) {
          i += 2;
        } else if (c == quote) {
          return i;
        } else {
          i++;
        }
      }
      throw new IllegalArgumentException("unterminated " + quote);
    }
  }
}
