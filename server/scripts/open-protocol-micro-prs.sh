#!/usr/bin/env bash
set -euo pipefail

REPO="littlegrasscao/OpenSharing"
BASE="server/01-scaffold"

SLICES=(
  "base-entity|BaseEntity"
  "object-names|ObjectNames (+ test)"
  "error-codes|ErrorCodes"
  "error-response|ErrorResponse"
  "api-exception|ApiException"
  "admin-json|AdminJson"
  "protocol-media-type|ProtocolMediaType"
  "list-response|ListResponse"
  "offset-pageable|OffsetPageable"
  "offset-page|OffsetPage"
  "page-tokens|PageTokens"
  "protocol-action|ProtocolAction"
  "metadata-action|MetadataAction"
  "file-action|FileAction"
  "change-file-action|ChangeFileAction"
  "end-stream-action|EndStreamAction"
  "table-action|TableAction"
  "aws-credentials|AwsCredentials"
  "azure-user-delegation-sas|AzureUserDelegationSas"
  "gcp-oauth-token|GcpOauthToken"
  "r2-credentials|R2Credentials"
  "get-share-response|GetShareResponse"
  "schema|Schema"
  "share|Share"
  "table|Table"
  "profile-file|ProfileFile"
  "iceberg-config|IcebergConfig"
  "query-table-request|QueryTableRequest"
  "temporary-credentials|TemporaryCredentials"
  "temporary-credentials-request|TemporaryCredentialsRequest"
)

i=1
for slice in "${SLICES[@]}"; do
  slug="${slice%%|*}"
  label="${slice#*|}"
  head=$(printf "server/02-%02d-%s" "$i" "$slug")
  n=$(printf "%02d" "$i")
  gh pr create --repo "$REPO" --draft \
    --base "$BASE" \
    --head "$head" \
    --title "server: add ${label} (2.${n}/N)" \
    --body "Part **2.${n}** of splitting fork #1 into one-class PRs. Stacks on \`${BASE}\` → … → \`${head}\`.

Replaces [littlegrasscao#1](https://github.com/littlegrasscao/OpenSharing/pull/1).

## Test plan
- [x] \`cd server && mvn test\`"
  BASE="$head"
  i=$((i + 1))
done

echo "Created ${#SLICES[@]} draft PRs; final base for next stack PR is $BASE"
