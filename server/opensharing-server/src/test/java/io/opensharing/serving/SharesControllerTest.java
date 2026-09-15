package io.opensharing.serving;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.http.ProtocolMediaType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:list-shares;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class SharesControllerTest {

  @Autowired private MockMvc mvc;

  @Test
  void listsNoSharesUntilShareStorageExists() throws Exception {
    mvc.perform(get("/api/1.0/opensharing/shares"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ProtocolMediaType.JSON_UTF8))
        .andExpect(jsonPath("$.items").isEmpty());
  }
}
