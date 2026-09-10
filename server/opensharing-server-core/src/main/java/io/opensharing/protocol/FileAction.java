package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One data file, with a url the recipient reads directly.
 *
 * @param id stable for a given file across responses, so a client can cache the bytes against it
 * @param stats the log's statistics JSON, passed through, which a client may use or ignore
 * @param expirationTimestamp when the url stops working, in epoch milliseconds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileAction(
    String url,
    String id,
    Map<String, String> partitionValues,
    long size,
    String stats,
    Long version,
    Long timestamp,
    Long expirationTimestamp)
    implements TableAction.File {}
