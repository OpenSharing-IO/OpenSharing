# Embedding OpenSharing

OpenSharing can run as a Spring application inside a host process. The host owns its catalog
integration and passes a `CatalogConnector` directly to `OpenSharing.embedded()`:

```java
AutoCloseable context =
    OpenSharing.embedded()
        .catalog(catalogConnector)
        .identityResolver(new CatalogAuthorizingIdentityResolver(catalogConnector))
        .property("server.port", 8099)
        .property("server.address", "127.0.0.1")
        .run();
```

The host is responsible for constructing a thread-safe connector and for closing the returned
context during shutdown. `CatalogCaller.Credential.OnBehalfOf` is a trusted in-process identity:
hosts may resolve it directly to the stored catalog user ID without adding an HTTP server secret.

## Unity Catalog OSS

Unity Catalog integration is embedded-only and is implemented in the Unity Catalog repository.
Its connector calls UC repositories, token validation, authorization evaluation, and storage
credential vending as local Java services. OpenSharing does not ship a Unity REST client and does
not make loopback catalog requests.

Unity Catalog still runs OpenSharing's protocol server on an internal loopback port and routes the
public OpenSharing paths through UC's public port. This routing concerns client protocol traffic
only; catalog and identity operations remain in-process.

## Standalone mode

The reference server supports the file-backed `local` connector:

```shell
java -jar opensharing-server-0.1.0-SNAPSHOT-exec.jar \
  --opensharing.catalog.type=local \
  --opensharing.catalog.local.file=file:./local-catalog.yml
```

To integrate another catalog, embed OpenSharing in the catalog host and provide a connector rather
than adding catalog-specific HTTP configuration to the reference server.
