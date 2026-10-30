# Security Policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Report suspected vulnerabilities privately through GitHub's
[private vulnerability reporting](https://github.com/OpenSharing-IO/OpenSharing/security/advisories/new)
(the **Security → Advisories → Report a vulnerability** button on this
repository). This opens a private channel with the maintainers.

Please include, where possible:

- a description of the issue and its impact,
- the affected part of the specification or a reference implementation,
- steps to reproduce, and
- any suggested mitigation.

## What to expect

- We aim to **acknowledge** a report within **5 business days**, and in any
  case within **14 days**.
- We will work with you on an assessment and a coordinated disclosure timeline.
- We will credit reporters who wish to be acknowledged.
- Fixes for publicly known runtime vulnerabilities that already have a CVE (or
  equivalent) at release time are listed in the release notes and
  [CHANGELOG.md](./CHANGELOG.md).

<!-- TODO(maintainers): once the LF AI & Data security mailing list is provisioned
     and a security contact/team is named, add them here as an alternate reporting
     channel. -->

## What this project covers

OpenSharing is a protocol specification plus a reference server. Operators
deploy and harden their own deployments; this repository does not run a
hosted sharing service. The server vends short-lived, scoped storage
credentials and should be treated as trusted infrastructure. See
[RELEASE.md](./RELEASE.md) for versioning of the specification.

## Supported versions

Security fixes are applied to the current specification version and to the
`main` line of the reference server. Older tagged releases are not separately
maintained unless a release note says otherwise.
