package io.opensharing.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenSharing server configuration. */
@ConfigurationProperties(prefix = "opensharing")
public class OpenSharingProperties {

  private final Hosting hosting = new Hosting();
  private final Provider provider = new Provider();
  private final Admin admin = new Admin();
  private final Catalog catalog = new Catalog();

  public Hosting getHosting() {
    return hosting;
  }

  public Provider getProvider() {
    return provider;
  }

  public Admin getAdmin() {
    return admin;
  }

  public Catalog getCatalog() {
    return catalog;
  }

  /**
   * A url prefix without its trailing slash, kept that way here so that everything appending to
   * one — a filter's url pattern, an OpenAPI path match, a route the server builds — appends to a
   * known shape instead of each trimming first.
   */
  private static String prefix(String value) {
    return value != null && value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1)
        : value;
  }

  public static class Hosting {

    public enum Mode {
      STANDALONE,
      EMBEDDED
    }

    private Mode mode = Mode.STANDALONE;

    public Mode getMode() {
      return mode;
    }

    public void setMode(Mode mode) {
      this.mode = mode;
    }
  }

  /** Provider-admin HTTP surface. */
  public static class Provider {

    private String basePath = "/api/1.0/opensharing/provider";

    public String getBasePath() {
      return basePath;
    }

    public void setBasePath(String basePath) {
      this.basePath = prefix(basePath);
    }
  }

  /**
   * Standalone identity for provider-admin calls when the catalog has no {@code authorize}
   * implementation. Embedded hosts register their own {@code ProviderIdentityResolver} instead.
   */
  public static class Admin {

    private final List<Principal> principals = new ArrayList<>();

    public List<Principal> getPrincipals() {
      return principals;
    }

    public static class Principal {

      private String name;
      private String bearerToken;

      public String getName() {
        return name;
      }

      public void setName(String name) {
        this.name = name;
      }

      public String getBearerToken() {
        return bearerToken;
      }

      public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
      }
    }
  }

  public static class Catalog {

    private String type;
    private final Local local = new Local();

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public Local getLocal() {
      return local;
    }

    public static class Local {

      private String file;

      public String getFile() {
        return file;
      }

      public void setFile(String file) {
        this.file = file;
      }
    }
  }
}
