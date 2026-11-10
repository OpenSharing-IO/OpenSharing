package io.opensharing.persistence;

import io.opensharing.runtime.OpenSharing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.connections.internal.DriverManagerConnectionProviderImpl;
import org.hibernate.tool.schema.TargetType;
import org.hibernate.tool.schema.internal.HibernateSchemaManagementTool;
import org.hibernate.tool.schema.spi.ContributableMatcher;
import org.hibernate.tool.schema.spi.SchemaManagementTool;
import org.hibernate.tool.schema.spi.ScriptTargetOutput;
import org.hibernate.tool.schema.spi.TargetDescriptor;

/** Hibernate's schema update of a database, captured as the statements it would apply. */
final class HibernateUpdateStatements {

  private HibernateUpdateStatements() {}

  static List<String> of(String url, String username, String password) {
    StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                AvailableSettings.CONNECTION_PROVIDER, DriverManagerConnectionProviderImpl.class)
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", username)
            .applySetting("hibernate.connection.password", password)
            .build();
    MetadataSources sources = new MetadataSources(registry);
    OpenSharing.entities().forEach(sources::addAnnotatedClass);
    Metadata metadata = sources.buildMetadata();

    List<String> statements = new ArrayList<>();
    HibernateSchemaManagementTool tool =
        (HibernateSchemaManagementTool) registry.getService(SchemaManagementTool.class);
    tool.getSchemaMigrator(Map.of())
        .doMigration(
            metadata,
            executionOptions(),
            ContributableMatcher.ALL,
            new TargetDescriptor() {
              @Override
              public java.util.EnumSet<TargetType> getTargetTypes() {
                return java.util.EnumSet.of(TargetType.SCRIPT);
              }

              @Override
              public ScriptTargetOutput getScriptTargetOutput() {
                return new ScriptTargetOutput() {
                  @Override
                  public void prepare() {}

                  @Override
                  public void accept(String command) {
                    statements.add(command);
                  }

                  @Override
                  public void release() {}
                };
              }
            });
    StandardServiceRegistryBuilder.destroy(registry);
    return statements;
  }

  private static org.hibernate.tool.schema.spi.ExecutionOptions executionOptions() {
    return new org.hibernate.tool.schema.spi.ExecutionOptions() {
      @Override
      public Map<String, Object> getConfigurationValues() {
        return Map.of();
      }

      @Override
      public boolean shouldManageNamespaces() {
        return false;
      }

      @Override
      public org.hibernate.tool.schema.spi.ExceptionHandler getExceptionHandler() {
        return failure -> {
          throw new IllegalStateException("Hibernate update failed", failure);
        };
      }
    };
  }
}
