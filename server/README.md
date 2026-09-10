# OpenSharing Reference Server

A prototype implementation of the [OpenSharing protocol](../spec/protocols/) — one server that both
manages shares and serves them to recipients, with the catalog and the database as replaceable
components.

**Deployment.** The reference server runs **standalone** by default (`OpenSharing.runStandalone` or
`OpenSharingServer.main`). It can also run **embedded** inside a host such as Unity Catalog OSS: the
host registers a `CatalogConnector` (and optionally a `ProviderIdentityResolver`) so catalog calls
stay in-process and provider configuration is not duplicated. See
[`docs/EMBEDDING.md`](docs/EMBEDDING.md).

**Scope: tables only.** The protocol also defines volumes, agent skills and models; this prototype
starts with tables, shared either one at a time or by the schema that holds them. The catalog is a
trait (`CatalogConnector`) with two implementations: open-source [Unity
Catalog](https://www.unitycatalog.io/) over its REST API, and a declarative file that contacts nothing,
so the sharing mechanics can be exercised on their own.

The management side follows Databricks' `DataSharingService` (`data_sharing.proto`): principals own
and author what they create, a share holds `SharedDataObject`s that carry the catalog's canonical name
plus the alias recipients see, and access is a `SharePermission` carrying a privilege. The
provider-admin API speaks snake_case to match; the recipient-facing protocol stays camelCase, as
Delta Sharing clients expect.

Tables are never copied. The server resolves a table in the provider's catalog, asks the catalog to
mint short-lived credentials scoped to that table's storage location, and hands them to the
recipient, who reads the bytes directly from cloud storage.

## Architecture

```
PROVIDER ADMIN (a principal)            RECIPIENT
      |                                    |
      | Bearer <own token>                 | activation URL -> config.share
      | manage shares, recipients, grants  | protocol calls (Bearer token)
      v                                    v
+-------------------------------------------------------------+
|                    OpenSharing server                       |
|                                                             |
|  Provider-admin API             Recipient protocol API      |
|  - principal / share /          - list & get tables         |
|    recipient / permission       - temporary-table-          |
|  - add object (resolved in the    credentials               |
|    catalog AS THE CALLER)       - authN recipient, authZ,   |
|  - recipient token comes with     IP access list            |
|    creation, rotate to replace  - re-resolves the object    |
|                                   before vending access     |
|                                 - read ops dispatch by      |
|                                   format: delta, iceberg    |
+------------+---------------------------------+--------------+
             |                                 |
   Stores (JPA)                       CatalogConnector (SPI)
   principals, shares + permissions,  resolveAsset(lookup, caller)
   recipients + tokens,               getStorageCredentials(req, caller)
   shared data objects                listChildren() (shared schemas only)
                                              |
             |                       UnityCatalogConnector (REST)
   H2 / Postgres / MySQL             LocalCatalogConnector (file)
                                               |
                                       cloud object storage
                                       (recipient reads directly)
```

### Layout

Two Maven modules build two jars:

| Module | Artifact | Role |
|--------|----------|------|
| `opensharing-server-core` | plain jar | Embeddable library (`OpenSharing.embedded()`, protocol, JPA) |
| `opensharing-server` | `-exec` fat jar | Runnable standalone server (`OpenSharingServer.main`) |

The things the server governs get a package each, and each one holds everything about that thing: its
JPA entity, its repository, its store, the provider-admin endpoints that touch it, and the request and
response types they use. The recipient-facing protocol is the one exception, and has a package of its
own.

| Package | What it holds |
|---|---|
| `principal` | `Caller`, `Ownership`, `ConfiguredPrincipalsIdentityResolver`, and `CatalogAuthorizingIdentityResolver` — the identity the admin filter resolves and controllers ask for when a write needs an owner. No principal table: `local` reads a fixed, in-memory configured list; `unity` asks the catalog itself, fresh, on every request, through `CatalogConnector#authorize` (`catalog.unity.UnityCatalogConnector`'s own implementation of it). |
| `share` | `ShareEntity`, `SharePermissionEntity` and `SharePrivilege`, `ShareStore`, the provider-admin share and permission endpoints, `ShareAccessService` (may this recipient read this share?) and `ShareMapper`. |
| `asset` | Everything about a shared asset that does not depend on its format: `SharedDataObjectEntity` and its status, `SharedDataObjectStore` and `SharedDataObjectService`, `SharedAliases` (the alias rules), `SharedTableService` (which tables a share holds, expanding a shared schema), `AssetResolutionService` (the server's use of the catalog trait, and so where the access modes an object offers are decided), `CredentialVendingService` and `TableMapper`. `asset.storage` holds reaching the storage a table lives in, whatever its format: the `UrlSigner` per scheme, the `StorageReader` that fetches a file the server itself has to look at, `StoragePaths` (whether a path is one of a shared table's own — asked by every format, so answered once), and `HadoopStorage`, which is how a read that goes through Hadoop gets the catalog's credentials and the path spelling a driver wants, `VendedGcsToken` included. Then one subpackage per format: `asset.delta` is url access mode — `DeltaLogReader` over Delta Kernel, `DeltaSharingCapabilities` (which response format a request settles on, and how much of the table's protocol the client is told) and a `DeltaLines` writer per format — and `asset.iceberg` is the Iceberg catalog's `loadTable`, plus the refusal that sends the Delta read operations there. |
| `serving` | The recipient-facing protocol: `RecipientApi` (every route it serves), the share, schema and table discovery endpoints, credential vending, the four table read operations, the Iceberg REST catalog with the error shape its clients read, and the `TableOperations` seam those read operations dispatch across. |
| `recipient` | `RecipientEntity`, `RecipientTokenEntity` and `AuthType`, `RecipientStore`, the provider-admin recipient and rotation endpoints, token minting, rotation and one-time activation, `IpAccessList`, and the filter and principal that authenticate a recipient request. |
| `catalog` | The `CatalogConnector` trait a new catalog implements and the types it exchanges, including `CatalogCaller`. Standalone connectors live in the `opensharing-server` module: `catalog.local` is the file-backed one, `catalog.unity` is Unity Catalog over HTTP. |
| `protocol` | Every wire shape the spec defines, inbound and outbound, and nothing else: `Share`, `Schema`, `Table`, `TemporaryCredentials` and its request body, the profile file, the read-response actions in both formats — `TableAction` is the one line of a response, and each of its slots takes either the parquet shape or the delta one — and the Iceberg REST shapes, whose spelling is Iceberg's rather than this protocol's. No behaviour, no dependencies. |
| `auth` | Admin authentication, bearer-token extraction and the token hashing every side shares. |
| `config` | Properties and bean wiring: which catalog, which filters, which argument resolvers, hosting mode, and how the two APIs are published as OpenAPI. |
| `runtime` | Library entry point (`OpenSharing`), hosting mode (`standalone` / `embedded`), `SharingRuntime`, and `ProviderIdentityResolver` for UC embed. |
| `http` | What both APIs put on the wire regardless of endpoint: `ApiFailure` (what a failure amounts to — a status, a code and something readable — decided once because it is written down two ways) and the `{errorCode, message}` body most of the protocol answers with, the `{items, nextPageToken}` envelope with the paging machinery that fills it, `@AdminJson` (the snake_case marker), and the protocol content type. |

`serving` sits apart from the per-thing packages because the protocol is a single contract that cuts
across all of them. One request walks a share, a schema, a table and a format, and a recipient calling
`/metadata` neither knows nor should know which of those the answer came from. Kept together, the whole
recipient surface is one package to diff against `spec/protocols/`, the same reason `protocol` holds
the wire shapes; scattered by entity, the surface could only be reconstructed by reading four
controllers and hoping none was missed. The admin API stays with each thing it administers, where its
entity and store are.

`asset` is split by format rather than by asset type because the read operations of
`spec/protocols/TABLES.md` are defined for a table, whatever the bytes underneath are — so the endpoint
is format-neutral and dispatch happens behind `TableOperations`, one implementation per format. `SCHEMA`
is shareable but has no package of its own, because a schema is not served: it resolves to the tables it
holds, which `SharedTableService` does before anything reaches a format.

The root package holds the application class and the two types every feature package needs:
`BaseEntity`, the JPA id and timestamps, and `ObjectNames`, the name rules of
`spec/protocols/OVERVIEW.md` plus the lower-casing that makes lookups case-insensitive.

Two boundaries are deliberate. The spec's shapes stay together in `protocol`, separate from the
admin shapes in each feature package, because the two answer to different contracts: `Share` carries
exactly what `spec/protocols/SHARES.md` defines and cannot change without breaking recipients, while
`ShareResponse` is this server's own admin type and is free to grow. Keeping `protocol` dependency-
free also makes it the one package to diff against the spec. The `CatalogConnector` interface is the
seam that matters most: nothing outside `catalog` knows which connector is in use.

Permissions couple `share` and `recipient`, so the dependency runs both ways by nature. The permission
lives in `share`, since it is a property of the share; deleting a recipient calls into `ShareStore` to
revoke what it held, and deleting a share calls into `SharedDataObjectStore` to remove the objects
inside it. Those two cascades are the only cross-package writes.

### Design decisions worth knowing

- **Every admin write has an identity behind it.** A provider-admin call authenticates as a
  `Principal` and the server stamps `owner_id`, `created_by`, `updated_by`, `added_by` and `granted_by`
  from it. An endpoint that needs an owner takes a `Caller` parameter, so the owner is something the
  handler cannot forget rather than a lookup it might skip.
- **A share or a recipient may only be changed by the principal that owns it.** Reading is open to any
  principal, so an admin can see what a colleague shares; every write goes through `requireOwned` and is
  refused with `PERMISSION_DENIED` for anyone else. `Ownership` states the rule once, which is where
  group membership will widen it — until then a `GROUP` owns only in its own right.
- **Provider identity is never stored.** For `catalog.type=local`, `opensharing.admin.principals`
  lists the usernames and bearer tokens the server recognizes, held in memory only — no database row,
  and rotating a credential means changing the configuration and restarting. For `catalog.type=unity`,
  there is nothing to configure at all: identity is resolved fresh from the catalog on every
  provider-admin request.
- **The catalog is asked as a provider, never as a recipient.** Adding an object is asked as the admin
  making the request, whose token is in hand while it is in flight. Serving — both re-resolving the
  table and minting the credentials that open it — is asked as the owner of
  the share being read through, because a recipient is nobody the catalog knows and the owner is whose
  access they read by — so an owner who loses access takes their recipients' with them, which is what
  Databricks does too. That means holding something the catalog accepts as the owner, which is why a
  principal's `bearer_token` is their catalog credential and their login here both: it is stored hashed
  to recognize them and sealed to replay, one secret in two forms rather than two secrets that would
  have to match. Nothing stored can be asked with means the read stops rather than falling back to the
  server's own catalog identity, which would answer on an access no provider was granted and outlive
  the access it was meant to follow.
- **A recipient sees an alias, not a catalog name.** An object stores the canonical `main.sales.orders`
  and the two-level `shared_as` alias, defaulting to the last two levels of the source name. Splitting
  that alias is what gives the protocol its schema level, so the two halves are stored normalized for
  case-insensitive lookups.
- **Schemas are derived, not stored.** A schema exists exactly as long as an object is shared under
  it, which keeps the stored schema to the entities the protocol actually needs to govern. A shared
  schema is the one row that names a schema directly, under a one-level alias, and it is why the name
  half of an alias is nullable.
- **A shared schema is a grant, not a copy.** Adding one writes a single row and no rows for the tables
  it covers: the catalog is what knows those, and it may answer differently an hour later. So they are
  asked for on every listing and assembled per request, which is the whole point — a table added to the
  schema is shared without anyone touching the share, and a dropped one stops being offered. The cost
  is a catalog call per listing, and holding a schema's tables in memory to page over them; a share
  with no schema grant keeps the database-paged query it always used.
- **A broken object stops being served instead of failing forever.** Re-resolution records
  `SOURCE_NOT_FOUND`, `PERMISSION_DENIED` or `SOURCE_NOT_SHAREABLE` on the object and recipient-facing
  queries filter to `ACTIVE`, so a dropped table leaves the listing while staying visible to the admin,
  and comes back on its own if the catalog resolves it again. The third of those is a table that is
  still there but is no longer something this server can share — recreated as CSV, or replaced by a
  view. Answering that the request was invalid would blame a recipient for a change in the catalog, on
  every read from then on, so it is withdrawn like the others.
- **The object type is kept as a seam.** Everything stored and served carries an `AssetType`; `TABLE`
  and `SCHEMA` are accepted and the rest are refused explicitly, so adding volumes, skills or models
  later is additive.
- **Timestamps that mean "when the row appeared" are not duplicated.** `added_at` and `granted_at` are
  the row's `created_at`; a token's `superseded_at` and `revoked_at` are real columns, because they say
  something `created_at` cannot.
- **No provider credential is ever stored, in any form.** Issuing a recipient token only creates an
  activation link. The bearer token is minted when the recipient opens that link, returned once inside
  `config.share`, and stored only as a SHA-256 hash — the server only ever needs to recognize it again,
  never present it anywhere. A provider's token is not stored at all, hashed or otherwise: for the
  `unity` catalog connector, every provider-admin request resolves identity fresh by asking the catalog
  itself (`GET .../opensharing/authorize`), and a recipient's read reaches the catalog through
  on-behalf-of access — this server's own configured identity plus the share owner's catalog user id,
  never a token nobody has. There is no principal table, so there is nothing for a database dump to
  expose in the first place.
- **A token comes with the recipient, and only rotation replaces it.** There is no issue endpoint, so
  credentials cannot pile up by accident and a recipient can never exist without a way in. Tokens are
  still rows of their own keyed by recipient, which is what makes rotation safe: the superseded token
  stays usable for its grace window while the partner installs the new profile file. Any token that
  is still usable authenticates, and revoked and expired rows stay behind as the audit trail.
- **Storage locations are re-resolved before access is granted.** Listings are served from the stored
  snapshot; anything that vends credentials asks the catalog again, so a relocated or dropped table is
  never served from stale state.
- **An ungranted share is reported as missing, not forbidden**, so recipients cannot probe for the
  names of other tenants' shares. An IP access list is the opposite: it says `PERMISSION_DENIED`,
  because the recipient holds a valid token and the network is the thing being refused.
- **A Delta table can be read either way, and only what can actually be served is offered.** In `dir`
  access mode the recipient gets credentials and replays the log itself. In `url` mode the server
  replays the log and hands back one signed url per file, so a recipient holds no credential at all.
  The protocol lets a client pick a mode from the list a table carries, so being sent down a dead end
  is worse than not being offered the mode: `url` is advertised only where this build can serve it, and
  `dir` only where the catalog says it will vend for the location, which it will not for a table on the
  server's own filesystem.
- **The server never opens a data file.** Reading the log is the only time it touches storage, and it
  does so with the same credentials it would hand a recipient — never with a standing secret of its
  own, which it does not have.

## Running it

Requirements: Java 21 and Maven.

```bash
cd server
mvn install
mvn -pl opensharing-server spring-boot:run -Dspring-boot.run.arguments="\
  --opensharing.admin.principals[0].name=alice@example.com \
  --opensharing.admin.principals[0].bearer-token=dapi-alice-secret"
```

The server listens on `http://localhost:8080` with an H2 file database under `server/data/` and the
sample catalog in `src/main/resources/local-catalog.yml`. Configure `opensharing.admin.principals`
with at least one username and bearer token, held in memory only — the local file catalog has no
identity provider to delegate to. With none configured, every admin call is rejected.

(For `opensharing.catalog.type=unity` there is no `admin.principals` to configure at all: a
provider-admin request just presents whatever bearer token their catalog already issued them, and
this server asks the catalog itself whose it is, fresh, on every request — see "Catalog integration"
above.)

Then walk through the whole provider-to-recipient flow (requires `jq`):

```bash
./scripts/demo.sh
```

## API surface

### Protocol endpoints (recipient, `Authorization: Bearer <token>`)

Mounted under `opensharing.protocol-prefix`, default `/api/2.1/opensharing`. Every route below is
declared in `RecipientApi` and served from the `serving` package.

```
GET  /shares
GET  /shares/{share}
GET  /shares/{share}/schemas
GET  /shares/{share}/all-tables
GET  /shares/{share}/schemas/{schema}/tables
POST /shares/{share}/schemas/{schema}/tables/{table}/temporary-table-credentials
GET  /shares/{share}/schemas/{schema}/tables/{table}/version
GET  /shares/{share}/schemas/{schema}/tables/{table}/metadata
POST /shares/{share}/schemas/{schema}/tables/{table}/query
GET  /shares/{share}/schemas/{schema}/tables/{table}/changes

GET  /iceberg/v1/config?warehouse={share}
GET  /iceberg/v1/shares/{share}/namespaces
GET  /iceberg/v1/shares/{share}/namespaces/{namespace}
GET  /iceberg/v1/shares/{share}/namespaces/{namespace}/tables
GET  /iceberg/v1/shares/{share}/namespaces/{namespace}/tables/{table}
POST /iceberg/v1/shares/{share}/namespaces/{namespace}/tables/{table}/metrics
```

Everything the spec defines for shares, schemas and tables is served, in both access modes.

`version`, `metadata`, `query` and `changes` belong to a table rather than to a format, so the endpoint
resolves the table and then asks whichever `TableOperations` implementation serves its format. A Delta
table is answered in full; an Iceberg or bare-parquet table is answered `NOT_IMPLEMENTED` with the route
that does work, since a recipient asking about a table they hold has asked a reasonable question — for
an Iceberg table, in the wrong protocol.

Dispatch goes by the format the catalog states on that request, not by the one recorded when the table
was last read, and that resolution travels into the implementation rather than being asked for twice.
So a table converted after it was shared is served by whatever serves its new format, from the first
read onwards: converted to Iceberg, it is pointed at the Iceberg catalog; converted from bare Parquet
to Delta, it starts being served rather than staying refused by a record that had fallen behind.

The rest of this section describes the Delta implementation of
those four; the Iceberg catalog is below.

These four are url access mode. They read the table's Delta log and answer in newline-delimited JSON,
one action per line, with `Delta-Table-Version` on every response: for `query`, the version the files
came from; for `changes` and for a `startingVersion` query, the version they start at.

Both response formats the protocol defines are produced, and a client says which it can read in
`delta-sharing-capabilities`. Parquet format states what a recipient needs in order to read each file:
`protocol` and `metaData` lines, then a `file` line per data file with a signed url and its expiry.
Delta format wraps the log's own actions instead — `deltaProtocol`, `deltaMetadata`, and a
`deltaSingleAction` whose path is the signed url — so a recipient can write the response into a local
Delta log and read the table with a Delta library. That is what carries a table the parquet shape
cannot describe: deletion vectors are signed alongside the file they belong to and named by
`deletionVectorFileId`, and column mapping travels in the metadata untouched.

The format is settled once per request, against the table, the way the reference server settles it:
from the features the table has **turned on** in its own properties — `delta.enableDeletionVectors`
and `delta.columnMapping.mode` — and not from the reader version its protocol carries. The two come
apart all the time, because a protocol goes on naming a feature long after the property that enabled
it was switched off, and a table that merely names one reads like any other. So a client naming one
format gets it, a client naming both gets parquet whenever the table reads as an ordinary one, and
silence means parquet, as the spec defines.

That also decides how the table is described. A client that listed no `readerfeatures` is told
`minReaderVersion` 1 with no features named, since by then nothing needing one is on and a version it
does not know would only make it refuse a table it can read; a client that listed features is told
what the log says and can judge for itself.

Three requests are refused rather than answered wrongly. A table using a feature the request did not
list in `readerfeatures` is a `400` naming the feature and how to ask for it — in either format, since
the objection is to the reader, not the shape. Parquet for a table using one at all is
`NOT_IMPLEMENTED` pointing at `responseformat=delta`, which only a client that claimed the feature and
then asked for parquet alone can reach. And a file carrying a deletion vector in a parquet response is
`NOT_IMPLEMENTED` too: the table said the feature was off, so the format was settled as parquet, and
only the file itself reveals that some of its rows are gone. The reference server hands such a file
over; this one will not, because a recipient would count deleted rows as live ones with nothing to
tell it otherwise.

The chosen format comes back in the response header, along with `includeendstreamaction=true` when the
client asked for the closing line and the response carries one. `fileidhash` accepts either scheme:
ids are derived from a file's path within the table, which is stable across both formats and both
endpoints.

`changes` answers with the table's history over a window rather than its state: an `add`, `cdf` or
`remove` line per change file in parquet format, or a `file` line wrapping the same action in delta
format, each carrying the version and commit timestamp a streaming reader tracks. Either end of the
window can be named by version or by timestamp — `startingVersion` and `startingTimestamp` are mutually
exclusive, as are the two ending forms — and a timestamp resolves the way the spec asks, the start
forward to the first version at or after it and the end back to the last version at or before it.
Omitting the window reads from the table's first version to its latest. The `cdf` lines are the
before-and-after an update recorded, so they only appear for a window of commits a writer made with
`delta.enableChangeDataFeed` on; a window without them still reports its adds and removes.

`includeHistoricalMetadata=true` turns the window into a chronology: it opens with the starting
version's own schema and then reports each change to it, in place among the files, so a stream can tell
which schema each file was written under. `includeHistoricalProtocol=true` does the same for protocol
changes, and only in delta format, which is the only one with a line for them — a parquet response has
nowhere to put one, and a version raised mid-window says nothing a reader of those lines can act on, so
the window is served through it and each file is judged on its own. Without either flag the window is
headed by its ending version's metadata, as before.

`query` answers with a snapshot unless it is given `startingVersion`, which asks the other question a
table can be asked: what has changed since. Then the response carries the files each commit added and
removed, from that version to `endingVersion` or to the latest, headed by the starting version's own
protocol and metadata and including the schema changes along the way. The recorded row-level changes of
a change data feed are not part of it — those belong to `changes`, and a stream rebuilding the table
from commits would count them twice. `startingVersion` cannot be combined with `version` or
`timestamp`, which ask what the table held rather than what has happened to it.

The Iceberg REST catalog is served in full, at the path the profile file's `icebergEndpoint` points at.
`/v1/config` takes the share as its `warehouse` and answers with the path prefix `shares/{share}` a
client addresses every later call by, and the list of operations that exist here — there is no create,
drop, rename or commit, because a recipient cannot change a provider's tables.

A share is the warehouse and its schemas are namespaces, so `listNamespaces` and `listTables` show the
same objects the protocol's own `schemas` and `tables` endpoints do, one level deep and paged with
`pageSize` and `pageToken`. `listTables` names only the share's Iceberg tables, since a table of
another format is not one this catalog could hand over; asking for one anyway is a 404, which is what
"no such table" is to an Iceberg client.

`loadTable` relays the table's own metadata document, read from the `metadataLocation` the catalog
reports, together with credentials for its storage under both the `config` map and the
`storage-credentials` list, keyed as Iceberg's file IO reads them (`s3.session-token`,
`adls.sas-token.<account>`, `gcs.oauth2.token`, each with its expiry). That is the whole of what an
engine needs: it plans the scan from the metadata and reads the manifests and data files itself, which
is why nothing here replays a log or signs a data file. The document is passed through untouched — this
server has no opinion about a format it does not implement — and the metadata file is the only thing it
reads, through a signed url from the same grant, so it looks inside a table with exactly the access it
is about to hand over. A pointer that leads outside the shared location is refused rather than
followed. `reportMetrics` accepts a scan report and drops it: the scan happened on the recipient's
engine and the numbers are none of this server's business.

