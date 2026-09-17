package io.opensharing.recipient;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to recipient rows. Names are stored lowercase and looked up case-insensitively. */
public interface RecipientRepository extends JpaRepository<RecipientEntity, String> {

  Optional<RecipientEntity> findByName(String name);

  boolean existsByName(String name);

  Page<RecipientEntity> findAllByOrderByNameAsc(Pageable pageable);
}
