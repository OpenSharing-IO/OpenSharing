# Contributing to OpenSharing

Thanks for your interest in contributing to OpenSharing — an open protocol for
sharing Data and AI assets, hosted by the LF AI & Data Foundation. Participation
is open to anyone who abides by the project's [Technical Charter](./GOVERNANCE.md)
and [Code of Conduct](./CODE_OF_CONDUCT.md).

## Ways to contribute

- **Discuss and propose** — open a [GitHub Issue](https://github.com/OpenSharing-IO/OpenSharing/issues)
  to report a problem, ask a question, or propose a change to the specification.
  Use the **Bug report** template for defects. Do not file public issues for
  security reports; follow [SECURITY.md](./SECURITY.md).
- **Contribute changes** — open a Pull Request against `main`. Specification
  changes should include the rationale and, where relevant, the impact on existing
  clients and servers. Each commit must be DCO-signed (`git commit -s`).

## Developer Certificate of Origin (DCO)

All contributions must be signed off under the
[Developer Certificate of Origin](https://developercertificate.org/), as required
by the Technical Charter (§7.b.ii). Sign off each commit with:

```bash
git commit -s -m "Your message"
```

This adds a `Signed-off-by: Your Name <your@email>` trailer certifying that you
have the right to submit the contribution under the project's license. If you
contribute as part of your employment, use your work email so the sign-off binds
your employer.

## Tests

New functionality in the reference server should include automated tests in the
same change. Run the suite with:

```bash
mvn -B -ntp -f server/pom.xml verify
```

CI runs that command on every pull request. Specification-only changes do not
need a Maven test, but should describe how a reviewer can check the protocol
text.

## Licensing

- **Code** is contributed and released under the [Apache License 2.0](./LICENSE)
  (full text also in [`LICENSES/Apache-2.0.txt`](./LICENSES/Apache-2.0.txt)).
- **Documentation** is licensed under
  [CC-BY-4.0](./LICENSES/CC-BY-4.0.txt).
- **Data**, where a contribution includes any, is licensed under
  [CDLA-Permissive-2.0](./LICENSES/CDLA-Permissive-2.0.txt).
- File-level SPDX annotations are declared in [`REUSE.toml`](./REUSE.toml).
  New files should either match those path rules or carry a short-form header:

  ```
  SPDX-FileCopyrightText: Copyright 2026 The OpenSharing Authors
  SPDX-License-Identifier: Apache-2.0
  ```

  Use `CC-BY-4.0` for documentation and `CDLA-Permissive-2.0` for data.

## Roles

Per the Technical Charter (§2.c), unless otherwise documented:

- **Contributor** — anyone in the technical community who contributes code,
  documentation, or other technical artifacts to the project.
- **Maintainer** — a Contributor who has earned the ability to commit to the
  project's repository. A Contributor may become a Maintainer by a majority vote
  of the Technical Steering Committee (TSC); a Maintainer may be removed by a
  majority vote of the TSC.

Current Maintainers are listed in [MAINTAINERS.md](./MAINTAINERS.md).

## Technical Steering Committee

The **TSC** holds all technical oversight of the project. **The TSC's voting
members are the project's Maintainers** as listed in
[MAINTAINERS.md](./MAINTAINERS.md). Governance — voting, quorum, meetings, and
amendments — is described in [GOVERNANCE.md](./GOVERNANCE.md) and defined
authoritatively by the [Technical Charter](./GOVERNANCE.md#technical-charter).

## Code of Conduct

This project follows the [LF Projects Code of Conduct](./CODE_OF_CONDUCT.md).
Report concerns to conduct@lfai.foundation.