Failures on these paths are rendered in Iceberg's own `{"error": {...}}` body rather than the
protocol's `{errorCode, message}`, because an Iceberg client parses the body to tell a missing table
from a server it cannot understand. The judgement is the same one every other endpoint makes; only the
spelling differs.

The volume, skill and model endpoints of the spec are not served yet.

One path is not in the spec as written: `temporary-table-credentials` follows the naming of
`temporary-volume-credentials` in VOLUMES.md, because TABLES.md links to a
`GenerateTemporaryTableCredential` section that the document does not contain. The four Delta
operations come from the Delta Sharing protocol, which `TABLES.md` defers to for Delta access. There is
no `GET .../tables/{table}`: the protocol has no such endpoint, and a table's details come from a
listing or from `metadata`.

All list endpoints accept `maxResults` and `pageToken` and return `{items, nextPageToken}`.

### Provider-admin endpoints (`Authorization: Bearer <principal's token>`)

Mounted under `opensharing.provider.base-path`, default `/api/2.1/opensharing/provider`. Bodies are
snake_case. Any principal may read; a `PATCH`, `DELETE` or `rotate-token` on a share or a recipient
is refused unless the caller owns it.

```
POST   /shares                                      create a share
GET    /shares                                      list shares
GET    /shares/{name}                               get a share with its objects
PATCH  /shares/{name}                               update metadata, add or remove objects
DELETE /shares/{name}                               delete a share, its objects and its permissions
GET    /shares/{name}/permissions                   list who may read the share
PATCH  /shares/{name}/permissions                   grant or revoke privileges
POST   /recipients                                  create a recipient with its first token
GET    /recipients                                  list recipients
GET    /recipients/{name}                           get a recipient with its token history
PATCH  /recipients/{name}                           update the IP access list or properties
DELETE /recipients/{name}                           delete a recipient, its tokens and its grants
POST   /recipients/{name}/rotate-token              replace the token, returns an activation URL
GET    /recipients/{name}/share-permissions         list the shares granted to the recipient
```

