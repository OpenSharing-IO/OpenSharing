package io.opensharing.asset;

import io.opensharing.EnumNameConverter;
import io.opensharing.catalog.AssetType;
import jakarta.persistence.Converter;

/** Stores {@link AssetType} as its name. */
@Converter
public class AssetTypeConverter extends EnumNameConverter<AssetType> {

  public AssetTypeConverter() {
    super(AssetType.class);
  }
}
