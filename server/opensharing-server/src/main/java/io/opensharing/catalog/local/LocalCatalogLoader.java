package io.opensharing.catalog.local;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.opensharing.catalog.CatalogException;
import java.io.IOException;
import java.io.InputStream;

/** Reads a {@link LocalCatalogFile} from YAML or JSON. */
public final class LocalCatalogLoader {

  /** Unknown keys are rejected so a typo in a catalog file surfaces at startup. */
  private static final ObjectMapper MAPPER =
      YAMLMapper.builder()
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
          .build();

  private LocalCatalogLoader() {}

  public static LocalCatalogFile load(InputStream in, String origin) {
    try {
      LocalCatalogFile file = MAPPER.readValue(in, LocalCatalogFile.class);
      return file == null ? new LocalCatalogFile(null, null) : file;
    } catch (IOException e) {
      throw new CatalogException("failed to parse local catalog file " + origin, e);
    } catch (IllegalArgumentException e) {
      throw new CatalogException("invalid local catalog file " + origin + ": " + e.getMessage(), e);
    }
  }
}
