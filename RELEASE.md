# Release Process

OpenSharing is primarily a **specification** rather than a shipped binary, so a
"release" of the specification is a versioned, published state of the protocol
specification in [`spec/`](./spec/). The reference server under
[`server/`](./server/) also ships Maven artifacts, which are versioned and
tagged separately from the specification.

## Versioning

The specification uses semantic versioning (`MAJOR.MINOR.PATCH`):

- **MAJOR** — a backward-incompatible change to the protocol (e.g. a breaking
  change to an existing endpoint or object).
- **MINOR** — backward-compatible additions (e.g. a new asset type, a new
  optional field or endpoint).
- **PATCH** — clarifications, corrections, and editorial fixes with no behavioral
  change.

Asset types marked *"community proposal"* (e.g. Agent, Page) are not yet part of a
stable version and may change without a MAJOR bump until specified.

## Server artifacts

The server ships two Maven artifacts:

- **`opensharing-server-core`** — the embedding library. A host (such as Unity
  Catalog) embeds it and supplies a `CatalogConnector`.
- **`opensharing-server`** — the standalone server application.

Server artifacts are versioned independently of the specification and tagged as
`server-vMAJOR.MINOR.PATCH`.

Hosts may rely on the embedding API: the public classes in the `io.opensharing.*`
packages of `opensharing-server-core` (the root `Transactions` interface and the
`asset`, `auth`, `catalog`, `exception`, `http`, `protocol`, `recipient`,
`runtime`, and `share` packages), and `OpenSharing.entities()`, which returns
the JPA entities a host must register. Everything else is internal and may
change in any release.

Before 1.0.0, any `0.y.z` release — including a patch release — may change the
embedding API or the persisted schema incompatibly. Each such release lists the
affected APIs and the config, schema, and migration steps in its release notes.

From 1.0.0, the server artifacts follow semantic versioning:

- **MAJOR** — may break hosts.
- **MINOR** — additive and compatible with earlier 1.y.z releases.
- **PATCH** — compatible fixes only.

The database schema is created and migrated by Flyway on startup (see
`JpaTransactions`), and a schema change is a new migration for every supported
database. A host must not create or alter OpenSharing's tables itself.

## Release process

1. Changes land on `main` via reviewed, DCO-signed Pull Requests.
2. When the TSC agrees a set of changes constitutes a release, a Maintainer
   proposes the version number and a summary of changes.
3. On TSC approval, a Maintainer tags the release (`vMAJOR.MINOR.PATCH`) and
   publishes release notes via GitHub Releases.

## Cadence

Releases are made as the specification matures rather than on a fixed calendar
cadence. The TSC may adopt a regular cadence and will document it here if so.
