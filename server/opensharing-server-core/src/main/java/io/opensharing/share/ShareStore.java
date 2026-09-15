package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.http.ApiException;
import io.opensharing.auth.Caller;
import io.opensharing.auth.Ownership;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Storage for shares. Name lookups are case-insensitive. */
@Service
@Transactional
public class ShareStore {

  private final ShareRepository shares;

  public ShareStore(ShareRepository shares) {
    this.shares = shares;
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
    share.setOwnerId(author.id());
    share.setCreatedBy(author.id());
    return shares.save(share);
  }

  /** Only non-null fields are applied. Only the owner may update the share. */
  public ShareEntity update(
      Caller caller,
      String name,
      String displayName,
      String comment,
      Map<String, String> properties) {
    ShareEntity share = requireOwned(name, caller);
    if (displayName != null) {
      share.setDisplayName(displayName);
    }
    if (comment != null) {
      share.setComment(comment);
    }
    if (properties != null) {
      share.setProperties(properties);
    }
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

  public void delete(String name, Caller caller) {
    ShareEntity share = requireOwned(name, caller);
    shares.delete(share);
  }
}
