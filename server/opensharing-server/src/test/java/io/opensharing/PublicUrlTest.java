package io.opensharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.opensharing.config.ServerProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class PublicUrlTest {

  @Test
  void requiresAPublicUrlToStart() {
    Properties props = new Properties();
    props.setProperty("server.port", "0");

    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenSharingServer.start(new ServerProperties(props)));
    assertEquals(
        "server.public-url is required; set it to the address recipients reach this server at",
        missing.getMessage());
  }

  @Test
  void acceptsOnlyHttpUrlsAndDropsTrailingSlashes() {
    assertEquals(
        "https://sharing.example.com/delta", publicUrl("https://sharing.example.com/delta/"));
    for (String invalid :
        new String[] {"sharing.example.com", "ftp://sharing.example.com", "https://x.com/?a=b"}) {
      IllegalArgumentException rejected =
          assertThrows(IllegalArgumentException.class, () -> publicUrl(invalid));
      assertEquals(
          "invalid value '" + invalid + "' for server.public-url; expected an http or https URL",
          rejected.getMessage());
    }
  }

  @Test
  void buildsActivationLinksOnThePublicUrlRatherThanTheRequestAddress() throws Exception {
    Properties props = new Properties();
    props.setProperty("server.port", "0");
    props.setProperty("server.public-url", "https://sharing.example.com/");
    try (OpenSharingServer server = OpenSharingServer.start(new ServerProperties(props))) {
      AggregatedHttpResponse response =
          WebClient.of("http://127.0.0.1:" + server.port())
              .execute(
                  RequestHeaders.builder(
                          HttpMethod.POST, "/api/1.0/opensharing/provider/recipients")
                      .contentType(MediaType.JSON)
                      .add("authorization", "Bearer alice-token")
                      .build(),
                  "{\"name\":\"acme\",\"authenticationType\":\"TOKEN\"}")
              .aggregate()
              .join();

      assertEquals(201, response.status().code());
      JsonNode body = new ObjectMapper().readTree(response.contentUtf8());
      assertTrue(
          body.path("activationUrl")
              .asText()
              .startsWith("https://sharing.example.com/api/1.0/opensharing/activations/"));
    }
  }

  private static String publicUrl(String value) {
    Properties props = new Properties();
    props.setProperty("server.public-url", value);
    return new ServerProperties(props).getPublicUrl();
  }
}
