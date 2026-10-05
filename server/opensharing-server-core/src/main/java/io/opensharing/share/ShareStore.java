package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.http.ApiException;
import io.opensharing.auth.UserContext;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for shares. Names are stored lowercase and looked up case-insensitively. Each method runs
 * in its own transaction, so returned entities are detached and must be fully loaded.
 */
@Service
@Transactional
public class ShareStore {

  private final ShareRepository shares;

  public ShareStore(ShareRepository shares) {
    this.shares = shares;
  }

  /**
   * Creates a share owned by {@code author}. {@code name} must already be validated and lowercase.
   * Fails with already-exists when the name is taken.
   */
  public ShareEntity create(
      UserContext author,
      String name,
      String displayName,
      String comment,
      Map<String, String> properties) {
    if (shares.existsByName(name)) {
      throw ApiException.alreadyExists("share '" + name + "' already exists");
    }
    ShareEntity share = new ShareEntity();
    share.setName(name);
    share.setDisplayName(displayName);
    share.setComment(comment);
    share.setProperties(properties);
    share.setOwnerId(author.userId());
    return shares.save(share);
  }

  /** Only non-null fields are applied. Only the owner may update the share. */
  public ShareEntity update(
      UserContext user,
      String name,
      String displayName,
      String comment,
      Map<String, String> properties) {
    ShareEntity share = requireOwned(name, user);
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

  /** Looks up a share by name in any case. */
  @Transactional(readOnly = true)
  public Optional<ShareEntity> find(String name) {
    return shares.findByName(ObjectNames.normalize(name));
  }

  /** Like {@link #find}, but fails with not-found when the share does not exist. */
  @Transactional(readOnly = true)
  public ShareEntity require(String name) {
    return find(name)
        .orElseThrow(() -> ApiException.notFound("share '" + name + "' does not exist"));
  }

  /** Like {@link #require}, but also fails unless {@code user} owns the share. */
  @Transactional(readOnly = true)
  public ShareEntity requireOwned(String name, UserContext user) {
    ShareEntity share = require(name);
    user.requireOwner(share.getOwnerId(), "share '" + share.getName() + "'");
    return share;
  }

  /** Lists all shares ordered by name, regardless of owner. */
  @Transactional(readOnly = true)
  public Page<ShareEntity> list(Pageable pageable) {
    return shares.findAllByOrderByNameAsc(pageable);
  }

  /** Deletes a share. Only the owner may delete it. */
  public void delete(String name, UserContext user) {
    ShareEntity share = requireOwned(name, user);
    shares.delete(share);
  }
}
