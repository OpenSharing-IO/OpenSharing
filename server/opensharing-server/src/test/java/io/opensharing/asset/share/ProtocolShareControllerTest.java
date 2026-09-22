package io.opensharing.asset.share;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.asset.ProtocolApiSupport;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-shares;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
class ProtocolShareControllerTest extends ProtocolApiSupport {

  private static final String SHARES = PROTOCOL + "/shares";

  @Test
  void authenticatesARecipientAndListsOnlyGrantedShares() throws Exception {
    // Create two shares and grant only one to the recipient.
    createShare("protocol-granted", "Visible");
    createShare("protocol-hidden", "Hidden");
    String bearer = createAndActivateRecipient("protocol-partner");
    grant("protocol-granted", "protocol-partner");

    // The protocol list exposes the granted share and its protocol metadata only.
    mvc.perform(get(SHARES).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding("UTF-8"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("protocol-granted"))
        .andExpect(jsonPath("$.items[0].displayName").value("Visible"))
        .andExpect(jsonPath("$.items[0].id").exists())
        .andExpect(jsonPath("$.items[0].objects").doesNotExist())
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    // Get wraps the granted share; lookup is case-insensitive.
    mvc.perform(get(SHARES + "/PROTOCOL-GRANTED").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding("UTF-8"))
        .andExpect(jsonPath("$.share.name").value("protocol-granted"))
        .andExpect(jsonPath("$.share.displayName").value("Visible"))
        .andExpect(jsonPath("$.share.id").exists())
        .andExpect(jsonPath("$.share.objects").doesNotExist());

    // An ungranted share is reported as missing rather than forbidden.
    mvc.perform(get(SHARES + "/protocol-hidden").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void rejectsMissingInvalidAndSupersededTokens() throws Exception {
    // Protocol requests require an activated recipient bearer.
    mvc.perform(get(SHARES))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
    mvc.perform(get(SHARES).header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));

    // Rotation with the default zero grace period invalidates the old bearer immediately.
    String bearer = createAndActivateRecipient("protocol-rotated");
    mvc.perform(
            post(PROVIDER + "/recipients/protocol-rotated/rotate-token")
                .header("Authorization", "Bearer " + ALICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(get(SHARES).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
  }

  @Test
  void paginatesGrantedSharesWithOpaqueTokens() throws Exception {
    // Grant two ordered shares to one recipient.
    createShare("protocol-page-a", "A");
    createShare("protocol-page-b", "B");
    String bearer = createAndActivateRecipient("protocol-paged");
    grant("protocol-page-a", "protocol-paged");
    grant("protocol-page-b", "protocol-paged");

    // Resume from the opaque token without repeating the first item.
    String first =
        mvc.perform(
                get(SHARES).param("maxResults", "1").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstName = JsonPath.read(first, "$.items[0].name");
    String pageToken = JsonPath.read(first, "$.nextPageToken");
    String second =
        mvc.perform(
                get(SHARES)
                    .param("maxResults", "1")
                    .param("pageToken", pageToken)
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextPageToken").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertNotEquals(firstName, JsonPath.read(second, "$.items[0].name"));

    // Malformed pagination input is rejected.
    mvc.perform(
            get(SHARES).param("pageToken", "not-a-token").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  void listsManyGrantedSharesAcrossPages() throws Exception {
    int total = 25;
    String bearer = createAndActivateRecipient("protocol-many");
    for (int i = 0; i < total; i++) {
      String name = "protocol-many-%02d".formatted(i);
      createShare(name, "Share " + i);
      grant(name, "protocol-many");
    }

    String first =
        mvc.perform(
                get(SHARES).param("maxResults", "10").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].name").value("protocol-many-00"))
            .andExpect(jsonPath("$.items[9].name").value("protocol-many-09"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstToken = JsonPath.read(first, "$.nextPageToken");
    String second =
        mvc.perform(
                get(SHARES)
                    .param("maxResults", "10")
                    .param("pageToken", firstToken)
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].name").value("protocol-many-10"))
            .andExpect(jsonPath("$.items[9].name").value("protocol-many-19"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondToken = JsonPath.read(second, "$.nextPageToken");
    mvc.perform(
            get(SHARES)
                .param("maxResults", "10")
                .param("pageToken", secondToken)
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.items[0].name").value("protocol-many-20"))
        .andExpect(jsonPath("$.items[4].name").value("protocol-many-24"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(SHARES).param("maxResults", "10000").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(total))
        .andExpect(jsonPath("$.items[0].name").value("protocol-many-00"))
        .andExpect(jsonPath("$.items[24].name").value("protocol-many-24"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());
  }
}
