package io.opensharing.share;

import io.opensharing.protocol.Share;
import io.opensharing.protocol.Schema;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps stored shares onto protocol wire types. Optional fields are emitted as null so they are
 * omitted from responses rather than sent as empty values.
 */
@Component
public class ShareMapper {

  public Share share(ShareEntity share) {
    return new Share(
        share.getName(),
        share.getId(),
        share.getDisplayName(),
        share.getComment(),
        emptyToNull(share.getProperties()));
  }

  public Schema schema(ShareEntity share, String schemaName) {
    return new Schema(schemaName, share.getName());
  }

  private static Map<String, String> emptyToNull(Map<String, String> values) {
    return values == null || values.isEmpty() ? null : Map.copyOf(values);
  }
}
