package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

class ProtocolTableCredentialsTest extends ProtocolApiSupport {

  @Test
  void vendsTemporaryCredentialsForAGrantedTable() {
    createShare("table-creds");
    addObject("table-creds", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-creds", "SCHEMA", "main.hr", "hr");
    createShare("hidden-creds");
    addObject("hidden-creds", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-creds-partner");
    grant("table-creds", "table-creds-partner");

    // No body vends the local catalog's placeholder AWS credentials for the table root.
    Response root = server.post(credentials("TABLE-CREDS", "SALES", "ORDERS"), bearer, null);
    assertEquals(200, root.status());
    JsonNode credentials = root.body().path("credentials");
    assertEquals("s3://test/main.sales.orders/", credentials.path("location").asText());
    JsonNode aws = credentials.path("awsTempCredentials");
    assertTrue(aws.path("accessKeyId").asText().startsWith("ASIA"));
    assertTrue(aws.has("secretAccessKey"));
    assertTrue(aws.has("sessionToken"));
    assertTrue(credentials.path("azureUserDelegationSas").isMissingNode());
    assertTrue(credentials.path("expirationTime").asLong() > 0);

    // An explicit location may repeat the table root, with or without its trailing slash.
    Response explicit =
        server.post(
            credentials("table-creds", "sales", "orders"),
            bearer,
            "{\"location\":\"s3://test/main.sales.orders/\"}");
    assertEquals(200, explicit.status());
    assertEquals(
        "s3://test/main.sales.orders/",
        explicit.body().path("credentials").path("location").asText());
    Response unslashed =
        server.post(
            credentials("table-creds", "sales", "orders"),
            bearer,
            "{\"location\":\"s3://test/main.sales.orders\"}");
    assertEquals(200, unslashed.status());
    assertEquals(
        "s3://test/main.sales.orders/",
        unslashed.body().path("credentials").path("location").asText());

    // A SCHEMA grant can mint credentials for a catalog child.
    Response child = server.post(credentials("table-creds", "hr", "employees"), bearer, "{}");
    assertEquals(200, child.status());
    assertEquals(
        "s3://test/main.hr.employees/", child.body().path("credentials").path("location").asText());

    // A body that is not JSON is malformed.
    Response malformed = server.post(credentials("table-creds", "sales", "orders"), bearer, "{");
    assertEquals(400, malformed.status());
    assertEquals(ErrorCodes.MALFORMED_REQUEST, malformed.errorCode());

    // A location outside the table root and auxiliaries is INVALID_PARAMETER_VALUE.
    Response outside =
        server.post(
            credentials("table-creds", "sales", "orders"),
            bearer,
            "{\"location\":\"s3://other/prefix/\"}");
    assertEquals(400, outside.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, outside.errorCode());

    // A table in an ungranted share is 404, not 403.
    Response hidden = server.post(credentials("hidden-creds", "sales", "customers"), bearer, null);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }

  private static String credentials(String share, String schema, String table) {
    return PROTOCOL
        + "/shares/"
        + share
        + "/schemas/"
        + schema
        + "/tables/"
        + table
        + "/temporary-table-credentials";
  }
}
