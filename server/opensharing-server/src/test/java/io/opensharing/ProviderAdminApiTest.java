package io.opensharing;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The provider-admin API: shares and their contents, recipients, permissions, and the recipient token
 * lifecycle.
 */
class ProviderAdminApiTest extends ServerTestBase {

  @Test
  void requiresAPrincipalsToken() throws Exception {
    mvc.perform(get(ADMIN_BASE + "/shares"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

    mvc.perform(get(ADMIN_BASE + "/shares").header("Authorization", "Bearer wrong-token"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * There is no principal table for a caller's token to end up in, at all — configured or resolved
   * fresh from the catalog, a caller's identity lives only for the length of the request that
   * presented it, so there is nothing here for a database dump to expose.
   */
  @Test
  void requiresAKnownPrincipalsTokenAndKeepsNoRecordOfIt() throws Exception {
    mvc.perform(get(ADMIN_BASE + "/shares").header("Authorization", "Bearer " + ALICE_TOKEN))
        .andExpect(status().isOk());
    assertThrows(
        Exception.class,
        () -> jdbc.queryForObject("select 1 from os_principals", Integer.class),
        "os_principals should not exist at all");
  }

  @Test
  void createsAShareOwnedByItsCreator() throws Exception {
    String alice = principalId(ALICE);
    String share = createShare(unique("vaccine_share"));

    JsonNode read = adminGet("/shares/" + share);
    assertEquals(share, read.get("name").asText());
    assertNotNull(read.get("share_id").asText());
    assertEquals("test share", read.get("comment").asText());
    assertEquals(alice, read.get("owner_id").asText());
    assertEquals(alice, read.get("created_by").asText());
    assertEquals(alice, read.get("updated_by").asText());

    // Share names are case-insensitive.
    assertEquals(share, adminGet("/shares/" + share.toUpperCase()).get("name").asText());
  }

  @Test
  void rejectsDuplicateAndInvalidShareNames() throws Exception {
    String share = createShare(unique("dupe"));

    mvc.perform(adminJson(post(ADMIN_BASE + "/shares")).content("{\"name\":\"" + share + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_ALREADY_EXISTS"));

    // Names are compared case-insensitively, so a differently cased name is the same share.
    mvc.perform(
            adminJson(post(ADMIN_BASE + "/shares"))
                .content("{\"name\":\"" + share.toUpperCase() + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_ALREADY_EXISTS"));

    mvc.perform(adminJson(post(ADMIN_BASE + "/shares")).content("{\"name\":\"has a space\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER_VALUE"));
  }

  @Test
  void rejectsDuplicateRecipientNamesWithoutMintingAnotherToken() throws Exception {
    String recipient = createRecipient(unique("dupe_partner"));

    mvc.perform(
            adminJson(post(ADMIN_BASE + "/recipients"))
                .content("{\"name\":\"" + recipient.toUpperCase() + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_ALREADY_EXISTS"));

    assertEquals(1, adminGet("/recipients/" + recipient).get("tokens").size());
  }

  @Test
  void addingATableCapturesWhatTheCatalogReports() throws Exception {
    String alice = principalId(ALICE);
    String share = createShare(unique("sales_share"));

    JsonNode object = addTable(share, "sales.orders", "main.sales.orders");

    assertEquals("TABLE", object.get("type").asText());
    assertEquals("main.sales.orders", object.get("name").asText());
    assertEquals("sales.orders", object.get("shared_as").asText());
    assertEquals("ACTIVE", object.get("status").asText());
    assertEquals("delta", object.get("source_format").asText());
    assertEquals("MANAGED", object.get("source_subtype").asText());
    assertEquals("main.sales.orders", object.get("source_asset_id").asText());
    assertEquals("s3://acme-lake/sales/orders/", object.get("storage_location").asText());
    assertEquals("dir", object.get("access_modes").get(0).asText());
    assertEquals(alice, object.get("added_by").asText());
    assertNotNull(Instant.parse(object.get("added_at").asText()));
  }

  @Test
  void sharesATableUnderItsCatalogSchemaWhenNoAliasIsGiven() throws Exception {
    String share = createShare(unique("default_alias_share"));

    JsonNode updated =
        adminPatch(
            "/shares/" + share,
            "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                + "{\"name\":\"main.sales.orders\"}}]}");

    assertEquals("sales.orders", updated.get("objects").get(0).get("shared_as").asText());
  }

  @Test
  void removesATableByAliasOrByCatalogName() throws Exception {
    String share = createShare(unique("removals_share"));
    addTable(share, "sales.orders", "main.sales.orders");
    addTable(share, "sales.forecast", "main.sales.forecast");

    JsonNode afterAlias =
        adminPatch(
            "/shares/" + share,
            "{\"updates\":[{\"action\":\"REMOVE\",\"data_object\":"
                + "{\"name\":\"main.sales.orders\",\"shared_as\":\"sales.orders\"}}]}");
    assertEquals(1, afterAlias.get("objects").size());

    JsonNode afterName =
        adminPatch(
            "/shares/" + share,
            "{\"updates\":[{\"action\":\"REMOVE\",\"data_object\":"
                + "{\"name\":\"main.sales.forecast\"}}]}");
    assertEquals(0, afterName.get("objects").size());

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"REMOVE\",\"data_object\":"
                        + "{\"name\":\"main.sales.orders\"}}]}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(containsString("is not shared")));
  }

  @Test
  void appliesMetadataAndContentChangesInOneRequest() throws Exception {
    String share = createShare(unique("combined_share"));

    JsonNode updated =
        adminPatch(
            "/shares/" + share,
            "{\"comment\":\"curated sales data\",\"properties\":{\"tier\":\"gold\"},"
                + "\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                + "{\"name\":\"main.sales.orders\",\"shared_as\":\"sales.orders\"}}]}");

    assertEquals("curated sales data", updated.get("comment").asText());
    assertEquals("gold", updated.get("properties").get("tier").asText());
    assertEquals(1, updated.get("objects").size());
  }

  @Test
  void rollsBackAWholePatchWhenOneUpdateIsRejected() throws Exception {
    String share = createShare(unique("atomic_share"));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"comment\":\"never applied\",\"updates\":["
                        + "{\"action\":\"ADD\",\"data_object\":{\"name\":\"main.sales.orders\"}},"
                        + "{\"action\":\"ADD\",\"data_object\":{\"name\":\"main.sales.ghost\"}}]}"))
        .andExpect(status().isBadRequest());

    JsonNode read = adminGet("/shares/" + share);
    assertEquals(0, read.get("objects").size(), "the accepted object must not survive");
    assertEquals("test share", read.get("comment").asText());
  }


  @Test
  void refusesTablesThatDoNotExistInTheCatalog() throws Exception {
    String share = createShare(unique("bad_share"));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.sales.ghost\",\"type\":\"TABLE\"}}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER_VALUE"))
        .andExpect(jsonPath("$.message").value(containsString("does not exist")));
  }

  @Test
  void refusesATableTheCallerMayNotShare() throws Exception {
    String share = createShare(unique("restricted_share"));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.finance.ledger\"}}]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("PERMISSION_DENIED"))
        .andExpect(jsonPath("$.message").value(containsString(ALICE)));
  }

  @Test
  void refusesATableTheCatalogCannotPointAtStorage() throws Exception {
    String share = createShare(unique("no_location_share"));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.sales.no_location\"}}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER_VALUE"))
        .andExpect(jsonPath("$.message").value(containsString("storage location")));
  }

  @Test
  void refusesTheSameTableOrAliasTwiceInAShare() throws Exception {
    String share = createShare(unique("collision_share"));
    addTable(share, "sales.orders", "main.sales.orders");

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.sales.orders\",\"shared_as\":\"other.orders\"}}]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("already shared")));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.sales.forecast\",\"shared_as\":\"sales.orders\"}}]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("already exists")));
  }

  @Test
  void refusesACatalogNameLongerThanTheColumnHolds() throws Exception {
    String share = createShare(unique("long_name_share"));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":{\"name\":\""
                        + "main.sales.".repeat(50)
                        + "orders\"}}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER_VALUE"))
        .andExpect(jsonPath("$.message").value(containsString("512 characters")));
  }

  @Test
  void refusesAnAliasThatIsNotSchemaAndName() throws Exception {
    String share = createShare(unique("alias_share"));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.sales.orders\",\"shared_as\":\"orders\"}}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("two-level")));
  }

  @Test
  void refusesObjectTypesThatCannotBeSharedYet() throws Exception {
    String share = createShare(unique("volume_share"));

    mvc.perform(
            adminJson(patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.sales.files\",\"type\":\"VOLUME\"}}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("only TABLE")));
  }

  @Test
  void mintsATokenWithTheRecipientAndRevealsItOnlyThroughTheActivationUrl() throws Exception {
    String name = unique("acme");
    JsonNode created =
        adminPost(
            "/recipients",
            "{\"name\":\"" + name + "\",\"auth_type\":\"TOKEN\",\"token_expiration_seconds\":7776000}");

    assertEquals(name, created.get("recipient").get("name").asText());
    assertEquals("TOKEN", created.get("recipient").get("auth_type").asText());
    JsonNode issued = created.get("token");
    assertTrue(
        issued
            .get("activation_url")
            .asText()
            .startsWith("https://sharing.example.com" + ACTIVATION_BASE + "/"));
    assertTrue(issued.path("bearer_token").isMissingNode(), "the token must not be returned here");

    String nonce = nonceOf(issued.get("activation_url").asText());
    JsonNode profile =
        readJson(
            mvc.perform(get(ACTIVATION_BASE + "/" + nonce)).andExpect(status().isOk()).andReturn());
    assertEquals(1, profile.get("shareCredentialsVersion").asInt());
    assertEquals(
        "https://sharing.example.com" + PROTOCOL_BASE, profile.get("endpoint").asText());
    assertEquals(
        "https://sharing.example.com" + PROTOCOL_BASE + "/iceberg",
        profile.get("icebergEndpoint").asText());
    assertTrue(profile.get("bearerToken").asText().startsWith("os_"));
    assertNotNull(profile.get("expirationTime").asText());

    mvc.perform(get(ACTIVATION_BASE + "/" + nonce)).andExpect(status().isNotFound());
    assertTrue(adminGet("/recipients/" + name).get("tokens").get(0).get("activated").asBoolean());
  }

  @Test
  void rejectsAuthTypesThatAreNotImplemented() throws Exception {
    mvc.perform(
            adminJson(post(ADMIN_BASE + "/recipients"))
                .content(
                    "{\"name\":\"" + unique("oidc_partner") + "\",\"auth_type\":\"OIDC\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("OIDC")));
  }

  @Test
  void rejectsAnIpAccessListThatIsNotCidr() throws Exception {
    mvc.perform(
            adminJson(post(ADMIN_BASE + "/recipients"))
                .content(
                    "{\"name\":\""
                        + unique("bad_cidr")
                        + "\",\"ip_access_list\":[\"203.0.113.0/33\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("valid CIDR")));
  }

  @Test
  void updatesARecipientsAllowlistAndProperties() throws Exception {
    String recipient = createRecipient(unique("evolving"));

    JsonNode updated =
        adminPatch(
            "/recipients/" + recipient,
            "{\"ip_access_list\":[\"203.0.113.0/24\"],\"properties\":{\"region\":\"eu\"}}");

    assertEquals("203.0.113.0/24", updated.get("ip_access_list").get(0).asText());
    assertEquals("eu", updated.get("properties").get("region").asText());
  }

  @Test
  void rotationKeepsTheSupersededTokenAliveForItsGrace() throws Exception {
    String share = createShare(unique("rotating_share"));
    String recipient = unique("rotating");
    String oldToken = createRecipientWithToken(recipient);
    grant(share, recipient);

    String newToken = rotateToken(recipient);

    // Both work until the grace window closes, so the recipient can switch over at its own pace.
    protocolGet(oldToken, "/shares");
    protocolGet(newToken, "/shares");
    JsonNode tokens = adminGet("/recipients/" + recipient).get("tokens");
    assertEquals(2, tokens.size());
    assertNotNull(
        Instant.parse(tokens.get(1).get("superseded_at").asText()),
        "the replaced token records when it was superseded");
  }

  @Test
  void rotationWithoutGraceCutsTheSupersededTokenOffAtOnce() throws Exception {
    String share = createShare(unique("abrupt_share"));
    String recipient = unique("compromised");
    String oldToken = createRecipientWithToken(recipient);
    grant(share, recipient);

    String newToken = rotateToken(recipient, "{\"existing_token_expire_in_seconds\":0}");

    mvc.perform(get(PROTOCOL_BASE + "/shares").header("Authorization", "Bearer " + oldToken))
        .andExpect(status().isUnauthorized());
    protocolGet(newToken, "/shares");
    assertNotEquals(oldToken, newToken);
  }

  @Test
  void rotationInvalidatesAnActivationLinkThatWasNeverUsed() throws Exception {
    String recipient = unique("never_activated");
    JsonNode created = adminPost("/recipients", "{\"name\":\"" + recipient + "\"}");
    String staleNonce = nonceOf(created.get("token").get("activation_url").asText());

    rotateToken(recipient);

    mvc.perform(get(ACTIVATION_BASE + "/" + staleNonce)).andExpect(status().isNotFound());
  }

  @Test
  void grantsAndRevokesShareAccess() throws Exception {
    String alice = principalId(ALICE);
    String share = createShare(unique("granted_share"));
    String recipient = createRecipient(unique("partner"));

    grant(share, recipient);
    JsonNode granted = adminGet("/shares/" + share + "/permissions").get("items").get(0);
    assertEquals(recipient, granted.get("recipient_name").asText());
    assertEquals("SELECT", granted.get("privilege").asText());
    assertEquals(alice, granted.get("granted_by").asText());
    assertNotNull(Instant.parse(granted.get("granted_at").asText()));

    // The recipient's own view of what it can read agrees.
    assertEquals(
        share,
        adminGet("/recipients/" + recipient + "/share-permissions")
            .get("items")
            .get(0)
            .get("share_name")
            .asText());

    revoke(share, recipient);
    assertEquals(0, adminGet("/shares/" + share + "/permissions").get("items").size());
  }

  @Test
  void grantingTwiceKeepsTheOriginalGrant() throws Exception {
    String share = createShare(unique("idempotent_share"));
    String recipient = createRecipient(unique("eager_partner"));

    grant(share, recipient);
    String grantedAt =
        adminGet("/shares/" + share + "/permissions").get("items").get(0).get("granted_at").asText();
    grant(share, recipient);

    JsonNode permissions = adminGet("/shares/" + share + "/permissions").get("items");
    assertEquals(1, permissions.size());
    assertEquals(grantedAt, permissions.get(0).get("granted_at").asText());
  }

  @Test
  void deletingAShareTakesItsObjectsAndPermissionsWithIt() throws Exception {
    String share = createShare(unique("doomed_share"));
    String recipient = createRecipient(unique("bystander"));
    addTable(share, "sales.orders", "main.sales.orders");
    grant(share, recipient);

    mvc.perform(adminJson(delete(ADMIN_BASE + "/shares/" + share)))
        .andExpect(status().isNoContent());

    mvc.perform(adminJson(get(ADMIN_BASE + "/shares/" + share))).andExpect(status().isNotFound());

    // The recipient survives, and re-creating the share starts from an empty share.
    adminGet("/recipients/" + recipient);
    createShare(share);
    assertEquals(0, adminGet("/shares/" + share).get("objects").size());
    assertEquals(0, adminGet("/shares/" + share + "/permissions").get("items").size());
  }

  @Test
  void deletingARecipientRevokesItsPermissionsAndTokens() throws Exception {
    String share = createShare(unique("kept_share"));
    String recipient = unique("departing");
    String token = createRecipientWithToken(recipient);
    grant(share, recipient);

    mvc.perform(adminJson(delete(ADMIN_BASE + "/recipients/" + recipient)))
        .andExpect(status().isNoContent());

    assertEquals(0, adminGet("/shares/" + share + "/permissions").get("items").size());
    mvc.perform(get(PROTOCOL_BASE + "/shares").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void letsOnlyTheOwnerChangeAShareOrARecipient() throws Exception {
    String share = createShare(unique("alices_share"));
    String recipient = createRecipient(unique("alices_partner"));

    String mallory = MALLORY;

    // Reading is open to any principal.
    mvc.perform(as(MALLORY_TOKEN, get(ADMIN_BASE + "/shares/" + share)))
        .andExpect(status().isOk());
    mvc.perform(as(MALLORY_TOKEN, get(ADMIN_BASE + "/recipients/" + recipient)))
        .andExpect(status().isOk());

    // Writing is not.
    mvc.perform(
            as(MALLORY_TOKEN, patch(ADMIN_BASE + "/shares/" + share))
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.sales.orders\",\"shared_as\":\"sales.orders\"}}]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("PERMISSION_DENIED"))
        .andExpect(jsonPath("$.message").value(containsString("does not own share")));
    mvc.perform(
            as(MALLORY_TOKEN, patch(ADMIN_BASE + "/shares/" + share + "/permissions"))
                .content(
                    "{\"changes\":[{\"recipient_name\":\""
                        + recipient
                        + "\",\"add\":[\"SELECT\"]}]}"))
        .andExpect(status().isForbidden());
    mvc.perform(as(MALLORY_TOKEN, delete(ADMIN_BASE + "/shares/" + share)))
        .andExpect(status().isForbidden());
    mvc.perform(
            as(MALLORY_TOKEN, patch(ADMIN_BASE + "/recipients/" + recipient))
                .content("{\"ip_access_list\":[\"10.0.0.0/8\"]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("does not own recipient")));
    mvc.perform(
            as(MALLORY_TOKEN, post(ADMIN_BASE + "/recipients/" + recipient + "/rotate-token"))
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(as(MALLORY_TOKEN, delete(ADMIN_BASE + "/recipients/" + recipient)))
        .andExpect(status().isForbidden());

    // Alice owns both, so nothing above applies to her.
    addTable(share, "sales.orders", "main.sales.orders");
    grant(share, recipient);
    rotateToken(recipient);
    mvc.perform(adminJson(delete(ADMIN_BASE + "/shares/" + share)))
        .andExpect(status().isNoContent());
    mvc.perform(adminJson(delete(ADMIN_BASE + "/recipients/" + recipient)))
        .andExpect(status().isNoContent());
  }

  private MockHttpServletRequestBuilder adminJson(MockHttpServletRequestBuilder request) {
    return as(ALICE_TOKEN, request);
  }

  private MockHttpServletRequestBuilder as(
      String bearerToken, MockHttpServletRequestBuilder request) {
    return request
        .header("Authorization", "Bearer " + bearerToken)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