**Provider principals** are not managed through this API. List them in configuration and restart to
add or rotate one:

```yaml
opensharing:
  admin:
    principals:
      - name: alice@example.com
        bearer-token: dapi-alices-catalog-token
      - name: bob@example.com
        bearer-token: dapi-bobs-catalog-token
```

`bearer-token` is the principal's catalog credential, and this server accepts it as their login too.
One secret, because the server has to ask the catalog as whoever shared an asset, and a second secret
would only ever have to be kept identical to this one to behave; whoever holds it can already act as
them against the catalog that decides everything here anyway. It is never returned or logged, and,
unlike a recipient's token, never persisted anywhere at all: the configured list lives in memory only
for the life of the process, compared directly against what a request presents. Rotating a credential
means updating the allowlist and restarting the server.

Every other admin call authenticates as one of those principals, because everything records who did it:

**Adding a table** names the source in the catalog and, optionally, the alias recipients will see:

```bash
curl -X PATCH "$ADMIN/shares/sales_share" \
  -H "Authorization: Bearer caller-generated-secret" -H 'Content-Type: application/json' \
  -d '{"updates":[{"action":"ADD","data_object":
        {"name":"main.sales.orders","type":"TABLE","shared_as":"sales.orders"}}]}'
```

The server resolves the source in the catalog **as the calling principal**, so the catalog decides
whether they may share it, and records the source's asset id, subtype and format. Two permissions are
at work and only one of them is the catalog's: the caller must own the share, which this server checks
itself, and must be allowed to share the asset, which only the catalog can answer. That second
question is not about owning the asset — Databricks, whose model this follows, asks for `SELECT`
on the table or view together with `USE CATALOG` and `USE SCHEMA` on the parents holding it, so a
connector that demanded ownership would refuse a provider Databricks allows. `type` defaults to
`TABLE`; `SCHEMA` is the other accepted value and anything else is refused for now. `shared_as`
defaults to the last two levels of the source name, so `main.sales.orders` is served as schema `sales`,
table `orders`. `REMOVE` takes the same `data_object`, matched on either the source name or the alias.
Updates apply in order and the whole `PATCH` is one transaction, so a rejected object leaves the share
untouched.

