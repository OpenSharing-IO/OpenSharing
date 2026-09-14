package io.opensharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ObjectNamesTest {

  @Test
  void acceptsOrdinaryNames() {
    assertEquals("vaccine_share", ObjectNames.validateShareName("vaccine_share"));
    assertEquals("acme-data", ObjectNames.validateSchemaName("acme-data"));
    assertEquals("orders", ObjectNames.validateAssetName("orders"));
  }

  @Test
  void rejectsRestrictedCharacters() {
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateShareName("my share"));
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateShareName("a/b"));
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateShareName("a\u0001b"));
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateShareName("a\u007fb"));
  }

  @Test
  void rejectsPeriodOnlyForSchemasAndAssets() {
    assertEquals("share.with.dots", ObjectNames.validateShareName("share.with.dots"));
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateSchemaName("a.b"));
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateAssetName("a.b"));
  }

  @Test
  void rejectsEmptyAndOverlongNames() {
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateShareName(""));
    assertThrows(IllegalArgumentException.class, () -> ObjectNames.validateShareName(null));
    assertThrows(
        IllegalArgumentException.class, () -> ObjectNames.validateShareName("x".repeat(256)));
  }

  @Test
  void normalizesForCaseInsensitiveComparison() {
    assertEquals("vaccine_share", ObjectNames.normalize("Vaccine_Share"));
  }
}
