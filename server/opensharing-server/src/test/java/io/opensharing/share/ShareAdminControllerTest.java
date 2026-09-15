package io.opensharing.share;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:provider-shares;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml",
      "opensharing.admin.principals[0].name=alice",
      "opensharing.admin.principals[0].bearer-token=alice-token",
      "opensharing.admin.principals[1].name=bob",
      "opensharing.admin.principals[1].bearer-token=bob-token"
    })
@AutoConfigureMockMvc
class ShareAdminControllerTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";

  @Autowired private MockMvc mvc;

  @Test
  void requiresAConfiguredPrincipal() throws Exception {
    mvc.perform(get(SHARES).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
  }

  @Test
  void createsListsUpdatesAndDeletesAShare() throws Exception {
    mvc.perform(
            post(SHARES)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sales\",\"comment\":\"orders\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("sales"))
        .andExpect(jsonPath("$.owner_id").value("alice"));

    mvc.perform(get(SHARES).header("Authorization", "Bearer alice-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("sales"));

    mvc.perform(get(SHARES + "/sales").header("Authorization", "Bearer bob-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comment").value("orders"));

    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer bob-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"stolen\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PERMISSION_DENIED));

    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comment").value("updated"));

    mvc.perform(delete(SHARES + "/sales").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNoContent());

    mvc.perform(get(SHARES + "/sales").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }
}
