package io.opensharing.config;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientTokenSettings;
import io.opensharing.runtime.OpenSharing;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Builds the standalone server's {@link OpenSharing} from its Spring beans. */
@Configuration
public class OpenSharingConfiguration {

  @Bean
  public OpenSharing openSharing(
      CatalogConnector catalog,
      PlatformTransactionManager transactionManager,
      EntityManagerFactory entityManagerFactory,
      OpenSharingProperties properties) {
    return OpenSharing.builder()
        .catalog(catalog)
        .transactions(new SpringTransactions(transactionManager, entityManagerFactory))
        .recipientTokens(
            new RecipientTokenSettings(
                properties.getRecipientTokens().getDefaultTtl(),
                properties.getRecipientTokens().getRotationGrace()))
        .pagination(
            new Pagination(
                properties.getPagination().getDefaultMaxResults(),
                properties.getPagination().getMaxMaxResults()))
        .build();
  }
}
