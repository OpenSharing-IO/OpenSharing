package io.opensharing.config;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogFile;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.exception.CatalogException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;

/** Builds the catalog used by the standalone server. */
public final class CatalogConfiguration {

  private static final String CLASSPATH = "classpath:";
  private static final String FILE = "file:";

  private CatalogConfiguration() {}

  public static CatalogConnector catalogConnector(OpenSharingProperties properties) {
    OpenSharingProperties.Catalog catalog = properties.getCatalog();
    String type = catalog.getType() == null ? "" : catalog.getType().trim().toLowerCase(Locale.ROOT);
    return switch (type) {
      case LocalCatalogConnector.NAME -> localConnector(catalog.getLocal());
      default ->
          throw new IllegalStateException(
              "unknown opensharing.catalog.type '"
                  + catalog.getType()
                  + "'; this build ships '"
                  + LocalCatalogConnector.NAME
                  + "'");
    };
  }

  private static CatalogConnector localConnector(OpenSharingProperties.Catalog.Local config) {
    String location = config.getFile();
    if (location == null || location.isBlank()) {
      throw new IllegalStateException("opensharing.catalog.local.file is required");
    }
    try (InputStream in = open(location)) {
      LocalCatalogFile file = LocalCatalogLoader.load(in, location);
      return new LocalCatalogConnector(file);
    } catch (IOException e) {
      throw new CatalogException("failed to read local catalog file " + location, e);
    }
  }

  // A classpath: resource, or a file path with or without file:.
  private static InputStream open(String location) throws IOException {
    InputStream in;
    if (location.startsWith(CLASSPATH)) {
      in =
          CatalogConfiguration.class
              .getClassLoader()
              .getResourceAsStream(location.substring(CLASSPATH.length()).replaceFirst("^/", ""));
    } else {
      Path path = Path.of(location.startsWith(FILE) ? location.substring(FILE.length()) : location);
      try {
        in = Files.newInputStream(path);
      } catch (NoSuchFileException e) {
        in = null;
      }
    }
    if (in == null) {
      throw new IllegalStateException("local catalog file '" + location + "' does not exist");
    }
    return in;
  }
}
