package io.opensharing.serving;

/**
 * Every route the recipient-facing protocol serves, in one list.
 *
 * <p>The protocol is one contract even though it spans shares, schemas and tables of several formats,
 * so its routes are stated here rather than discovered by reading the controllers. What a recipient
 * can address is then a single file to diff against {@code spec/protocols/}, the same reason the wire
 * types are kept together in {@code io.opensharing.protocol}.
 *
 * <p>{@code opensharing.protocol-prefix} is a placeholder Spring resolves, so these are usable
 * directly as request mappings.
 */
public final class RecipientApi {

  /** Where the whole protocol is mounted, and what a profile file's {@code endpoint} points at. */
  public static final String PREFIX = "${opensharing.protocol-prefix}";

  public static final String SHARES = PREFIX + "/shares";

  /** A share and everything under it: its schemas, its tables, and all-tables across them. */
  public static final String SHARE = SHARES + "/{share}";

  /** One table, under which the protocol's read operations and credential vending hang. */
  public static final String TABLE = SHARE + "/schemas/{schema}/tables/{table}";

  /** The Iceberg REST catalog surface, where a profile file's {@code icebergEndpoint} points. */
  public static final String ICEBERG = PREFIX + "/iceberg/v1";

  private RecipientApi() {}
}
