package io.opensharing;

import jakarta.persistence.AttributeConverter;

/**
 * Stores an enum as its name. The converter, rather than {@code @Enumerated(STRING)}, keeps the
 * column a plain {@code varchar} on every database: Hibernate's enum mapping adds a native enum
 * type or a check constraint on some dialects. JPA needs one {@code @Converter} subclass per enum.
 */
public abstract class EnumNameConverter<E extends Enum<E>>
    implements AttributeConverter<E, String> {

  private final Class<E> type;

  protected EnumNameConverter(Class<E> type) {
    this.type = type;
  }

  @Override
  public String convertToDatabaseColumn(E value) {
    return value == null ? null : value.name();
  }

  @Override
  public E convertToEntityAttribute(String value) {
    return value == null ? null : Enum.valueOf(type, value);
  }
}
