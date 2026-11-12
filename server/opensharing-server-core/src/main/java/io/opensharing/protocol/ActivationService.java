package io.opensharing.protocol;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import io.opensharing.http.ApiJson;
import io.opensharing.recipient.RecipientService;

/**
 * Public one-time redemption of a recipient activation URL. {@link OpenSharingRecipientApi} mounts
 * it without authentication: the activation code itself is the credential.
 */
@Blocking
public final class ActivationService {

  private final RecipientService recipients;

  public ActivationService(RecipientService recipients) {
    this.recipients = recipients;
  }

  /**
   * {@code GET /{code}}: redeems the code and downloads the recipient's profile file. Fails with
   * not-found when the code is unknown, already redeemed, or expired. The response is not cacheable
   * because it carries the bearer token.
   */
  @Get("/{code}")
  public HttpResponse activate(@Param String code) {
    return ApiJson.response(
        ResponseHeaders.builder(HttpStatus.OK)
            .add(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"config.share\"")
            .add(HttpHeaderNames.CACHE_CONTROL, "no-store")
            .build(),
        recipients.activate(code));
  }
}
