# Embedding OpenSharing in a host

OpenSharing can run as a **standalone** Spring Boot server or be **embedded** inside another
process such as [Unity Catalog OSS](https://www.unitycatalog.io/). Embedded mode exists so operators
do not run two services, duplicate provider configuration, or round-trip catalog calls over HTTP when
the catalog is already in the same JVM.

## Maven artifacts

| Artifact | Use |
|----------|-----|
| `io.opensharing:opensharing-server-core` | Library for UC embed — `OpenSharing.embedded()`, protocol serving, JPA metadata store |
| `io.opensharing:opensharing-server` | Runnable distribution (`-exec` classifier is the fat jar) |
| `io.opensharing:opensharing-server:exec` | Same as `opensharing-server-*-exec.jar` for `java -jar` |

The `local` file-based connector is standalone-only and lives in `opensharing-server`. The `unity`
HTTP connector (`io.opensharing.catalog.unity.UnityCatalogConnector`) lives in **core**, because
embedded mode reuses it too — see "Embedded in Unity Catalog OSS" below.

## Publish locally for UC testing

From `server/`:

```bash
mvn install
# On machines that cannot reach Maven Central directly:
mvn -s .mvn/local-mirror-settings.xml install
```

This builds at release 21 (this pom's `maven.compiler.release`) — the language level OpenSharing's
own code uses, most visibly pattern-matching over `CatalogCaller.Credential` in a switch. Unity
Catalog's `server-sharing` module, which depends on the jar this installs, compiles at `--release
21` for exactly that reason (see `unitycatalog/build.sbt`'s `serverSharing` project) rather than the
`--release 17` most of that build otherwise uses — `javac --release N` refuses a classpath jar built
for anything newer than `N` outright (`class file has wrong version`), not merely one whose syntax
it declines to use, so `server-sharing` needs at least as new a release as this jar's.

This installs into `~/.m2/repository`:

```
io/opensharing/opensharing-server-core/0.1.0-SNAPSHOT/opensharing-server-core-0.1.0-SNAPSHOT.jar
io/opensharing/opensharing-server/0.1.0-SNAPSHOT/opensharing-server-0.1.0-SNAPSHOT-exec.jar
```

UC depends on **core only** (plain jar, not `-exec`), from its own `server-sharing` project, which
in turn `server-embedded` depends on for a runtime classpath (see `unitycatalog/build.sbt`):

```scala
// unitycatalog/build.sbt — serverSharing project libraryDependencies
"io.opensharing" % "opensharing-server-core" % "0.1.0-SNAPSHOT"
```

Unity Catalog already adds `Resolver.mavenLocal`, so `mvn install` in OpenSharing is enough before
`publishLocal` / `sbt serverSharing/compile` in UC.

After changing OpenSharing, reinstall and rebuild UC:

```bash
cd ~/OpenSharing/server && mvn install -DskipTests
cd ~/unitycatalog && sbt "serverSharing/compile" "serverEmbedded/compile"
```

## Modes

| Mode | How to start | Catalog | Provider identity |
|------|----------------|---------|-------------------|
| `standalone` | `OpenSharing.runStandalone(args)` or `OpenSharingServer.main` | `opensharing.catalog.*` (`local` file or `unity` HTTP) | `local`: `opensharing.admin.principals`, held in memory only. `unity`: the catalog itself, asked fresh on every request — see "Provider identity" below |
| `embedded` | `OpenSharing.embedded()...run(args)` or Spring with `opensharing.hosting.mode=embedded` | Host registers a `CatalogConnector` bean (required) | Host registers a `ProviderIdentityResolver` bean (required) |

Set `opensharing.hosting.mode` in configuration (`standalone` by default). Neither mode ever
persists a provider's bearer token, encrypted or otherwise, or keeps a principal table of its own
— see "Provider identity: no stored credential, ever" below.

## Standalone (current demo)

```bash
# Unity Catalog on :8080, OpenSharing on :8099 — two processes
java -jar opensharing-server-0.1.0-SNAPSHOT-exec.jar \
  --opensharing.hosting.mode=standalone \
  --opensharing.catalog.type=unity \
  --opensharing.catalog.unity.uri=http://localhost:8080/api/2.1/unity-catalog
```

No `opensharing.admin.principals` to configure for `catalog.type=unity`: a provider-admin request
just presents whatever bearer token their catalog already issued them (a real UC token, or, in a
demo with `server.authorization=disable`, any JWT-shaped one), and OpenSharing asks the catalog's
own `GET /opensharing/authorize` whose it is, fresh, on every request. `opensharing.admin.principals`
is still how `catalog.type=local` works — the file catalog has no identity provider to delegate to.

Or from source:

```bash
cd server
mvn -pl opensharing-server spring-boot:run -Dspring-boot.run.arguments="--server.port=8099 ..."
```

## Embedded in Unity Catalog OSS

The host supplies two integration points, both required:

1. **`CatalogConnector`** — for UC, this is the *same* `UnityCatalogConnector` standalone mode
   uses, pointed at UC's own Armeria server on `127.0.0.1:<armeriaPort>` instead of a remote URL.
2. **`ProviderIdentityResolver`** — the *same* `CatalogAuthorizingIdentityResolver` standalone
   mode's `catalog.type=unity` uses, wrapped around that one `CatalogConnector` rather than opening
   a second connection to the same address: it asks the connector's own
   `CatalogConnector#authorize` whose bearer token a provider-admin request carries, by delegating
   to `UnityCatalogConnector`'s implementation of it (`GET /opensharing/authorize`). There is no
   fallback to configured principals in embedded mode: nothing here is stored anywhere, so there is
   nothing to fall back to.

```java
URI ucLoopback = URI.create("http://127.0.0.1:" + armeriaPort + "/api/2.1/unity-catalog");
UnityCatalogConnector connector =
    new UnityCatalogConnector(ucLoopback, connectTimeout, requestTimeout, serverSecret);
OpenSharing.embedded()
    .catalog(connector)
    .identityResolver(new CatalogAuthorizingIdentityResolver(connector))
    .property("opensharing.protocol-prefix", "/api/2.1/opensharing")
    .run();
```

Both are real HTTP calls, not a direct repository read, and that is deliberate: UC enforces its own
grants (metastore / catalog / schema / table privileges) in a decorator wrapped around Armeria's
HTTP dispatch (`UnityAccessDecorator`), not inside the repositories or service methods themselves —
a repository call, or even a plain Java call into a UC service class, bypasses every grant check UC
has. Going through the real endpoints, on the loopback address UC itself is reached on, is what
lets embedding reuse UC's authorization instead of reimplementing it. See "Provider identity: no
stored credential, ever" below.

Recipient protocol endpoints, share metadata (JPA), credential vending, and Delta/Iceberg serving
stay in OpenSharing. Only catalog access and provider identity/authorization are delegated to the
host.

## Provider identity: no stored credential, ever

OpenSharing keeps no principal table and stores no provider's bearer token, at rest or otherwise —
not encrypted, not hashed for later re-presentation, nothing. Every provider identity is either
resolved fresh from the catalog on the request that needs it, or, for the file-backed `local`
catalog (which has no identity provider to ask), read from a fixed, in-memory list built once from
`opensharing.admin.principals`.

For `unity` — standalone against a remote catalog, or embedded against a loopback one, same code
either way — two calls carry the whole story, both real HTTP requests through UC's normal decorator
chain exactly as if they'd arrived on UC's public port:

- **A provider-admin write** (creating a share, adding a table, granting a permission, ...)
  presents the live caller's own bearer token to UC's `GET /opensharing/authorize`, which returns
  `{user_id, user_name, authorized}`. `CREATE_SHARE` / `CREATE_RECIPIENT` are asked only for the two
  operations that create a new, otherwise-unowned object; every other request resolves identity
  only, with OpenSharing enforcing its own ownership rule (only a share's or recipient's owner may
  change it) rather than asking UC to decide that again on every call. Only `user_id` is ever
  stored, as the object's owner — never the token.
- **A recipient's read** (resolving a table, listing a shared schema, minting storage credentials)
  has no owner token to present — the owner isn't the one asking, and never will be for this
  request. `UnityCatalogConnector` presents its own configured identity instead
  (`server.opensharing.server-secret` / `opensharing.catalog.unity.server-secret`) alongside the
  owner's stored `user_id`, and UC's `AuthDecorator` evaluates the request as that user without
  their token ever existing on the wire. A principal with no grant on the asset gets UC's own `403
  PERMISSION_DENIED` either way, which the connector turns into `AssetAccessDeniedException` — the
  same failure reported when the remote catalog refuses a request, translated by the same,
  already-tested code (`UnityCatalogConnectorTest`).

Nothing about embedding weakens this: there is no second, simplified authorization model to keep in
sync with UC's, and a grant revoked in UC is enforced on the very next call, since there is no
separately stored credential that could still work after it.

## One process, one address

Embedding does not mean sharing a port automatically — UC's own gRPC-JSON transcoding server
(Armeria) and OpenSharing's embedded Tomcat are unrelated server engines that each bind their own
socket, and a TCP port can only have one listener regardless of how many frameworks share a JVM.
What makes it feel like one server to a client is UC's own public-facing listener (the Vert.x
`URLTranscoderVerticle` that already sits in front of Armeria) forwarding a request to whichever
backend its path belongs to:

```
client → UC's public port (e.g. 8080)
              │
              ├─ path under opensharing.protocol-prefix (and the /provider, /activation paths
              │  derived from it) → embedded OpenSharing, on its own port, bound to 127.0.0.1
              │
              └─ everything else
                     → UC's own Armeria server, on port+1
```

OpenSharing's own port is real (something has to bind it) but private: bound to `127.0.0.1` only,
never advertised, reached solely by that one path-based forward on the same host. A client — and a
recipient's activation URL / `config.share` — only ever sees UC's own public address, because
`OpenSharingLifecycle` sets `opensharing.activation.external-base-url` to that address (derived
from UC's own public port, not a value configured separately) rather than to OpenSharing's internal
port. Standalone OpenSharing (no UC in the loop) is unaffected: this routing is UC's own addition
to its already-existing transcoder, not a change to OpenSharing itself.

## No datasource config of its own — it reads the host's

OpenSharing's metadata (`os_shares`, `os_shared_data_objects`, `os_recipients`,
`os_recipient_tokens`, `os_share_permissions`, all prefixed `os_` so they never collide with a
host's own tables) is stored via its own JPA/Hibernate model, independent of the host's schema. In
embedded mode there is no `server.opensharing.datasource.url` (or equivalent) to set: the host is
expected to hand OpenSharing whichever JDBC connection it already uses for its own metadata —
`spring.datasource.url`/`username`/`password`/`driver-class-name` — and both sides' tables land in
one physical database. For UC, `OpenSharingLifecycle` reads this straight out of the
`HibernateConfigurator` UC already built for itself (`hibernate.connection.url` →
`spring.datasource.url`, etc.), so there is nothing for an operator to duplicate or keep in sync;
whatever database UC points at (H2 file, Postgres, MySQL) is what OpenSharing follows.

This is safe within a single JVM: H2 keeps one shared in-memory `Database` instance per canonical
file path per process, and a real database server is designed for exactly this kind of sharing.

Two things worth knowing about it:

- **Matching credentials, before the first connect.** H2 creates its database's admin user from
  whichever username/password the *first* connection presents. If the host's Hibernate config
  doesn't set `hibernate.connection.username`/`password` explicitly (UC's own demo config doesn't),
  that first connection uses an empty username — so the mapping has to carry that through as an
  explicit empty string, not silently fall back to OpenSharing's own `application.yml` default of
  `sa`, or the second connection pool fails with `Wrong user name or password`.
- **Two connection pools, not one transaction.** This shares a database file, not a Hibernate
  `SessionFactory`/`EntityManagerFactory` or a JDBC connection. A single logical operation that
  touches both the host's tables and OpenSharing's (e.g. resolving a table while creating a share)
  still runs as two independent local transactions — there is no 2PC/XA coordination between them.
  Acceptable for a single-node deployment; not a substitute for real distributed transactions if
  that ever matters.

## What the host implements

### `CatalogConnector`

Already the main seam (`catalog/CatalogConnector.java`). For UC embed, no new implementation is
needed at all: `OpenSharingLifecycle` (in the UC repository's `server-sharing` module) constructs
the existing `io.opensharing.catalog.unity.UnityCatalogConnector` — the same class standalone mode
uses against a remote Unity Catalog — pointed at `127.0.0.1:<armeriaPort>` instead of a configured
`opensharing.catalog.unity.uri`. Every call still presents the same `CatalogCaller` credential
standalone mode would (a live caller's bearer token, or on-behalf-of access for a recipient's
read); only the address changes.

### `ProviderIdentityResolver`

```java
@FunctionalInterface
public interface ProviderIdentityResolver {
  Optional<Caller> resolve(HttpServletRequest request);
}
```

Required in embedded mode — `AdminAuthenticationFilter` has no fallback to fall back to. For UC,
also no new implementation needed: `OpenSharingLifecycle` wraps the existing
`io.opensharing.principal.CatalogAuthorizingIdentityResolver` around the same `UnityCatalogConnector`
instance as the connector above, so both go through one connection to UC rather than opening two —
the resolver itself calls nothing but `CatalogConnector#authorize`, which `UnityCatalogConnector`
implements as `GET /opensharing/authorize`.

## Spring wiring

Beans gated on hosting mode:

| Bean | Standalone | Embedded |
|------|------------|----------|
| `CatalogConfiguration` (auto `CatalogConnector` + `ProviderIdentityResolver`) | yes | no — host supplies both |
| `SharingRuntime` | yes | yes |
| `EmbeddedStartupValidator` | no | yes |

Inspect runtime mode: inject `SharingRuntime` and call `hostingMode()`.

## Demo (embedded UC)

From `server/` after UC integration is built in a sibling `unitycatalog` checkout:

```bash
# terminal A — build, configure, start UC with embedded OpenSharing
UC_ROOT=~/unitycatalog ./scripts/demo-embedded-up.sh

# terminal B — walkthrough (share, recipient, protocol); it finds and sources demo.env on its own
./scripts/demo-embedded.sh
# or step through while recording:
PAUSE=ask ./scripts/demo-embedded.sh
```

`demo-embedded-up.sh` runs `mvn install` for `opensharing-server-core`, then
`sbt serverEmbedded/exportEmbeddedClasspath` in UC. Unity Catalog listens on `UC_PORT` (default
8080), and embedded OpenSharing's admin and protocol APIs answer there too — see "One process, one
address" above. `OS_INTERNAL_PORT` (default 8099) is OpenSharing's own port, bound to `127.0.0.1`
and not meant to be reached directly.

For the two-process demo (released UC jar + standalone OpenSharing), use `demo-unity-up.sh` and
`demo.sh` instead.

## Roadmap

`OpenSharingLifecycle` and the rest of UC's startup wiring live in the UC repository
(`server-sharing` module). This repo ships the library artifact
(`opensharing-server-core`, which includes the `unity` HTTP connector reused by both standalone and
embedded mode) and the standalone distribution (`opensharing-server`).
