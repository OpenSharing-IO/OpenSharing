package io.opensharing.catalog.local;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.opensharing.exception.CatalogException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Reads a {@link LocalCatalogFile} from YAML or JSON, matching keys and enums ignoring case. */
public final class LocalCatalogLoader {

  private static final String CLASSPATH = "classpath:";
  private static final String FILE = "file:";

  private static final ObjectMapper MAPPER =
      YAMLMapper.builder()
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
          .build();

  private LocalCatalogLoader() {}

  /**
   * Reads {@code location}: a {@code classpath:} resource, or a file path with or without {@code
   * file:}.
   */
  public static LocalCatalogFile load(String location) {
    try (InputStream in = open(location)) {
      return load(in, location);
    } catch (IOException e) {
      throw new CatalogException("failed to read local catalog file " + location, e);
    }
  }

  /**
   * Parses {@code in} without closing it. {@code origin} only names the source in error messages.
   */
  public static LocalCatalogFile load(InputStream in, String origin) {
    try {
      LocalCatalogFile file = MAPPER.readValue(in, LocalCatalogFile.class);
      // A null YAML document, such as "~" or a lone "---", is an empty catalog.
      return file == null ? new LocalCatalogFile(null, null) : file;
    } catch (IOException e) {
      throw new CatalogException("failed to parse local catalog file " + origin, e);
    } catch (IllegalArgumentException e) {
      throw new CatalogException("invalid local catalog file " + origin + ": " + e.getMessage(), e);
    }
  }

  private static InputStream open(String location) throws IOException {
    InputStream in;
    if (location.startsWith(CLASSPATH)) {
      // ClassLoader resources never start with "/", so "classpath:/x.yml" means "classpath:x.yml".
      String resource = location.substring(CLASSPATH.length()).replaceFirst("^/", "");
      in = LocalCatalogLoader.class.getClassLoader().getResourceAsStream(resource);
    } else {
      // "file:" is stripped, not parsed as a URI, so relative paths and %-escapes stay as written.
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
