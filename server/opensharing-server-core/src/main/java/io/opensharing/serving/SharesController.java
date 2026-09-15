package io.opensharing.serving;

import io.opensharing.http.ListResponse;
import io.opensharing.http.ProtocolMediaType;
import io.opensharing.protocol.Share;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recipient-facing {@code GET /shares}. Persistence and recipient auth come in later slices. */
@RestController
@RequestMapping(
    value = "${opensharing.protocol-prefix}/shares",
    produces = ProtocolMediaType.JSON_UTF8)
public class SharesController {

  @GetMapping
  public ListResponse<Share> listShares() {
    return ListResponse.of(List.of());
  }
}
