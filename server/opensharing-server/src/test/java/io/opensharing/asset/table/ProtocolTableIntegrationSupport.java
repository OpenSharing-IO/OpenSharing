package io.opensharing.asset.table;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.asset.ProtocolApiSupport;
import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Shares catalog tables with new recipients and queries them as a Delta Sharing client. */
abstract class ProtocolTableIntegrationSupport extends ProtocolApiSupport {

  /** Shares {@code catalogName} as {@code sharedAs} in a new share and returns the bearer token. */
  protected String shareTable(String share, String catalogName, String sharedAs) throws Exception {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  /** {@code sharedAs} is {@code schema.table}. */
  protected static String tableEndpoint(String share, String sharedAs) {
    String[] alias = sharedAs.split("\\.");
    return PROTOCOL + "/shares/" + share + "/schemas/" + alias[0] + "/tables/" + alias[1];
  }

  protected ResultActions query(String endpoint, String bearer, String body, String capabilities)
      throws Exception {
    var request =
        post(endpoint)
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (capabilities != null) {
      request = request.header(DeltaSharingCapabilities.HEADER, capabilities);
    }
    return mvc.perform(request);
  }

  /** Expects 200 with {@code version} as the Delta-Table-Version and returns the NDJSON body. */
  protected static String ok(ResultActions response, String version) throws Exception {
    return response
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", version))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** Lines whose single key is {@code action}, such as file or endStreamAction. */
  protected static long count(String ndjson, String action) {
    return Arrays.stream(ndjson.split("\n"))
        .filter(line -> line.startsWith("{\"" + action + "\""))
        .count();
  }
}
