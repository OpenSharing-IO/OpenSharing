package io.opensharing.recipient;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to recipient rows. Names are stored lowercase and looked up case-insensitively. */
public interface RecipientRepository extends JpaRepository<RecipientEntity, String> {

  /** {@code SELECT * FROM os_recipients WHERE name = ?} */
  Optional<RecipientEntity> findByName(String name);

  /** {@code SELECT 1 FROM os_recipients WHERE name = ? LIMIT 1} */
  boolean existsByName(String name);

  /**
   * {@code SELECT * FROM os_recipients ORDER BY name ASC LIMIT ? OFFSET ?}, plus a {@code COUNT(*)}
   * for the total when paged.
   */
  Page<RecipientEntity> findAllByOrderByNameAsc(Pageable pageable);
}
