package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.opensharing.OpenSharingServer;
import io.opensharing.config.ServerProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class UnknownRouteTest {

  @Test
  void answersUnknownRoutesWithTheProtocolErrorBody() throws Exception {
    Properties props = new Properties();
    props.setProperty("server.port", "0");
    props.setProperty("server.public-url", "https://sharing.example.com");
    try (OpenSharingServer server = OpenSharingServer.start(new ServerProperties(props))) {
      WebClient client = WebClient.of("http://127.0.0.1:" + server.port());
      AggregatedHttpResponse response = client.get("/no-such-route").aggregate().join();

      assertEquals(404, response.status().code());
      JsonNode body = new ObjectMapper().readTree(response.contentUtf8());
      assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, body.get("errorCode").asText());
      assertEquals("the endpoint does not exist", body.get("message").asText());
    }
  }
}