A table is also refused if no access mode could serve it: neither mode is this server's to promise
alone — `dir` needs the catalog to vend for the location and `url` needs a Delta log this build may
replay — and a table with neither would be accepted, listed, and unreadable by any route a recipient
could take. Better said while the provider is still on the phone.

**Adding a schema** shares every table in it, including the ones added to it after this call:

```bash
curl -X PATCH "$ADMIN/shares/sales_share" \
  -H "Authorization: Bearer caller-generated-secret" -H 'Content-Type: application/json' \
  -d '{"updates":[{"action":"ADD","data_object":
        {"name":"main.hr","type":"SCHEMA","shared_as":"hr"}}]}'
```

A schema's alias is one level, since the schema is itself the level a recipient sees, and it defaults
to the last level of the source name. Nothing is written for the tables it covers: they are read from
the catalog whenever a recipient lists or reads one, so the share stays in step with the schema by
never having its own copy of what is in it. The trade is that the provider gives up naming them — a
table appears under the name the catalog gives it — and that the catalog must be able to enumerate,
which is checked here rather than left for a recipient to discover.

Two schemas cannot be shared under one alias, since a recipient's table would then be ambiguous. A
table shared in its own right may sit in a shared schema's alias, and wins over the schema's own table
of that name, which is how a table from elsewhere joins the schema a recipient sees. Removing the grant
takes the one-level alias, or the source name.

**Granting** resolves both names to their ids and stamps `granted_by`:

```bash
curl -X PATCH "$ADMIN/shares/sales_share/permissions" \
  -H "Authorization: Bearer caller-generated-secret" -H 'Content-Type: application/json' \
  -d '{"changes":[{"recipient_name":"partner_acme","add":["SELECT"]}]}'
```

`SELECT` is the only privilege today, and it is what the protocol endpoints check. `remove` takes the
same list, and both are idempotent.

**A token comes with the recipient.** There is no endpoint that issues one on its own, so a recipient
always has a way in and credentials cannot pile up by accident. The create response is the only place
the activation URL appears:

```json
{"recipient": {"recipient_id": "...", "name": "partner_acme", ...},
 "token": {"token_id": "...", "activation_url": "https://.../activation/...", "expires_at": "..."}}
```

Replacing it is a rotation. The superseded token keeps working for `existing_token_expire_in_seconds`
(default `recipient-tokens.rotation-grace`, `0` to cut it off at once), which is the window the
recipient has to install the new `config.share`; the row keeps a `superseded_at` so the history shows
what happened. An activation link that was never opened is invalidated instead, since nobody is
holding the token behind it.

```bash
curl -X POST "$ADMIN/recipients/partner_acme/rotate-token" \
  -H "Authorization: Bearer caller-generated-secret" -H 'Content-Type: application/json' \
  -d '{"existing_token_expire_in_seconds":86400}'
```

Rotating with `0` is also how a compromised token is killed: it is revoked at once and a replacement
is waiting.

### Activation (unauthenticated, single use)

```
GET /api/2.1/opensharing/activation/{nonce}    ->  config.share profile file
```

### The API described (unauthenticated)

```
GET /v3/api-docs/protocol  ->  OpenAPI for the recipient protocol
GET /v3/api-docs/admin     ->  OpenAPI for the provider-admin API
GET /swagger-ui.html       ->  both, browsable
```

