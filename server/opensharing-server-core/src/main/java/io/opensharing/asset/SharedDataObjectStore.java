package io.opensharing.asset;

import io.opensharing.ObjectNames;
import io.opensharing.catalog.AssetType;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for the objects shared into each share. Alias lookups are case-insensitive, as the protocol
 * requires. Recipient-facing reads see only {@link SharedObjectStatus#ACTIVE} objects; the admin-facing
 * ones see everything.
 */
@Service
@Transactional
public class SharedDataObjectStore {

  private final SharedDataObjectRepository objects;

  public SharedDataObjectStore(SharedDataObjectRepository objects) {
    this.objects = objects;
  }

  public SharedDataObjectEntity save(SharedDataObjectEntity object) {
    return objects.save(object);
  }

  @Transactional(readOnly = true)
  public boolean existsSharedAs(ShareEntity share, String schemaName, String name) {
    return objects.existsByShareAndSharedAsSchemaLowerAndSharedAsNameLower(
        share, ObjectNames.normalize(schemaName), ObjectNames.normalize(name));
  }

  @Transactional(readOnly = true)
  public boolean existsSource(ShareEntity share, String catalogName) {
    return objects.existsByShareAndNameLower(share, ObjectNames.normalize(catalogName));
  }

  @Transactional(readOnly = true)
  public Optional<SharedDataObjectEntity> find(ShareEntity share, String schemaName, String name) {
    return objects.findByShareAndSharedAsSchemaLowerAndSharedAsNameLower(
        share, ObjectNames.normalize(schemaName), ObjectNames.normalize(name));
  }

  /** Looks up an object by the catalog name it was added under. */
  @Transactional(readOnly = true)
  public Optional<SharedDataObjectEntity> findSource(ShareEntity share, String catalogName) {
    return objects.findByShareAndNameLower(share, ObjectNames.normalize(catalogName));
  }

  /** An object a recipient may be served, or empty if there is none it may. */
  @Transactional(readOnly = true)
  public Optional<SharedDataObjectEntity> findActive(
      ShareEntity share, String schemaName, String name, AssetType type) {
    return objects.findByShareAndSharedAsSchemaLowerAndSharedAsNameLowerAndTypeAndStatus(
        share,
        ObjectNames.normalize(schemaName),
        ObjectNames.normalize(name),
        type,
        SharedObjectStatus.ACTIVE);
  }

  /** The grant that shares a whole schema under this alias, if a provider added one. */
  @Transactional(readOnly = true)
  public Optional<SharedDataObjectEntity> findSchemaGrant(ShareEntity share, String schemaName) {
    return objects.findByShareAndSharedAsSchemaLowerAndType(
        share, ObjectNames.normalize(schemaName), AssetType.SCHEMA);
  }

  @Transactional(readOnly = true)
  public Optional<SharedDataObjectEntity> findActiveSchemaGrant(
      ShareEntity share, String schemaName) {
    return objects.findByShareAndSharedAsSchemaLowerAndTypeAndStatus(
        share, ObjectNames.normalize(schemaName), AssetType.SCHEMA, SharedObjectStatus.ACTIVE);
  }

  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> listActiveSchemaGrants(ShareEntity share) {
    return objects.findByShareAndTypeAndStatusOrderBySharedAsSchemaLowerAsc(
        share, AssetType.SCHEMA, SharedObjectStatus.ACTIVE);
  }

  @Transactional(readOnly = true)
  public Page<SharedDataObjectEntity> list(ShareEntity share, AssetType type, Pageable pageable) {
    return objects.findByShareAndTypeAndStatusOrderBySharedAsSchemaLowerAscSharedAsNameLowerAsc(
        share, type, SharedObjectStatus.ACTIVE, pageable);
  }

  @Transactional(readOnly = true)
  public Page<SharedDataObjectEntity> list(
      ShareEntity share, String schemaName, AssetType type, Pageable pageable) {
    requireSchema(share, schemaName);
    return objects.findByShareAndSharedAsSchemaLowerAndTypeAndStatusOrderBySharedAsNameLowerAsc(
        share, ObjectNames.normalize(schemaName), type, SharedObjectStatus.ACTIVE, pageable);
  }

  /** Unpaged, for merging with a shared schema's contents before the result can be paged. */
  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> listTables(ShareEntity share) {
    return objects.findByShareAndTypeAndStatusOrderBySharedAsSchemaLowerAscSharedAsNameLowerAsc(
        share, AssetType.TABLE, SharedObjectStatus.ACTIVE);
  }

  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> listTablesInSchema(ShareEntity share, String schemaName) {
    return objects.findByShareAndSharedAsSchemaLowerAndTypeAndStatusOrderBySharedAsNameLowerAsc(
        share, ObjectNames.normalize(schemaName), AssetType.TABLE, SharedObjectStatus.ACTIVE);
  }

  /** Every object in a share, whatever its status, in the order the admin API reports them. */
  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> listAll(ShareEntity share) {
    return objects.findByShareOrderBySharedAsSchemaLowerAscSharedAsNameLowerAsc(share);
  }

  @Transactional(readOnly = true)
  public Page<String> listSchemaNames(ShareEntity share, Pageable pageable) {
    return objects.findSchemaNames(share, SharedObjectStatus.ACTIVE, pageable);
  }

  /** Schemas exist only as long as an object is shared under them. */
  @Transactional(readOnly = true)
  public void requireSchema(ShareEntity share, String schemaName) {
    if (!objects.existsByShareAndSharedAsSchemaLowerAndStatus(
        share, ObjectNames.normalize(schemaName), SharedObjectStatus.ACTIVE)) {
      throw ApiException.notFound(
          "schema '" + share.getName() + "." + schemaName + "' does not exist");
    }
  }

  public void delete(SharedDataObjectEntity object) {
    objects.delete(object);
  }

  public void deleteAllIn(ShareEntity share) {
    objects.deleteByShare(share);
  }
}
