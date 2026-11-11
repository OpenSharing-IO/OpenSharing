package io.opensharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EnumNameConverterTest {

  private enum Color {
    RED
  }

  private static final class ColorConverter extends EnumNameConverter<Color> {
    ColorConverter() {
      super(Color.class);
    }
  }

  private final ColorConverter converter = new ColorConverter();

  @Test
  void roundTripsNamesAndNulls() {
    assertEquals("RED", converter.convertToDatabaseColumn(Color.RED));
    assertEquals(Color.RED, converter.convertToEntityAttribute("RED"));
    assertNull(converter.convertToDatabaseColumn(null));
    assertNull(converter.convertToEntityAttribute(null));
  }

  @Test
  void namesTheEnumAndValueWhenTheStoredNameIsUnknown() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> converter.convertToEntityAttribute("BLUE"));
    assertEquals("Unrecognized Color value in database: 'BLUE'", e.getMessage());
  }
}