The documents are read out of the controllers and the records they return, so they cannot promise
something the server does not serve. They exist for the other side of the wire: point a generator at
one and get a typed client instead of hand-writing calls. Note that `spec/protocols/` remains the
normative definition of the protocol — this describes one server's rendering of it.

The two APIs are separate documents because they answer to different contracts and even spell their
fields differently: the protocol is camelCase and fixed by the spec, the admin API snake_case and
this server's own. Neither is behind authentication, since the filters cover only the two API
prefixes; what a server accepts is not a secret, but a deployment that disagrees can set
`springdoc.api-docs.enabled=false`.

One thing the document says poorly: the four table read operations answer with newline-delimited
JSON, a stream of actions rather than one object, so their bodies appear there as text. What a line
holds is described under the protocol endpoints above.

## Configuration

Everything lives under the `opensharing` prefix in `src/main/resources/application.yml`. Any key can be
overridden without editing the file, either as `--opensharing.provider.base-path=/provider` on the
command line or as the upper-case underscored form of the same path in the environment, so
`opensharing.admin.principals` is `OPENSHARING_ADMIN_PRINCIPALS_0_NAME` and `OPENSHARING_ADMIN_PRINCIPALS_0_BEARER_TOKEN` for indexed entries.

| Key | Default | Purpose |
|---|---|---|
| `protocol-prefix` | `/api/2.1/opensharing` | Prefix for protocol endpoints; also what goes in the profile file. |
| `provider.base-path` | `/api/2.1/opensharing/provider` | Prefix for the provider-admin API. |
| `admin.principals` | `[]` | Usernames and bearer tokens, held in memory only — `catalog.type=local` only; `unity` asks the catalog itself instead. Each token is both the admin login and the catalog credential. |
| `activation.base-path` | `/api/2.1/opensharing/activation` | Prefix the single-use activation endpoint is served under. |
| `activation.external-base-url` | `http://localhost:8080` | Public base URL used to build activation URLs and the profile endpoint. |
| `activation.ttl` | `72h` | How long an unused activation link stays valid. |
| `recipient-tokens.default-ttl` | `90d` | Token lifetime when none is given; blank means never expires. |
| `recipient-tokens.rotation-grace` | `24h` | How long a superseded token keeps working when a rotation does not say. |
| `asset-credentials.ttl` | `1h` | Lifetime requested from the catalog for storage credentials. |
| `pagination.default-max-results` / `max-max-results` | `500` / `1000` | List page sizes. |
| `delta.url-access-enabled` | `true` | Read Delta logs and serve `version`, `metadata` and `query`. Off leaves `dir` mode alone and stops `url` being advertised. |
| `delta.url-ttl` | `1h` | Lifetime of a signed file url, capped by the credentials it was signed with. |
| `storage.s3-region` | `us-east-1` | Region used to read and to sign S3 urls when the catalog's credentials do not name one. |
| `storage.gcs-service-account-key-file` | blank | Service account key whose private key signs urls for `gs` paths. Blank falls back to `GOOGLE_APPLICATION_CREDENTIALS`; with neither, Google storage is offered in `dir` mode only. |
| `catalog.type` | `local` | Which connector to run: `local` or `unity`. |
| `catalog.local.file` | `classpath:local-catalog.yml` | Spring resource location of the catalog file. |
| `catalog.unity.uri` | blank | Base url of the Unity Catalog API, including the path it is served under, e.g. `http://localhost:8081/api/2.1/unity-catalog`. Required when the type is `unity`. |
| `catalog.unity.connect-timeout` / `request-timeout` | `5s` / `30s` | How long to wait for the catalog to accept a connection, and for it to answer. |
| `catalog.unity.server-secret` | blank | This server's own identity, presented instead of a bearer token for on-behalf-of access on a recipient's read (no owner token is available to present for it). Must match the catalog's own configured secret. Only required if a recipient is ever actually served. |

### Reaching storage

Delta Kernel replays a log through Hadoop's filesystem interface, which is how the reference sharing
server reads a table too, so reading a log on a cloud comes down to two things: a driver for that
storage, and credentials in the configuration the read is made with.

Four filesystem families ship, one per storage the protocol's own credential shapes name — `s3a` for
AWS and S3-compatible stores, `abfss` and `wasbs` for Azure, `gs` for Google Cloud Storage — plus
`hdfs` and local paths, which come with Hadoop itself. A location the catalog reports as `s3://` or
`s3n://` is addressed as `s3a://`; every other scheme is already what Hadoop calls it. Storage nothing
here addresses is `NOT_IMPLEMENTED` naming the mode that still works, and so is a driver a deployment
slimmed out of the jar — neither is anything a request can fix.

The reference server hands Hadoop an empty configuration and lets the machine it runs on supply the
access, which means one standing set of credentials reads every table. Here a configuration is built
per read from the credentials the catalog minted for that table's location — the same ones a recipient
would get from `temporary-table-credentials`, so the server reads a log with exactly the access it
hands out and one table's credentials are never in reach of another's read:

| Storage | What the driver is given |
|---|---|
| AWS, R2 | The session triple as `fs.s3a.access.key`, `.secret.key` and `.session.token`, read by `TemporaryAWSCredentialsProvider`, with `fs.s3a.endpoint.region` from the catalog or `storage.s3-region` so no read waits on instance metadata for a region already known. |
| Azure | The user delegation SAS itself, as `fs.azure.sas.fixed.token` under SAS auth. The token is the grant, so it is handed over rather than minted from a key. |
| Google | The OAuth token, through `VendedGcsToken`. Google's connector takes credentials only from a provider class it instantiates, so the token travels in the configuration under a key of this server's own and is read back there. |

Two cases fall through to the deployment's own Hadoop configuration, which `core-site.xml` on the
classpath supplies as usual: a table the catalog vends nothing for, and Azure's older `wasb`
filesystem, whose SAS support mints tokens from an account key rather than accepting one. That is the
reference server's model, kept as the fallback rather than as the rule.

Two dependency choices are worth knowing, since both are visible in the jar's size. The AWS SDK's
`bundle` artifact is excluded — 686 MB of every service AWS has, where S3A calls four of its modules —
and Google's connector is taken as the shaded build it publishes for this, keeping gRPC, Guava and
Gson out of the server.

### Signing a url

Handing out a url is the other half of reaching storage, and each cloud gives a different answer to
the same question — what makes a url stand on its own. AWS takes the session triple into a SigV4
query signature, so the url carries the credential; Azure appends the user delegation SAS, which *is*
the credential. Both are therefore signed from what the catalog minted for that one table, and neither
can outlive or outreach it.

Google is the exception, and it is a structural one. A V4 signature there is RSA over the request, so
only a private key produces one, and an OAuth access token — all a catalog has to vend — cannot be put
in a url at all. The reference sharing server resolves this by holding a service account key and
signing with it, pointed at the file by `GOOGLE_APPLICATION_CREDENTIALS`; this server does the same,
through `storage.gcs-service-account-key-file` or that same variable. What follows is worth being
explicit about: such a url reaches whatever that service account may read, rather than only what the
catalog was willing to vend for, so the key should be one whose access is no wider than what is
shared. It signs and never reads, so no table is *read* with it either way. A deployment that would
rather hold no key leaves it unset, and then `gs` has no signer, `url` mode is not advertised for a
table on Google storage, and `dir` mode serves it with the vended token as before.

That last part is the general rule: `url` mode is offered only for a scheme this build can sign, so a
table never advertises a mode that would replay its log and then refuse every file it found.

The signing is written out here rather than taken from each cloud's SDK — the same trade as the
excluded AWS bundle, and for Google the difference between one `Signature` call and pulling in the
library the shaded connector exists to keep out. Signatures are unit-tested by verifying them against
the public half of a generated key, over a string to sign the test builds from each cloud's published
algorithm rather than from the signer's own code.

