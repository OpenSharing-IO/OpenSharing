package io.opensharing.serving;

/**
 * Headers the protocol names, shared by the endpoints that read them and the formats that set them.
 */
public final class ProtocolHeaders {

  /** The version a response describes, on every table read operation. */
  public static final String TABLE_VERSION = "Delta-Table-Version";

  /** What a client accepts, and what the server chose in return. */
  public static final String CAPABILITIES = "delta-sharing-capabilities";

  /** The scheme a client wants file ids derived by, echoed back once accepted. */
  public static final String FILE_ID_HASH = "fileidhash";

  private ProtocolHeaders() {}
}
