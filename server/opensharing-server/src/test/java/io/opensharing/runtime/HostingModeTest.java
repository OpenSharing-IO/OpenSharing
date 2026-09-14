package io.opensharing.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.config.OpenSharingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:opensharing-hosting;DB_CLOSE_DELAY=-1",
      "opensharing.hosting.mode=standalone",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:test-catalog.yml"
    })
class HostingModeTest {

  @Autowired private OpenSharingProperties properties;
  @Autowired private SharingRuntime runtime;

  @Test
  void defaultsToStandaloneHosting() {
    assertEquals(OpenSharingProperties.Hosting.Mode.STANDALONE, properties.getHosting().getMode());
    assertEquals(HostingMode.STANDALONE, runtime.hostingMode());
    assertEquals("local", runtime.catalogConnector().name());
  }
}
