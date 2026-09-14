package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.http.ApiException;
import io.opensharing.principal.Caller;
import io.opensharing.principal.Ownership;
import io.opensharing.recipient.RecipientEntity;
import io.opensharing.asset.SharedDataObjectStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for shares and for the permissions that expose them to recipients. Name lookups are
 * case-insensitive, as the protocol requires. Every write records the principal behind it.
 */
@Service
@Transactional
public class ShareStore {

  private final ShareRepository shares;
  private final SharePermissionRepository permissions;
  private final SharedDataObjectStore objects;

  public ShareStore(
      ShareRepository shares,
      SharePermissionRepository permissions,
      SharedDataObjectStore objects) {
    this.shares = shares;
    this.permissions = permissions;
    this.objects = objects;
  }

  public ShareEntity create(
      Caller author,
      String name,
      String displayName,
      String comment,
      Map<String, String> properties) {
    ObjectNames.validateShareName(name);
    if (shares.existsByNameLower(ObjectNames.normalize(name))) {
      throw ApiException.alreadyExists("share '" + name + "' already exists");
    }
    ShareEntity share = new ShareEntity();
    share.setName(name);
    share.setDisplayName(displayName);
    share.setComment(comment);
    share.setProperties(properties);
    share.setOwnerId(author.principalId());
    share.setCreatedBy(author.principalId());
    share.setUpdatedBy(author.principalId());
    return shares.save(share);
  }

  /** Only non-null fields are applied. */
  public ShareEntity update(
      Caller author,
      ShareEntity share,
      String displayName,
      String comment,
      Map<String, String> properties) {
    if (displayName != null) {
      share.setDisplayName(displayName);
    }
    if (comment != null) {
      share.setComment(comment);
    }
    if (properties != null) {
      share.setProperties(properties);
    }
    share.setUpdatedBy(author.principalId());
    return shares.save(share);
  }

  @Transactional(readOnly = true)
  public Optional<ShareEntity> find(String name) {
    return shares.findByNameLower(ObjectNames.normalize(name));
  }

  @Transactional(readOnly = true)
  public ShareEntity require(String name) {
    return find(name)
        .orElseThrow(() -> ApiException.notFound("share '" + name + "' does not exist"));
  }

  /** Loads a share to be changed, which only its owner may do. */
  @Transactional(readOnly = true)
  public ShareEntity requireOwned(String name, Caller caller) {
    ShareEntity share = require(name);
    Ownership.requireOwner(share.getOwnerId(), caller, "share '" + share.getName() + "'");
    return share;
  }

  @Transactional(readOnly = true)
  public Page<ShareEntity> list(Pageable pageable) {
    return shares.findAllByOrderByNameLowerAsc(pageable);
  }

  /** Deleting a share takes its shared objects and its permissions with it. */
  public void delete(String name, Caller caller) {
    ShareEntity share = requireOwned(name, caller);
    objects.deleteAllIn(share);
    permissions.deleteByShare(share);
    shares.delete(share);
  }

  /** Granting a privilege the recipient already holds leaves the original grant untouched. */
  public SharePermissionEntity grant(
      Caller author, ShareEntity share, RecipientEntity recipient, SharePrivilege privilege) {
    return permissions
        .findByShareAndRecipientAndPrivilege(share, recipient, privilege)
        .orElseGet(
            () -> {
              SharePermissionEntity permission = new SharePermissionEntity();
              permission.setShare(share);
              permission.setRecipient(recipient);
              permission.setPrivilege(privilege);
              permission.setGrantedBy(author.principalId());
              return permissions.save(permission);
            });
  }

  public void revoke(ShareEntity share, RecipientEntity recipient, SharePrivilege privilege) {
    permissions
        .findByShareAndRecipientAndPrivilege(share, recipient, privilege)
        .ifPresentOrElse(
            permissions::delete,
            () -> {
              throw ApiException.notFound(
                  "recipient '"
                      + recipient.getName()
                      + "' does not hold "
                      + privilege
                      + " on share '"
                      + share.getName()
                      + "'");
            });
  }

  /** Called when a recipient is deleted, so no permission outlives the recipient it was made to. */
  public void revokeAll(RecipientEntity recipient) {
    permissions.deleteByRecipient(recipient);
  }

  @Transactional(readOnly = true)
  public List<SharePermissionEntity> listPermissions(ShareEntity share) {
    return permissions.findByShareOrderByRecipientNameLowerAsc(share);
  }

  @Transactional(readOnly = true)
  public List<SharePermissionEntity> listPermissionsOf(RecipientEntity recipient) {
    return permissions.findByRecipientOrderByShareNameLowerAsc(recipient);
  }

  @Transactional(readOnly = true)
  public Page<ShareEntity> listGrantedTo(RecipientEntity recipient, Pageable pageable) {
    return permissions.findSharesForRecipient(recipient, pageable);
  }

  /** Whether the recipient may read the share at all. */
  @Transactional(readOnly = true)
  public boolean isSharedWith(ShareEntity share, RecipientEntity recipient) {
    return permissions.existsByShareAndRecipientAndPrivilege(
        share, recipient, SharePrivilege.SELECT);
  }
}
