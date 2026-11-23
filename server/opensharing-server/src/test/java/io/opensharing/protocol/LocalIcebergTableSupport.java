package io.opensharing.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/** Iceberg endpoint tests with temporary Delta tables in {@code main.sales} and {@code main.hr}. */
abstract class LocalIcebergTableSupport extends ProtocolApiSupport {

  @TempDir static Path tables;

  @BeforeAll
  static void startLocalIcebergServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", write("local-catalog.yml", catalog()).toString());
    restartServer(properties);
  }

  private static String catalog() {
    try (InputStream in =
        LocalIcebergTableSupport.class.getResourceAsStream("/local-catalog-iceberg.yml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .replace("${tables}", tables.toUri().toString())
          .replace("${aliceToken}", ALICE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path write(String relativePath, String content) {
    Path path = tables.resolve(relativePath);
    try {
      Files.createDirectories(path.getParent());
      return Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
