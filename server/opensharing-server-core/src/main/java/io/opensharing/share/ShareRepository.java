package io.opensharing.share;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to share rows. Names are stored lowercase and looked up case-insensitively. */
public interface ShareRepository extends JpaRepository<ShareEntity, String> {

  Optional<ShareEntity> findByName(String name);

  boolean existsByName(String name);

  Page<ShareEntity> findAllByOrderByNameAsc(Pageable pageable);
}
