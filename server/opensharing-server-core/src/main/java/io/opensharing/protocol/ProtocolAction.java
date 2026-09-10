package io.opensharing.protocol;

/**
 * The reader version a client needs to interpret the response, so a client too old for a table is
 * told rather than left to misread it. This is all the parquet response format says about the
 * protocol; delta format sends the log's own action, as {@link DeltaProtocolAction}.
 */
public record ProtocolAction(int minReaderVersion) implements TableAction.Protocol {}
