# Contributing to OpenSharing

Thanks for your interest in contributing to OpenSharing — an open protocol for
sharing Data and AI assets, hosted by the LF AI & Data Foundation. Participation
is open to anyone who abides by the project's [Technical Charter](./GOVERNANCE.md)
and [Code of Conduct](./CODE_OF_CONDUCT.md).

**Table of Contents**

- [Governance](#governance)
- [Maintainers](#maintainers)
- [Contribution process](#contribution-process)
- [Contribution guidelines](#contribution-guidelines)
  - [Write designs for significant changes](#write-designs-for-significant-changes)
  - [Keep the protocol backwards compatible](#keep-the-protocol-backwards-compatible)
  - [Spec, server, and tests](#spec-server-and-tests)
  - [Java style](#java-style)
- [Setting up the repository](#setting-up-the-repository)
- [Developing and testing](#developing-and-testing)
  - [Specification](#specification)
  - [Reference server](#reference-server)
  - [Running tests](#running-tests)
  - [Running the server locally](#running-the-server-locally)
- [Sign your work](#sign-your-work)
- [Licensing](#licensing)
- [Code of Conduct](#code-of-conduct)

## Governance

Governance of OpenSharing is conducted by the Technical Steering Committee
(TSC). Voting members of the TSC are the project's Maintainers, listed in
[MAINTAINERS.md](./MAINTAINERS.md).

The Technical Charter (adopted June 9, 2026) is the authority on roles, voting,
licensing, and IP:

- [`CHARTER.md`](./CHARTER.md) — readable transcription
- [`charter/OpenSharing-Technical-Charter-2026-06-09.pdf`](./charter/OpenSharing-Technical-Charter-2026-06-09.pdf) — executed copy of record
- [GOVERNANCE.md](./GOVERNANCE.md) — summary of the Charter

TSC meetings are open to the public. How the TSC votes, what constitutes quorum,
and how the Charter is amended are described in [GOVERNANCE.md](./GOVERNANCE.md).

## Maintainers

OpenSharing is maintained by the people listed in
[MAINTAINERS.md](./MAINTAINERS.md), with contributions from the wider community.

Per the Technical Charter (§2.c), unless otherwise documented:

- **Contributor** — anyone in the technical community who contributes code,
  documentation, or other technical artifacts to the project.
- **Maintainer** — a Contributor who has earned the ability to commit to the
  project's repository. A Contributor may become a Maintainer by a majority vote
  of the TSC; a Maintainer may be removed by a majority vote of the TSC.

## Contribution process

1. **File a GitHub issue** for a bug, a documentation fix, a protocol question,
   or a feature / new asset type. For security reports, follow
   [SECURITY.md](./SECURITY.md) instead of opening a public issue.

2. **Wait for feedback** before implementing a significant change — especially
   anything that alters the protocol REST surface, profile format, or credential
   model. Maintainers triage issues and can help choose an approach.

3. **Open a pull request** against `main` from a branch on your fork (or a
   same-repo branch if you have write access). Keep the PR focused. Sign off
   every commit under the [DCO](#sign-your-work).

4. **Address review.** GitHub Actions runs `mvn verify` on PRs that include the
   server. Cloud-backed integration tests run when CI secrets are present and
   are skipped otherwise.

Once the PR is merged, the change is on `main` and is included in the next
specification or server release. See [RELEASE.md](./RELEASE.md) for versioning.

## Contribution guidelines

### Write designs for significant changes

For a change that is more than a localized bug fix, outline the design in the
GitHub issue (or a linked document) and discuss it with a Maintainer before
investing heavily in implementation. This is particularly important if the
change:

- Introduces or alters a protocol REST endpoint, request/response field, or
  profile file field. Existing [Delta Sharing](https://delta.io/sharing/)
  clients are expected to keep working against OpenSharing, so protocol changes
  affect every compliant client and server.
- Adds a new asset type, or moves an asset type from *community proposal* to
  specified.
- Adds a user-facing provider or recipient API.
- Adds a new library dependency to the reference server.
- Changes how credentials are vended or how catalog connectors are embedded.

### Keep the protocol backwards compatible

Providers and recipients depend on specific protocol behavior. Prefer additive,
optional fields and new endpoints over renaming or removing existing ones.
Breaking changes require a **MAJOR** version bump of the specification; see
[RELEASE.md](./RELEASE.md).

If you are unsure of compatibility implications, ask in the issue or on the PR.

### Spec, server, and tests

- Protocol behavior is defined in [`spec/`](./spec/). When you change how a
  recipient or provider API works, update the corresponding spec document in the
  same PR.
- The Java reference server in [`server/`](./server/) should match the spec.
  New server behavior needs tests: JUnit 5 classes named `*Test.java`.
- Community proposals (for example Agent and Page) may change without a MAJOR
  bump until they are specified.

### Java style

There is no separate formatter required in CI. Match the surrounding code:

- Java 21, package `io.opensharing`
- JUnit 5 for tests
- Prefer small, named types and comments that explain *why*, not a restatement
  of the code
- Do not add Maven dependencies without discussing them on the issue or PR

## Setting up the repository

```bash
git clone git@github.com:<username>/OpenSharing.git
cd OpenSharing
git remote add upstream git@github.com:OpenSharing-IO/OpenSharing.git
```

HTTPS clone works as well:

```bash
git clone https://github.com/<username>/OpenSharing.git
```

Configure the name and email Git will put on commits (required for DCO
sign-off):

```bash
git config --global user.name "Your Name"
git config --global user.email yourname@example.com
```

If you contribute as part of your employment, use your work email so the
sign-off binds your employer.

## Developing and testing

### Specification

Protocol documents live under [`spec/protocols/`](./spec/protocols/). Start with
[OVERVIEW.md](./spec/protocols/OVERVIEW.md) for the profile format and naming
rules. Asset types each have their own file (tables, volumes, models, skills,
and so on).

Edits are Markdown. Keep request/response examples consistent with neighboring
endpoints.

### Reference server

The standalone server and embeddable library are Maven modules under
[`server/`](./server/):

| Module | Role |
|---|---|
| `opensharing-server-core` | Embeddable protocol library |
| `opensharing-server` | Standalone Armeria server |

Requirements:

- [JDK 21](https://adoptium.net/) (Temurin or equivalent)
- [Apache Maven](https://maven.apache.org/download.cgi) 3.9+

### Running tests

From the repository root:

```bash
mvn -f server/pom.xml verify
```

`verify` compiles, runs unit and integration tests, and (for
`opensharing-server`) checks third-party licenses.

A single test class:

```bash
mvn -f server/pom.xml -pl opensharing-server -am test -Dtest=ProtocolTablesTest
```

Cloud listing tests against S3, Azure, and GCS run only when the matching
environment variables are set (otherwise JUnit skips them):

| Variable | Used for |
|---|---|
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | S3 |
| `AZURE_TEST_ACCOUNT_KEY` | Azure Data Lake SAS |
| `GOOGLE_APPLICATION_CREDENTIALS` | GCS OAuth |

Do not commit secrets or service-account JSON.

### Running the server locally

Package the executable JAR, then start it. `server.public-url` is required so
activation links and profile endpoints are absolute:

```bash
mvn -f server/pom.xml -pl opensharing-server -am package -DskipTests
java -Dserver.public-url=http://127.0.0.1:8080 \
  -jar server/opensharing-server/target/opensharing-server-0.1.0-SNAPSHOT-exec.jar
```

Settings come from `server/opensharing-server/src/main/resources/server.properties`.
A JVM system property (`-Dserver.port=9090`) or an environment variable
(`SERVER_PORT=9090`) overrides a key. Program arguments are not supported.

The default local catalog is `classpath:local-catalog.yml`. Point it at another
file with `-Dcatalog.local.file=/path/to/catalog.yml`.

## Sign your work

All contributions must be signed off under the
[Developer Certificate of Origin](https://developercertificate.org/), as required
by the Technical Charter (§7.b.ii). If you can certify the DCO, add a trailer
to every commit:

```text
Signed-off-by: Jane Smith <jane.smith@email.com>
```

Use your real name (no pseudonyms). Sign automatically after setting
`user.name` and `user.email`:

```bash
git commit -s -m "Your message"
```

A PR without DCO sign-off on every commit cannot be merged.

## Licensing

- **Code** is contributed and released under the [Apache License 2.0](./LICENSE).
- **Documentation** is licensed under [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/).
- **Data**, where a contribution includes any, is licensed under
  [CDLA-Permissive-2.0](https://cdla.dev/permissive-2-0).
- Contributed files should carry an [SPDX](https://spdx.dev/) short-form license
  identifier in their header where practical.

## Code of Conduct

This project follows the [LF Projects Code of Conduct](./CODE_OF_CONDUCT.md).
Report concerns to conduct@lfai.foundation.
