package io.opensharing.share;

import io.opensharing.http.ApiException;
import io.opensharing.recipient.RecipientEntity;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Storage for privileges recipients hold on shares. */
@Service
@Transactional
public class SharePermissionStore {

  private final SharePermissionRepository permissions;

  public SharePermissionStore(SharePermissionRepository permissions) {
    this.permissions = permissions;
  }

  /** Granting a privilege the recipient already holds leaves the original row untouched. */
  public SharePermissionEntity grant(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege) {
    return permissions
        .findByShareAndRecipientAndPrivilege(share, recipient, privilege)
        .orElseGet(
            () -> {
              SharePermissionEntity permission = new SharePermissionEntity();
              permission.setShare(share);
              permission.setRecipient(recipient);
              permission.setPrivilege(privilege);
              return permissions.save(permission);
            });
  }

  public void revoke(ShareEntity share, RecipientEntity recipient, SharePrivilege privilege) {
    SharePermissionEntity permission =
        permissions
            .findByShareAndRecipientAndPrivilege(share, recipient, privilege)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "recipient '"
                            + recipient.getName()
                            + "' does not hold "
                            + privilege
                            + " on share '"
                            + share.getName()
                            + "'"));
    permissions.delete(permission);
  }

  @Transactional(readOnly = true)
  public List<SharePermissionEntity> list(ShareEntity share) {
    return permissions.findByShareOrderByRecipient_NameAsc(share);
  }
}
