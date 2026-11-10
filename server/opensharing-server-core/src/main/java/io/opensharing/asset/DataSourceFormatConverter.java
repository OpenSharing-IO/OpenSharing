package io.opensharing.asset;

import io.opensharing.EnumNameConverter;
import io.opensharing.catalog.DataSourceFormat;
import jakarta.persistence.Converter;

/** Stores {@link DataSourceFormat} as its name. */
@Converter
public class DataSourceFormatConverter extends EnumNameConverter<DataSourceFormat> {

  public DataSourceFormatConverter() {
    super(DataSourceFormat.class);
  }
}
