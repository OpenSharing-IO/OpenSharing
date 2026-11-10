package io.opensharing.recipient;

import io.opensharing.EnumNameConverter;
import jakarta.persistence.Converter;

/** Stores {@link AuthenticationType} as its name. */
@Converter
public class AuthenticationTypeConverter extends EnumNameConverter<AuthenticationType> {

  public AuthenticationTypeConverter() {
    super(AuthenticationType.class);
  }
}
