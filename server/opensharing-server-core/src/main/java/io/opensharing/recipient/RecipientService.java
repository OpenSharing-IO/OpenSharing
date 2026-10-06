package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import java.util.UUID;

/**
 * Provider recipient CRUD. The host authenticates the caller and checks CREATE_RECIPIENT before
 * {@link #create}; any caller may read recipients, only the owner may change them.
 *
 * <p>{@code activationBaseUrl} is the absolute URL recipient activation links are built under, such
 * as {@code https://host/api/1.0/opensharing/activations}; the host knows its own address.
 */
public class RecipientService {

  private final RecipientStore recipients;

  public RecipientService(RecipientStore recipients) {
    this.recipients = recipients;
  }

  /** Creates a recipient owned by {@code user}, with a new activation code. */
  public RecipientResponse create(
      UserContext user, CreateRecipientRequest request, String activationBaseUrl) {
    if (request.authenticationType() == null) {
      throw ApiException.invalidParameter("authenticationType is required");
    }
    if (request.authenticationType() != AuthenticationType.TOKEN) {
      throw ApiException.invalidParameter(
          "authenticationType " + request.authenticationType() + " is not supported yet");
    }
    RecipientEntity recipient =
        recipients.create(
            user,
            ObjectNames.validateRecipientName(request.name()),
            request.comment(),
            request.authenticationType(),
            UUID.randomUUID().toString());
    return toResponse(recipient, activationBaseUrl);
  }

  /** Lists every recipient by name, unpaged. */
  public ListResponse<RecipientResponse> list(String activationBaseUrl) {
    // TODO: page with maxResults and pageToken.
    return ListResponse.of(
        recipients.list().stream().map(r -> toResponse(r, activationBaseUrl)).toList());
  }

  /** Gets a recipient by name in any case. */
  public RecipientResponse get(String recipient, String activationBaseUrl) {
    return toResponse(recipients.require(recipient), activationBaseUrl);
  }

  /** Updates the fields set in {@code request}. Owner only. */
  public RecipientResponse update(
      UserContext user,
      String recipient,
      UpdateRecipientRequest request,
      String activationBaseUrl) {
    return toResponse(recipients.update(user, recipient, request.comment()), activationBaseUrl);
  }

  /** Deletes the recipient and its tokens. Owner only. */
  public void delete(UserContext user, String recipient) {
    recipients.delete(recipient, user);
  }

  private RecipientResponse toResponse(RecipientEntity recipient, String activationBaseUrl) {
    String code = recipients.findActivationCode(recipient);
    return RecipientResponse.from(recipient, code == null ? null : activationBaseUrl + "/" + code);
  }
}
