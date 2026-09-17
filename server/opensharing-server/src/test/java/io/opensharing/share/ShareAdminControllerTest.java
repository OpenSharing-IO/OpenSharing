package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.asset.SharedDataObjectRepository;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.http.ErrorCodes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:provider-shares;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class ShareAdminControllerTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";

  @Autowired private MockMvc mvc;
  @Autowired private SharedDataObjectRepository objects;

  @Test
  void requiresACatalogAuthorizedPrincipal() throws Exception {
    // Missing bearer token is unauthenticated.
    mvc.perform(get(SHARES).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
  }

  @Test
  void createsListsUpdatesAndDeletesAShare() throws Exception {
    // Owner creates a share; name is persisted lowercase.
    mvc.perform(
            post(SHARES)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sales\",\"comment\":\"orders\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("sales"))
        .andExpect(jsonPath("$.id").exists());

    // Owner lists shares.
    mvc.perform(get(SHARES).header("Authorization", "Bearer alice-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("sales"));

    // Get by name is case-insensitive and allowed for any catalog principal.
    mvc.perform(get(SHARES + "/SALES").header("Authorization", "Bearer bob-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("sales"))
        .andExpect(jsonPath("$.comment").value("orders"));

    // Non-owner cannot update.
    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer bob-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"stolen\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PERMISSION_DENIED));

    // Owner updates the comment and adds a table.
    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "comment": "updated",
                      "updates": [{
                        "action": "ADD",
                        "dataObject": {
                          "name": "Main.Sales.Orders",
                          "type": "TABLE"
                        }
                      }]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comment").value("updated"));

    assertEquals(1, objects.count());
    var stored = objects.findAll().getFirst();
    assertEquals("sales", stored.getSharedAsSchema());
    assertEquals("orders", stored.getSharedAsTable());
    assertEquals("sales.orders", stored.getSharedAs());

    // Owner removes the table by sharedAs.
    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "updates": [{
                        "action": "REMOVE",
                        "dataObject": {"type": "TABLE", "sharedAs": "SALES.ORDERS"}
                      }]
                    }
                    """))
        .andExpect(status().isOk());

    assertEquals(0, objects.count());

    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "updates": [{
                        "action": "ADD",
                        "dataObject": {
                          "name": "Main.Sales",
                          "type": "SCHEMA"
                        }
                      }]
                    }
                    """))
        .andExpect(status().isOk());

    stored = objects.findAll().getFirst();
    assertEquals("sales", stored.getSharedAsSchema());
    assertEquals("", stored.getSharedAsTable());
    assertEquals("sales", stored.getSharedAs());

    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "updates": [{
                        "action": "REMOVE",
                        "dataObject": {"type": "SCHEMA", "sharedAs": "MAIN.SALES"}
                      }]
                    }
                    """))
        .andExpect(status().isOk());

    assertEquals(0, objects.count());

    // Owner adds the table back so delete can cascade it.
    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "updates": [{
                        "action": "ADD",
                        "dataObject": {
                          "name": "main.sales.orders",
                          "type": "TABLE",
                          "sharedAs": "Sales.Orders"
                        }
                      }]
                    }
                    """))
        .andExpect(status().isOk());

    // Owner deletes the share; shared objects are removed with it.
    mvc.perform(delete(SHARES + "/sales").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNoContent());

    assertEquals(0, objects.count());

    // Deleted share is 404.
    mvc.perform(get(SHARES + "/sales").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void rejectsUnsupportedAssetTypes() throws Exception {
    createShare("edge-volume");
    patchShare(
            "edge-volume",
            """
            {"updates":[{"action":"ADD","dataObject":{"name":"main.sales.orders","type":"VOLUME"}}]}
            """)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    deleteShare("edge-volume");
  }

  @Test
  void rejectsTableNamesWithoutASchema() throws Exception {
    createShare("edge-nodot");
    patchShare(
            "edge-nodot",
            """
            {"updates":[{"action":"ADD","dataObject":{"name":"Orders","type":"TABLE"}}]}
            """)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    deleteShare("edge-nodot");
  }

  @Test
  void rejectsDuplicateAliasAndSource() throws Exception {
    createShare("edge-dup");
    patchShare(
            "edge-dup",
            """
            {"updates":[{"action":"ADD","dataObject":{"name":"Main.Sales.Orders","type":"TABLE"}}]}
            """)
        .andExpect(status().isOk());
    patchShare(
            "edge-dup",
            """
            {"updates":[{"action":"ADD","dataObject":{"name":"Main.Sales.Orders","type":"TABLE"}}]}
            """)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_ALREADY_EXISTS));
    patchShare(
            "edge-dup",
            """
            {"updates":[{"action":"ADD","dataObject":{
              "name":"Main.Sales.Orders","type":"TABLE","sharedAs":"other.orders"}}]}
            """)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_ALREADY_EXISTS));
    deleteShare("edge-dup");
  }

  @Test
  void removeRequiresTypeAndAnExistingObject() throws Exception {
    createShare("edge-remove");
    patchShare(
            "edge-remove",
            """
            {"updates":[{"action":"ADD","dataObject":{"name":"Main.Sales.Orders","type":"TABLE"}}]}
            """)
        .andExpect(status().isOk());
    patchShare(
            "edge-remove",
            """
            {"updates":[{"action":"REMOVE","dataObject":{"sharedAs":"sales.orders"}}]}
            """)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    patchShare(
            "edge-remove",
            """
            {"updates":[{"action":"REMOVE","dataObject":{"type":"TABLE","sharedAs":"missing.table"}}]}
            """)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
    patchShare(
            "edge-remove",
            """
            {"updates":[{"action":"REMOVE","dataObject":{"name":"Main.Sales.Orders","type":"TABLE"}}]}
            """)
        .andExpect(status().isOk());
    deleteShare("edge-remove");
  }

  private void createShare(String name) throws Exception {
    mvc.perform(
            post(SHARES)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated());
  }

  private ResultActions patchShare(String share, String body)
      throws Exception {
    return mvc.perform(
        patch(SHARES + "/" + share)
            .header("Authorization", "Bearer alice-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private void deleteShare(String share) throws Exception {
    mvc.perform(delete(SHARES + "/" + share).header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNoContent());
  }

  @TestConfiguration
  static class CatalogAuthorization {

    @Bean
    @Primary
    CatalogConnector testCatalogConnector() {
      return new CatalogConnector() {
        @Override
        public String name() {
          return "test";
        }

        @Override
        public ResolvedAsset resolveAsset(AssetLookup lookup, UserContext user) {
          return ResolvedAsset.builder(lookup.type(), lookup.identifier())
              .catalogAssetId("catalog-orders-id")
              .format(io.opensharing.catalog.TableFormat.DELTA)
              .subtype("MANAGED")
              .build();
        }

        @Override
        public List<StorageCredentials> getStorageCredentials(
            CredentialRequest request, UserContext user) {
          return List.of();
        }

        @Override
        public UserContext authorize(String bearerToken, String privilege) {
          return switch (bearerToken) {
            case "alice-token" -> new UserContext("catalog-alice-id", "alice");
            case "bob-token" -> new UserContext("catalog-bob-id", "bob");
            default -> throw new CatalogAuthorizationException("invalid bearer token");
          };
        }
      };
    }
  }
}
