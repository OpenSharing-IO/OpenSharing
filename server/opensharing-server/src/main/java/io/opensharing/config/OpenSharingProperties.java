package io.opensharing.config;

/** OpenSharing server configuration, the {@code opensharing} key of {@link ServerSettings}. */
public class OpenSharingProperties {

  private final Catalog catalog = new Catalog();

  public Catalog getCatalog() {
    return catalog;
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
