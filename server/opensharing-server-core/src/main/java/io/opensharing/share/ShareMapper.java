package io.opensharing.share;

import io.opensharing.protocol.Share;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Maps persisted shares to the recipient protocol wire type. */
@Component
public class ShareMapper {

  public Share toProtocol(ShareEntity share) {
    Map<String, String> properties =
        share.getProperties().isEmpty() ? null : Map.copyOf(share.getProperties());
    return new Share(
        share.getName(),
        share.getId(),
        share.getDisplayName(),
        share.getComment(),
        properties);
  }
}
