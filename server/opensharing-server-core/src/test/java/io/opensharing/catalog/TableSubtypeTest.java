package io.opensharing.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TableSubtypeTest {

  @Test
  void parsesSupportedSubtypesInAnyCase() {
    assertEquals(TableSubtype.MANAGED, TableSubtype.parse("managed"));
    assertEquals(TableSubtype.EXTERNAL, TableSubtype.parse(" EXTERNAL "));
    assertNull(TableSubtype.parse(null));
  }

  @Test
  void rejectsSubtypesItCannotShare() {
    assertThrows(IllegalArgumentException.class, () -> TableSubtype.parse("VIEW"));
  }
}
