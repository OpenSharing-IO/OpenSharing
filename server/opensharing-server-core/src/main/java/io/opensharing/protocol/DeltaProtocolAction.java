package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What a reader must support, as delta response format states it: the log's own protocol action,
 * whole, rather than the single reader version the parquet format carries.
 *
 * @param version the version this protocol was committed at, sent only when a streaming client
 *     asked for the protocol changes inside its window
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaProtocolAction(Protocol deltaProtocol, Long version)
    implements TableAction.Protocol {

  /** The protocol action of the Delta log, which a Delta library reads as its own. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Protocol(
      int minReaderVersion,
      int minWriterVersion,
      List<String> readerFeatures,
      List<String> writerFeatures) {}
}