The Google signature has also been put to Google, which is the only check that rules out a correct
implementation of a misread specification: a url signed with a real service account key was accepted
for a real object, one altered hex digit in it came back `SignatureDoesNotMatch`, and the same
signature over an object that does not exist came back `NoSuchKey` — so the signature was verified on
its way in, rather than the object merely being readable anyway. That took credentials and a bucket,
so it is not part of the suite.

### The catalog trait

`CatalogConnector` is the whole seam between the server and a system of record for assets. Two methods
are required, because Unity Catalog, Polaris and Iceberg REST all answer the same two questions and the
server asks nothing else of a connector that shares assets one at a time:

```java
public interface CatalogConnector {
  String name();

  ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller);
  List<StorageCredentials> getStorageCredentials(CredentialRequest request, CatalogCaller caller);

  // Optional. Required only to share a whole schema.
  default List<ResolvedAsset> listChildren(AssetLookup parent, CatalogCaller caller) { ... }
}
```

`resolveAsset` answers with everything the catalog knows about the asset:

| Field | Purpose |
|---|---|
| `type` | Asset kind: a table or a schema. |
| `identifier` | The catalog's canonical name, which every later resolution asks about. |
| `catalogAssetId` | Durable catalog identity, where there is one, and what a catalog vending by id mints against. A share names an asset by name, so one recreated under the same name is served as the same object, with this changing underneath as the only trace. |
| `storageLocation` | Where the bytes are: what a credential is scoped to, what a url is signed from, what a path is checked against. |
| `metadataLocation` | Format-specific pointer needed to interpret those bytes, such as an Iceberg table's current metadata JSON. |
| `format`, `subtype` | `delta`/`iceberg`/`parquet`, and the catalog's own refinement — `MANAGED`, `EXTERNAL`, `VIEW`, `MATERIALIZED_VIEW`, `STREAMING_TABLE`. |
| `schema`, `partitionColumns` | The logical schema and the ordered partition columns, as the catalog reports them. What a recipient is told is the schema the format itself states — the Delta log, or an Iceberg metadata document — since that is the one the bytes were written under, so the catalog's copy is carried rather than served. |
| `accessModes` | The modes the catalog can support: `dir` in practice, since whether credentials can be scoped to the location is its answer to give. `url` is the server's, and depends on what this build can sign. |
| `auxiliaryLocations` | Other locations the catalog approves for the asset, which a credential may also cover — a table whose data spilled past its own prefix. |

A shared object stores a snapshot of this, so listings come from the database rather than a catalog
call per row. Every resolution rewrites the snapshot where the answer has moved on — relocation, a
changed format or subtype, a different id — and the two answers that end it instead are the asset being
gone and the owner no longer being allowed to read it, which withdraw the object so it stops being
listed rather than failing every read. Three fields stay out of the snapshot: `metadataLocation`,
because an Iceberg pointer moves with every commit and is used from the resolution in hand, and
`schema` and `partitionColumns`, because the format states both and that is the copy a recipient is
given.

It is called when an admin adds the object (so a share can never
point at something that does not exist) and again before every vend (so a relocated asset is never
served from a stale snapshot). `caller` carries the name and credential of the provider-side principal
the question is asked for: the admin making the request when an object is added, and the owner of the
share being read through when a recipient reads. Either way what to ask of them is whether they may
share the asset, not whether they own it — `SELECT` on the table or view, with `USE` above it, in
Databricks terms. Serving asks it of the owner on purpose: a recipient reads by virtue of that
provider's access, so an owner who loses it should take their recipients' along, and asking every time
is what makes that happen. Both halves are always there, so an implementation never has to decide what
to do without a credential: a principal who has none stops the read before a connector is reached,
rather than having it answered by a service identity whose access outlives theirs. A connector that
denies the caller throws `AssetAccessDeniedException`, which the admin API reports as
`PERMISSION_DENIED` and serving records on the object's status.

Resolution is also the existence check. There is no separate `exists`, because every caller that wants
to know whether an asset exists needs the metadata that proves it, so the cheaper call could never be
used. Nor is there a separate `authorize`: the caller is already an argument here, and an exception
says more than a boolean would. Nor is there an intent argument saying whether the asset is about to be
shared or read, which this once carried: both are the provider-side question of who may share, and a
second argument that could only ever agree with the first is one more thing to read the wrong way.

`getStorageCredentials` returns a prefix, a cloud provider, an opaque credential map and an expiry —
never a long-lived secret the server would have to keep. It returns a list because Iceberg's
`LoadCredentialsResponse` does: an asset may span prefixes, and `AssetResolutionService` picks the
narrowest entry covering the location being read, which is what the Iceberg spec asks of clients and
keeps a bucket-wide grant from shadowing a table-specific one. It takes a `CredentialRequest` rather
than a location, because a catalog may vend by asset id instead of by path — Unity Catalog's `POST
/temporary-table-credentials` takes a `table_id` — and because the asset type selects the endpoint on
catalogs that vend volumes separately from tables. It takes a caller for the same reason `resolveAsset`
does, and it is the more important of the two: this is the moment access to the bytes is handed out, so
it is the moment a catalog most wants to decide, and a grant minted on any other identity would be one
that losing the owner's access does not take away.

`listChildren` is the one optional method, and exists for exactly one caller: a shared schema, whose
tables have to come from somewhere and are not stored here. It defaults to refusing, so a catalog that
cannot enumerate stays a perfectly good catalog for sharing assets one at a time — and adding a schema
to a share is then rejected while the provider is still on the phone, rather than at the first listing
by a recipient. It answers with full `ResolvedAsset`s rather than bare identifiers because the catalogs
worth plugging in already carry that in their list response — Unity Catalog's `GET /tables` returns each
table's columns and location — so asking for identifiers alone would throw it away and buy it back one
request per child.

Provider-side browsing beyond that is deliberately absent. Recipient-facing listings of *shared*
objects are served from the metadata store, so nothing else needs to walk the catalog.

### The trivial implementation

`LocalCatalogConnector` implements that trait against a declarative file, so there is nothing to
authenticate to and nothing to deploy. Point `catalog.local.file` at a YAML file describing tables:

```yaml
credentials:
  provider: AWS        # AWS | AZURE | GCP | R2
  mode: FAKE           # FAKE generates placeholders; STATIC returns credentials.values verbatim
assets:
  - identifier: main.hr
    type: SCHEMA         # shareable as a whole; holds every main.hr.* table below
  - identifier: main.sales.orders
    subtype: MANAGED     # optional; recorded on the shared object as source_subtype
    storageLocation: s3://acme-lake/sales/orders/
    format: delta        # delta | iceberg | parquet
    metadataLocation:    # what a client needs to interpret the bytes; required of an Iceberg table
      s3://acme-lake/sales/orders/metadata/v3.metadata.json
    schema: '{"type":"struct","fields":[]}'   # optional; as the catalog states it
    auxiliaryLocations:  # optional extra locations credentials may be scoped to
      - s3://acme-overflow/sales/orders/
    sharableBy:          # optional; omit and any principal may share it
      - alice@example.com
```

Identifiers are matched case-insensitively and `type` defaults to `TABLE`. A `SCHEMA` entry needs no
location of its own and holds whatever names it: `main.hr` contains `main.hr.employees`, which is all
the hierarchy a flat file needs for a schema to be shared as a whole. `sharableBy` is how the file
stands in for a real catalog's permission model: a principal outside the list is refused when adding
the object, which is the check `resolveAsset` exists to make. What it stands in for is being allowed
to share the asset — `SELECT` on the table or view, and `USE` on the catalog and schema above it —
rather than owning it, which is not asked for here or by Databricks. Serving asks it too, of the
owner of the share being read through, so removing a principal from the list stops what they already
shared as well as what they might share next. The credential that arrives with the name is ignored:
this file authenticates nobody, it only recognizes names. Unknown keys, an unsupported `format`
and an unsupported `accessModes` entry all
fail the file at startup, so a typo cannot silently drop a table or leave one that only breaks when a
recipient reaches it. `mode: FAKE` logs a warning at startup, because the credentials it hands out
open nothing.

### Unity Catalog

`UnityCatalogConnector` implements the same trait against an open-source Unity Catalog, over its REST
API. Point it at one:

```yaml
opensharing:
  catalog:
    type: unity
    unity:
      uri: http://localhost:8081/api/2.1/unity-catalog
