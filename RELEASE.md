# Release Process

OpenSharing is primarily a **specification** rather than a shipped binary, so a
"release" is a versioned, published state of the protocol specification in
[`spec/`](./spec/).

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

## Release process

1. Changes land on `main` via reviewed, DCO-signed Pull Requests.
2. When the TSC agrees a set of changes constitutes a release, a Maintainer
   proposes the version number and a summary of changes.
3. On TSC approval, a Maintainer tags the release (`vMAJOR.MINOR.PATCH`) and
   publishes release notes via GitHub Releases.

## Cadence

Releases are made as the specification matures rather than on a fixed calendar
cadence. The TSC may adopt a regular cadence and will document it here if so.

<!-- TODO(maintainers): confirm the versioning scheme and cadence with the TSC and
     finalize this document. -->
