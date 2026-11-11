package io.opensharing.share;

import io.opensharing.EnumNameConverter;
import jakarta.persistence.Converter;

/** Stores {@link SharePrivilege} as its name. */
@Converter
public class SharePrivilegeConverter extends EnumNameConverter<SharePrivilege> {

  public SharePrivilegeConverter() {
    super(SharePrivilege.class);
  }
}
