package io.opensharing.asset;

import io.opensharing.ObjectNames;

/**
 * The recipient-visible alias of a shared object, a two-level {@code schema.name} — or a one-level
 * schema name, when the object shared is itself a schema. When a caller does not supply one, the
 * trailing levels of the catalog name are used, so {@code main.sales.orders} is shared as {@code
 * sales.orders} and the schema {@code main.sales} as {@code sales}.
 */
final class SharedAliases {

  private SharedAliases() {}

  /**
   * A shared schema's alias is one level, because the schema is itself the level a recipient sees. So
   * {@code main.sales} shared as {@code sales} makes every table the catalog puts in {@code
   * main.sales} appear under the schema {@code sales}.
   */
  static String schema(String sharedAs) {
    requireStated(sharedAs);
    if (sharedAs.contains(".")) {
      throw new IllegalArgumentException(
          "a schema is shared as a one-level name, got '" + sharedAs + "'");
    }
    return ObjectNames.validateSchemaName(sharedAs);
  }

  static String defaultSchemaFor(String catalogName) {
    String[] parts = catalogName.split("\\.", -1);
    return parts[parts.length - 1];
  }

  static String[] split(String sharedAs) {
    requireStated(sharedAs);
    String[] parts = sharedAs.split("\\.", -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException(
          "shared_as must be a two-level 'schema.name', got '" + sharedAs + "'");
    }
    ObjectNames.validateSchemaName(parts[0]);
    ObjectNames.validateAssetName(parts[1]);
    return parts;
  }

  private static void requireStated(String sharedAs) {
    if (sharedAs == null || sharedAs.isBlank()) {
      throw new IllegalArgumentException("shared_as must not be blank");
    }
  }

  static String defaultFor(String catalogName) {
    String[] parts = catalogName.split("\\.", -1);
    if (parts.length < 2) {
      throw new IllegalArgumentException(
          "shared_as is required because '"
              + catalogName
              + "' has no schema to derive one from");
    }
    return parts[parts.length - 2] + "." + parts[parts.length - 1];
  }
}
