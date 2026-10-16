package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import java.util.UUID;

/**
 * Provider recipient CRUD for a caller the host has authenticated. Creating a recipient needs
 * CREATE_RECIPIENT from the catalog, and only the owner may change it. Only the owner and callers
 * with USE_RECIPIENT, who may read anyone's recipients, are meant to list and get recipients.
 *
 * <p>{@code activationBaseUrl} is the absolute URL recipient activation links are built under, such
 * as {@code https://host/api/1.0/opensharing/activations}; the host knows its own address.
 */
public class RecipientService {

  private final CatalogConnector catalog;
  private final RecipientStore recipients;

  public RecipientService(CatalogConnector catalog, RecipientStore recipients) {
    this.catalog = catalog;
    this.recipients = recipients;
  }

  /**
   * Creates a recipient owned by the caller, who needs CREATE_RECIPIENT from the catalog, with a
   * new activation code.
   */
  public RecipientResponse create(
      UserContext user, CreateRecipientRequest request, String activationBaseUrl) {
    // The host has already authenticated the caller. A host that also checks CREATE_RECIPIENT at
    // its endpoint can make this a no-op.
    catalog.authorize(AuthContext.of(user), Privilege.CREATE_RECIPIENT);
    if (request.authenticationType() == null) {
      throw ApiException.invalidParameter("authenticationType is required");
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
    // TODO: introduce USE_RECIPIENT and list only the caller's recipients unless they hold it.
    return ListResponse.of(
        recipients.list().stream().map(r -> toResponse(r, activationBaseUrl)).toList());
  }

  /** Gets a recipient by name in any case. */
  public RecipientResponse get(String recipient, String activationBaseUrl) {
    // TODO: introduce USE_RECIPIENT and get only the caller's recipient unless they hold it.
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
