package io.opensharing.share;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA access to share rows. Names are stored lowercase and looked up case-insensitively. Each
 * loaded share also runs {@code SELECT * FROM os_share_properties WHERE share_id = ?}.
 */
public interface ShareRepository extends JpaRepository<ShareEntity, String> {

  /** {@code SELECT * FROM os_shares WHERE name = ?} */
  Optional<ShareEntity> findByName(String name);

  /** {@code SELECT 1 FROM os_shares WHERE name = ? LIMIT 1} */
  boolean existsByName(String name);

  /**
   * {@code SELECT * FROM os_shares ORDER BY name ASC LIMIT ? OFFSET ?}, plus a {@code COUNT(*)}
   * for the total when paged.
   */
  Page<ShareEntity> findAllByOrderByNameAsc(Pageable pageable);
}