```

No credential sits beside that url, and that is the point: every request is made as the principal it
concerns, presenting the token stored for them, so what this server can see in the catalog is never
more than what the provider asking could see themselves. Adding a table is asked as the admin adding
it; a recipient's read is asked as the owner of the share, whose token is unsealed for the call. Turn
Unity Catalog's own authorization on and it decides both.

Three calls carry the integration:

| What the server needs | Unity Catalog call |
|---|---|
| Where a table lives, and in what format | `GET /tables/{catalog.schema.table}` |
| That a schema exists, and what is in it right now | `GET /schemas/{catalog.schema}`, then `GET /tables?catalog_name=&schema_name=` a page at a time |
| Credentials for that storage | `POST /temporary-table-credentials` with the table's id and `READ` |

With Unity Catalog's authorization turned on, the principal a call is made as needs `USE CATALOG` on
the catalog, `USE SCHEMA` on the schema and `SELECT` on the table — the same three Databricks asks for.
The vend is authorized separately from the lookup and wants all three, so a principal holding only
`SELECT` is told where a table lives and then refused the credentials to read it; even a metastore
admin is refused until the grants exist. A provider hits this while adding the table, which is the
intended moment, and a share owner who loses one of the three afterwards stops their recipients with
them.

`table_id` is what the vend takes rather than a path, so it is kept as the object's `source_asset_id`
and refreshed on every resolution. `storage_location` becomes what credentials are scoped to,
`table_type` is recorded as `source_subtype`, and the `partition_index` on each column becomes the
ordered partition columns. The columns themselves are not translated: nothing here reads a
catalog-stated schema, since a Delta table's own log is the authority on its shape. `dir` mode is
offered for a table Unity Catalog will mint for, which is any table on a cloud, and withheld from one
on this machine's own filesystem, where it holds no grant to hand out — a recipient picks a mode from
what a table offers, so offering one nothing can be minted for would be an invitation to a dead end.
`url` mode is not the catalog's to offer either way: it depends on the format and on what this build
can serve.

An external table on the filesystem the server runs on is vended for like any other, and Unity Catalog
answers it with every credential block empty — its own reader takes that answer and opens the file. So
does this server: an empty vend from a `file:` or bare path means nothing is needed, the log is replayed
on the deployment's own filesystem access, and the recipient is handed urls to the parquet. The same
empty answer about a bucket stays a failure, since there it means a catalog that was never told about
the storage, and calling that "nothing needed" would turn a misconfiguration into a read that dies
further down. Such a table is offered in `url` mode only, so a recipient is never invited to ask for
credentials that do not exist; one that asks anyway, without reading what the table offers, is told
which mode does work.

Url mode carries a local table because the server replays its log through the same filesystem — but
that is the whole of what a credential-free table gets, and a Parquet one has no log to replay. A
local Parquet table therefore has no route to a recipient at all: nothing to vend for `dir`, nothing
to sign for `url`. Adding one is refused outright, naming both halves of the reason, and inside a
shared schema it is passed over like any other table this server could not serve.

`data_source_format` decides the format, and Unity Catalog's list of them has no Iceberg member, so a
table shared this way is Delta or Parquet; Iceberg tables in a Unity Catalog are reached through its
own Iceberg REST endpoint, which is a different connector's job. A table in any other format, and a
view, which has no storage to point a recipient at, are refused as a bad request naming which it was —
while the provider is still on the phone. Inside a shared schema they are passed over instead: sharing
a schema is an offer of whatever is in it, and one unreadable table among a hundred should not take the
other ninety-nine down with it. One that becomes either after it was shared is withdrawn on the next
read, as `SOURCE_NOT_SHAREABLE`.

A `404` is read as the asset being gone, and both a `403` and a `401` as the caller no longer being
allowed to read it: a `401` is their stored token expired or revoked, not this server failing to
authenticate, since it holds no credential of its own and asks only as them. Both therefore withdraw
the object rather than leave it listed and failing on every read, and the log says which it was and
that the principal needs a new bearer token. Anything else is a bad gateway saying only which status
came back — which request it was, and the catalog's own message, go to the log, because the same code
serves a recipient, who knows the table by the alias it is shared under and has no business learning
its internal name or reading text written upstream of here. Where one of these lands on a recipient's
read, whether the catalog was refusing to resolve the table or to mint for it, the object is marked
unservable and the recipient is told only that the server may no longer read it: the owner it was asked
as is named in the log, not on the wire. The same
line divides the one failure that is the server's own — an owner with no stored credential to ask as —
where the log names the principal and says how to fix it, and the recipient hears only that the
provider it is shared by has none.

Databricks' Unity Catalog answers the same endpoints and would mostly work, but its two extra
credential shapes (`r2_temp_credentials`, `azure_aad`) are not read, so a table backed by either is
refused rather than served with credentials this build guessed at. Dir mode is therefore offered only
for the storage whose grant this can read — `s3`, `abfss`, `gs` and their spellings — which keeps a
table on Cloudflare R2 out of a share rather than letting it in and failing every vend. An Azure AAD
token is the one that cannot be told apart in advance, since it arrives for the same `abfss` location a
delegation SAS would; a catalog minting those fails at the vend.

### Another catalog

Add a subpackage under `catalog` — as `catalog.unity` and `catalog.local` are — implement
`CatalogConnector` there and either add a branch to `CatalogConfiguration` or contribute your own
`@Bean`. That default is `@ConditionalOnMissingBean`, so yours takes over with no other change to the
server, and that class is the only one outside `catalog` that ever names an implementation.

### Databases

H2 by default. Both other drivers ship in the jar, and switching is configuration only — the URL is
enough, since the driver is derived from it and Hibernate detects the dialect from the connection:

```bash
mvn -pl opensharing-server spring-boot:run -Dspring-boot.run.arguments="\
  --spring.datasource.url=jdbc:postgresql://localhost:5432/opensharing \
  --spring.datasource.username=opensharing \
  --spring.datasource.password=secret"
