package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.opensharing.OpenSharingServer;
import io.opensharing.config.ServerProperties;
import java.net.URI;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ProfileEndpointTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void pointsProfilesAtThePublicUrlRatherThanTheRequestAddress() throws Exception {
    Properties props = new Properties();
    props.setProperty("server.port", "0");
    props.setProperty("server.public-url", "https://sharing.example.com");
    try (OpenSharingServer server = OpenSharingServer.start(new ServerProperties(props))) {
      WebClient client = WebClient.of("http://127.0.0.1:" + server.port());
      AggregatedHttpResponse created =
          client
              .execute(
                  RequestHeaders.builder(
                          HttpMethod.POST, "/api/1.0/opensharing/provider/recipients")
                      .contentType(MediaType.JSON)
                      .add("authorization", "Bearer alice-token")
                      .build(),
                  "{\"name\":\"acme\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}")
              .aggregate()
              .join();
      assertEquals(201, created.status().code());
      String activationUrl = JSON.readTree(created.contentUtf8()).path("activationUrl").asText();

      AggregatedHttpResponse profile =
          client.get(URI.create(activationUrl).getPath()).aggregate().join();
      assertEquals(200, profile.status().code());
      JsonNode body = JSON.readTree(profile.contentUtf8());
      assertEquals(
          "https://sharing.example.com/api/1.0/opensharing", body.path("endpoint").asText());
    }
  }
}
