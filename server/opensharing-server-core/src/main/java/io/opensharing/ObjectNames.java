package io.opensharing;

import java.util.Locale;

/**
 * Name rules from {@code spec/protocols/OVERVIEW.md}. Object names are compared case-insensitively,
 * so callers persist {@link #normalize(String)} alongside the name as provided.
 */
public final class ObjectNames {

  public static final int MAX_LENGTH = 255;

  private ObjectNames() {}

  /** Validates a share name. */
  public static String validateShareName(String name) {
    return validate(name, "share name", false);
  }

  /** Validates a schema name, which additionally disallows {@code .}. */
  public static String validateSchemaName(String name) {
    return validate(name, "schema name", true);
  }

  /** Validates a shared asset name, which additionally disallows {@code .}. */
  public static String validateAssetName(String name) {
    return validate(name, "asset name", true);
  }

  /** Validates a recipient name using the same rules as a share name. */
  public static String validateRecipientName(String name) {
    return validate(name, "recipient name", false);
  }

  /** Validates a principal name, which is often an email address and so may contain periods. */
  public static String validatePrincipalName(String name) {
    return validate(name, "principal name", false);
  }

  /** Lower-cases a name for case-insensitive lookups and uniqueness checks. */
  public static String normalize(String name) {
    return name == null ? null : name.toLowerCase(Locale.ROOT);
  }

  private static String validate(String name, String what, boolean rejectPeriod) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException(what + " must not be empty");
    }
    if (name.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(what + " must not exceed " + MAX_LENGTH + " characters");
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == ' ') {
        throw new IllegalArgumentException(what + " must not contain a space");
      }
      if (c == '/') {
        throw new IllegalArgumentException(what + " must not contain a forward slash");
      }
      if (c <= 0x1F || c == 0x7F) {
        throw new IllegalArgumentException(what + " must not contain control characters");
      }
      if (rejectPeriod && c == '.') {
        throw new IllegalArgumentException(what + " must not contain a period");
      }
    }
    return name;
  }
}
