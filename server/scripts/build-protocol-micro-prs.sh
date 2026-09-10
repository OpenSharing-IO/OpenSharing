#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
SOURCE="${1:-fork/server/02-protocol-http}"
BASE="${2:-server/01-scaffold}"

SLICES=(
  "base-entity|server/src/main/java/io/opensharing/BaseEntity.java"
  "object-names|server/src/main/java/io/opensharing/ObjectNames.java|server/src/test/java/io/opensharing/ObjectNamesTest.java"
  "error-codes|server/src/main/java/io/opensharing/http/ErrorCodes.java"
  "error-response|server/src/main/java/io/opensharing/http/ErrorResponse.java"
  "api-exception|server/src/main/java/io/opensharing/http/ApiException.java"
  "admin-json|server/src/main/java/io/opensharing/http/AdminJson.java"
  "protocol-media-type|server/src/main/java/io/opensharing/http/ProtocolMediaType.java"
  "list-response|server/src/main/java/io/opensharing/http/ListResponse.java"
  "offset-pageable|server/src/main/java/io/opensharing/http/OffsetPageable.java"
  "offset-page|server/src/main/java/io/opensharing/http/OffsetPage.java"
  "page-tokens|server/src/main/java/io/opensharing/http/PageTokens.java"
  "protocol-action|server/src/main/java/io/opensharing/protocol/ProtocolAction.java"
  "metadata-action|server/src/main/java/io/opensharing/protocol/MetadataAction.java"
  "file-action|server/src/main/java/io/opensharing/protocol/FileAction.java"
  "change-file-action|server/src/main/java/io/opensharing/protocol/ChangeFileAction.java"
  "end-stream-action|server/src/main/java/io/opensharing/protocol/EndStreamAction.java"
  "table-action|server/src/main/java/io/opensharing/protocol/TableAction.java"
  "aws-credentials|server/src/main/java/io/opensharing/protocol/AwsCredentials.java"
  "azure-user-delegation-sas|server/src/main/java/io/opensharing/protocol/AzureUserDelegationSas.java"
  "gcp-oauth-token|server/src/main/java/io/opensharing/protocol/GcpOauthToken.java"
  "r2-credentials|server/src/main/java/io/opensharing/protocol/R2Credentials.java"
  "get-share-response|server/src/main/java/io/opensharing/protocol/GetShareResponse.java"
  "schema|server/src/main/java/io/opensharing/protocol/Schema.java"
  "share|server/src/main/java/io/opensharing/protocol/Share.java"
  "table|server/src/main/java/io/opensharing/protocol/Table.java"
  "profile-file|server/src/main/java/io/opensharing/protocol/ProfileFile.java"
  "iceberg-config|server/src/main/java/io/opensharing/protocol/IcebergConfig.java"
  "query-table-request|server/src/main/java/io/opensharing/protocol/QueryTableRequest.java"
  "temporary-credentials|server/src/main/java/io/opensharing/protocol/TemporaryCredentials.java"
  "temporary-credentials-request|server/src/main/java/io/opensharing/protocol/TemporaryCredentialsRequest.java"
)

title_case() {
  echo "$1" | tr '-' ' ' | awk '{for (i = 1; i <= NF; i++) $i = toupper(substr($i, 1, 1)) substr($i, 2)} 1'
}

CURRENT="$BASE"
i=1
for slice in "${SLICES[@]}"; do
  name="${slice%%|*}"
  rest="${slice#*|}"
  IFS='|' read -r -a files <<<"$rest"
  branch=$(printf "server/02-%02d-%s" "$i" "$name")
  git checkout -q "$CURRENT"
  git branch -D "$branch" 2>/dev/null || true
  git checkout -q -b "$branch"
  git checkout -q "$SOURCE" -- "${files[@]}"
  git add "${files[@]}"
  git commit -q -m "Add $(title_case "$name")."
  CURRENT="$branch"
  i=$((i + 1))
done

git checkout -q "$CURRENT"
echo "Built ${#SLICES[@]} branches; tip=$CURRENT ($(git rev-parse --short HEAD))"
git diff --stat "$BASE"..HEAD | tail -1
