package io.opensharing.protocol;

/**
 * A failure as the Iceberg REST catalog states it, which is not how the rest of the protocol states
 * one.
 *
 * <p>The shape matters as much as the status: an Iceberg client parses this body to decide what kind
 * of failure it had, turning a 404 with it into "no such table" and a 404 without it into a transport
 * error. So the same failure is rendered this way on the Iceberg surface and as {@code {errorCode,
 * message}} everywhere else.
 *
 * @param error the failure, wrapped because the Iceberg spec wraps it
 */
public record IcebergError(Details error) {

  /**
   * @param type the kind of failure, for which this server uses its own protocol error codes, since
   *     a client that wants a category switches on the code instead
   */
  public record Details(String message, String type, int code) {}

  public static IcebergError of(String message, String type, int code) {
    return new IcebergError(new Details(message, type, code));
  }
}