```

The entities are written to keep that true. Every unique key stays inside InnoDB's 3072-byte index
limit under utf8mb4 — the widest is `(share_id, name_lower)` at 2192 bytes, which is why a catalog name
is capped at 512 characters — and case-insensitive lookups go through explicit `*_lower` columns rather
than relying on collation, so MySQL's case-insensitive default and Postgres's case-sensitive one behave
identically.

Schema management is `hibernate.ddl-auto=update`, which suits a prototype; a real deployment should
use versioned migrations.

## Tests

```bash
mvn test
```

The server tests boot the whole application against an in-memory database and the file-backed catalog,
then drive it exactly as a provider admin and a recipient client would, following the flow above:
provision Alice from configuration, create a share as her, add tables under aliases, create a
recipient, grant `SELECT`, activate the token minted with the recipient, discover the tables, vend
credentials, and rotate the token with and without a grace window. Sharing a schema gets the same
treatment, from the other end: nothing is added under a name, and the recipient still lists the tables,
pages through them, and vends credentials for one — plus the cases only a grant has, an alias claimed
twice, a table the schema does not hold, and a table shared in its own right winning over the schema's.
They also confirm what must be
refused: a principal who may not share a table, a
principal writing to a share or a recipient it does not own, a principal who still owns something being
deleted, duplicate names in either case, an unsupported object
type, a source the catalog does not know, missing, invalid, superseded and expired tokens, a stale
activation link, a request from outside the IP access list, ungranted shares, and unknown schemas and
tables. Unit tests cover the name rules, the token predicates, the catalog file and the status
transitions a broken source causes.

The Unity Catalog connector is tested against a stub Unity Catalog: a real HTTP server on a loopback
port answering canned JSON, so the request built, the header carrying the caller's token, and the
reading of the answer are all exercised the way a catalog would exercise them. Between them the cases
cover a table mapped to where it lives, a schema listed across two pages, each cloud's credential shape,
the four statuses that mean something particular, and every refusal: a name that could not be a Unity
Catalog name, a format and a view that cannot be served, and a vend with no table id to ask about.

It has also been driven against a real open-source Unity Catalog (`io.unitycatalog:unitycatalog-server`,
run from its published jars) with authorization enabled, which is worth repeating when the connector
changes: two providers holding tokens for different Unity Catalog principals, one privileged and one
not, sharing and failing to share the same table; a schema listed live with a CSV table in it passed
over; and the four statuses arriving from a server that meant them. What that cannot cover is a
successful vend, which needs a catalog configured against real cloud storage — asked for a table on a
local filesystem, Unity Catalog answers `200` with every credential block null, which is the reply the
connector reports as the catalog holding no storage configuration for that location.

The Delta read operations are exercised against hand-written logs under
`src/test/resources/delta-table`, which is mostly enough because nothing here opens a data file: a
table with three commits and a change data feed, one whose schema and then protocol change under a
reader, one using deletion vectors, one that only names the feature while leaving it off, one whose
vector outlived the setting that made it, and one whose log points at a file it does not own.

One of them, `stocked`, does have its parquet files on disk, and is the only reason to keep a binary
in the test resources: it lets a query be followed past the response to the bytes, checking that the
url handed out opens the file the log described at the size the response advertised. Nothing parses
the parquet — what a column holds is parquet's business — but the fixture also quietly guards against
a build that mangles a binary resource on its way into `target`. Between them they cover
both response formats and the negotiation that picks one, a snapshot and a `startingVersion` window,
the schema and protocol changes a stream is told about, a deletion vector signed beside its data file,
and the four refusals that matter — a client that cannot read what the table uses, parquet for a table
that uses it, a file whose vector no property declares, and a file outside the shared directory.

Reaching storage is tested for what a test can actually know without a cloud: that a driver answers
every scheme a shared table can live on, and that each provider's credentials arrive under the keys
its driver reads — including Google's, where the assertion follows the token all the way through the
provider class the connector instantiates. The tests' own `core-site.xml` points S3 at a port nothing
listens on, which is what lets the last case be exercised in milliseconds: what a recipient is told
when a table's storage does not answer.

The Iceberg catalog gets the same treatment against Iceberg metadata documents written to disk, since
that is all it reads: that a load hands back the document byte for byte and credentials in the keys an
Iceberg client looks under, that a namespace is a schema and holds only the share's Iceberg tables,
that a table a whole shared schema brought loads like any other, and that the four refusals come back
in Iceberg's error shape — another format's table, a catalog that cannot say where the metadata is, a
pointer out of the shared location, and a namespace deeper than a share has. A unit test pins the
credential key names per cloud, which are the whole contract with a client.

The published OpenAPI is tested as what it is, a contract a client is generated from: that the two
APIs stay separate documents, that each is described in the spelling it is served in, that the caller
the token identifies is not asked for as a parameter, and that a streamed response names its media
type once.

## Not implemented yet
- A catalog credential per catalog. One connector runs at a time, so a principal has one credential
  and nothing keys it to the catalog it is for.
- Obtaining a credential rather than being handed one. A provider pastes a long-lived secret in;
  neither OAuth token exchange nor an on-behalf-of grant is implemented, so nothing shortens its life
  or lets the server borrow the owner's identity without holding a secret of theirs.
- Volumes, agent skills and models. The protocol defines them and `AssetType` names them; only tables
  can be shared, whether directly or through the schema holding them.
- Any catalog but Unity Catalog and the file. `CatalogConnector` is the seam a Polaris or Iceberg REST
  implementation drops into, and neither exists yet — which also means an Iceberg table cannot be
  resolved through Unity Catalog, whose table formats do not include it.
- Unity Catalog's volume and model credential endpoints, and its staging and commit APIs for managed
  tables. Only what sharing a table needs is called.
- `auth_type: OIDC` on a recipient. It is accepted and stored, but only `TOKEN` is authenticated.
- Group membership. `PrincipalType.GROUP` exists so a share can be owned by a team, and nothing
  resolves a user to the groups it belongs to yet.
- Reading a deletion vector, or anything else a data file's bytes would say. Delta format hands a
  vector's file over signed, exactly as it hands over the data file, and it is the recipient's Delta
  library that applies it. A recipient that cannot do that has to read the table in `dir` access mode.
- `tags` on a file action, which delta format leaves behind. Nothing needed to read a table is in
  them, but a log that carries them is not reproduced exactly.
- Turning `readerfeatures` into anything but a check. A client that lists a feature is taken at its
  word, and a table using one the client did not list is refused; the server never rewrites a table
  into a shape a narrower client could read.
- A table whose log names files outside its own directory, which a shallow clone writes as absolute
  paths. Signing one would hand a recipient bytes from a location nobody shared, so url access mode
  refuses the table; `dir` access mode still serves what is genuinely under the shared root.
- Narrowing the metadata a recipient sees. The log's `schemaString` and the whole table
  `configuration` are passed through, so column metadata and every table property travel with them.
  The OSS server strips column metadata down to comments and reduces the configuration to
  `enableChangeDataFeed`, and this should do the same.
- Reading a Delta log on Azure's older `wasb` filesystem with a vended credential. Its SAS support
  mints tokens from an account key rather than accepting a token, so such a table is read with the
  deployment's own Hadoop configuration or not at all; `abfss` takes the SAS the catalog vends, and
  `dir` access mode works either way. What the other clouds take is under Reaching storage above.
- Signing a url for Google storage from the credentials the catalog vended, as AWS and Azure are. A V4
  signature is RSA, so it takes a service account key of the server's own — see Signing a url above
  for what that key can reach, and for what is served when there is none.
- Filtering by `predicateHints`, `jsonPredicateHints` or `limitHint`, and paginating a query. All are
  best-effort in the protocol, so every file of the version asked for is returned.
- Sharing part of a table: a share covers a table whole, with no partition filter, row filter or
  column mask. Those would mean the server standing between a recipient and the files rather than
  handing out the ones that already exist.
- Asynchronous queries and the query-info endpoint they need.
- Anything of the Iceberg REST catalog beyond the six operations the spec lists. There is no remote
  signing, no separate credentials endpoint to refresh a grant without loading the table again, and
  `snapshots=refs` is ignored — the metadata document is relayed whole, so a client gets every
  snapshot the table has. Multi-level namespaces do not exist here: a share's namespaces are its
  schemas.
- `loadTable` on a table whose storage this build cannot sign a url for, which now means one whose
  scheme has no signer — Google storage with no service account key configured, or a driver a
  deployment slimmed out. The metadata document is read through a signed url, so the signer url access
  mode needs is the same one this needs; `dir` access mode serves such a table regardless.
- Any outbound auth mode but bearer. A connector reaches its catalog over a url and presents the
  caller's token as `Authorization: Bearer`; nothing models OAuth token exchange, SigV4 or mutual TLS,
  and there is no proxy, retry or circuit-breaker configuration around the call.
- Provider-side catalog browsing. `listChildren` exists on the SPI for expanding a shared schema, but
  no admin endpoint exposes it, so a provider cannot list a catalog before deciding what to share.
- `Agent` and `Page` asset types, which are still community proposals.
- Audit logging of credential vending, and rate limiting.
