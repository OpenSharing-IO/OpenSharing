package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:provider-pagination;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml",
      "opensharing.pagination.default-max-results=2",
      "opensharing.pagination.max-max-results=3"
    })
@AutoConfigureMockMvc
class ProviderListPaginationTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";
  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  @Autowired private MockMvc mvc;

  @Test
  void pagesSharesByName() throws Exception {
    for (String name : List.of("paged-c", "paged-a", "paged-b")) {
      create(SHARES, "{\"name\":\"" + name + "\"}");
    }
    List<String> names = walk(SHARES, "name");
    assertTrue(names.containsAll(List.of("paged-a", "paged-b", "paged-c")), names.toString());
  }

  @Test
  void pagesRecipientsByName() throws Exception {
    for (String name : List.of("partner-c", "partner-a", "partner-b")) {
      create(RECIPIENTS, "{\"name\":\"" + name + "\",\"authenticationType\":\"TOKEN\"}");
    }
    List<String> names = walk(RECIPIENTS, "name");
    assertTrue(names.containsAll(List.of("partner-a", "partner-b", "partner-c")), names.toString());
  }

  @Test
  void pagesSharePermissionsByRecipientName() throws Exception {
    create(SHARES, "{\"name\":\"granted\"}");
    for (String name : List.of("grantee-c", "grantee-a", "grantee-b")) {
      create(RECIPIENTS, "{\"name\":\"" + name + "\",\"authenticationType\":\"TOKEN\"}");
    }

    // Updating returns the first page of the share's permissions.
    mvc.perform(
            authorized(patch(SHARES + "/granted/permissions"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[
                      {"recipientName":"grantee-c","add":["SELECT"]},
                      {"recipientName":"grantee-a","add":["SELECT"]},
                      {"recipientName":"grantee-b","add":["SELECT"]}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.nextPageToken").exists());

    assertEquals(
        List.of("grantee-a", "grantee-b", "grantee-c"),
        walk(SHARES + "/granted/permissions", "recipientName"));
  }

  @Test
  void resumesFromPageTokens() throws Exception {
    create(SHARES, "{\"name\":\"tokened\"}");
    for (String name : List.of("holder-a", "holder-b", "holder-c")) {
      create(RECIPIENTS, "{\"name\":\"" + name + "\",\"authenticationType\":\"TOKEN\"}");
      mvc.perform(
              authorized(patch(SHARES + "/tokened/permissions"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"changes\":[{\"recipientName\":\"" + name + "\",\"add\":[\"SELECT\"]}]}"))
          .andExpect(status().isOk());
    }
    String url = SHARES + "/tokened/permissions";

    String first = fetch(authorized(get(url)).param("maxResults", "1"));
    assertEquals(List.of("holder-a"), JsonPath.read(first, "$.items[*].recipientName"));
    String token = nextPageToken(first);

    String rest = fetch(authorized(get(url)).param("pageToken", token));
    assertEquals(List.of("holder-b", "holder-c"), JsonPath.read(rest, "$.items[*].recipientName"));
    assertNull(nextPageToken(rest));

    // A token marks a position, not a page size, so it can be replayed with another maxResults.
    String replayed =
        fetch(authorized(get(url)).param("pageToken", token).param("maxResults", "1"));
    assertEquals(List.of("holder-b"), JsonPath.read(replayed, "$.items[*].recipientName"));
    assertNotNull(nextPageToken(replayed));

    for (String list : List.of(SHARES, RECIPIENTS, url)) {
      mvc.perform(authorized(get(list)).param("pageToken", "not-a-token"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    }
  }

  @Test
  void capsAndValidatesPageSizes() throws Exception {
    for (String name : List.of("capped-a", "capped-b", "capped-c", "capped-d")) {
      create(SHARES, "{\"name\":\"" + name + "\"}");
    }
    mvc.perform(authorized(get(SHARES)).param("maxResults", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3));
    mvc.perform(authorized(get(SHARES)).param("maxResults", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextPageToken").exists());

    mvc.perform(authorized(get(SHARES)).param("maxResults", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  /**
   * Follows nextPageToken from the first page to the last and returns {@code field} of every
   * item, checking that pages hold at most the default size and come in ascending order.
   */
  private List<String> walk(String url, String field) throws Exception {
    List<String> values = new ArrayList<>();
    String token = null;
    do {
      MockHttpServletRequestBuilder request = authorized(get(url));
      if (token != null) {
        request.param("pageToken", token);
      }
      String body = fetch(request);
      List<String> page = JsonPath.read(body, "$.items[*]." + field);
      assertTrue(page.size() <= 2, body);
      values.addAll(page);
      token = nextPageToken(body);
    } while (token != null);
    assertEquals(values.stream().sorted().distinct().toList(), values);
    return values;
  }

  private String fetch(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static String nextPageToken(String body) {
    return (String) JsonPath.<Map<String, Object>>read(body, "$").get("nextPageToken");
  }

  private void create(String url, String body) throws Exception {
    mvc.perform(authorized(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
  }

  private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer alice-token");
  }
}
