#!/usr/bin/env bash
# End-to-end walkthrough of the OpenSharing reference server against a running instance.
#
#   1. terminal A:  cd server && mvn -pl opensharing-server spring-boot:run -Dspring-boot.run.arguments="\
#                     --opensharing.admin.principals[0].name=alice@example.com \
#                     --opensharing.admin.principals[0].bearer-token=dapi-alice-secret \
#                     --opensharing.admin.principals[1].name=bob@example.com \
#                     --opensharing.admin.principals[1].bearer-token=bob-secret"
#   2. terminal B:  ./scripts/demo.sh
#
# The provider principal must be listed in principals before the server starts: the local file
# catalog has nothing to delegate identity to, so this fixed list is the whole of it.
#
# Environment:
#   SERVER            base URL of the server                (default http://localhost:8080)
set -euo pipefail

SERVER="${SERVER:-http://localhost:8080}"
ADMIN="$SERVER/api/2.1/opensharing/provider"
PROTOCOL="$SERVER/api/2.1/opensharing"

ALICE="alice@example.com"
# Alice's one secret: it authenticates her to this server, and this server to the catalog as her when
# a recipient reads what she shared. In a real deployment it is her catalog token.
ALICE_TOKEN="dapi-alice-secret"
SHARE="demo_share_$RANDOM"
RECIPIENT="demo_partner_$RANDOM"

# Every admin call after registration authenticates as Alice; the service reads her identity from
# the token and uses it as owner, author and catalog caller.
admin() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$ADMIN$path" \
      -H "Authorization: Bearer $ALICE_TOKEN" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -X "$method" "$ADMIN$path" -H "Authorization: Bearer $ALICE_TOKEN"
  fi
}

step() { printf '\n\033[1;36m== %s\033[0m\n' "$1"; }

step "The provider '$ALICE' was provisioned from server configuration at startup"

step "Alice creates share '$SHARE'"
admin POST /shares \
  "{\"name\":\"$SHARE\",\"display_name\":\"Demo Share\",\"comment\":\"created by demo.sh\"}" \
  | jq '{share_id,name,owner_id,created_by}'

step "Alice adds tables; each is resolved in the catalog as Alice herself"
admin PATCH "/shares/$SHARE" '{
  "updates": [
    {"action":"ADD","data_object":{"name":"main.sales.orders","type":"TABLE","shared_as":"sales.orders"}},
    {"action":"ADD","data_object":{"name":"main.sales.forecast","shared_as":"sales.forecast"}},
    {"action":"ADD","data_object":{"name":"main.research.trial_results"}}
  ]}' | jq '.objects[] | {name,shared_as,type,source_format,status,storage_location}'

step "A table the catalog does not know about is refused"
admin PATCH "/shares/$SHARE" \
  '{"updates":[{"action":"ADD","data_object":{"name":"main.sales.ghost"}}]}' | jq .

step "Creating the recipient mints its token; only the activation URL comes back"
CREATED=$(admin POST /recipients \
  "{\"name\":\"$RECIPIENT\",\"auth_type\":\"TOKEN\",\"token_expiration_seconds\":7776000}")
echo "$CREATED" | jq '{recipient:.recipient.name,owner:.recipient.owner_id,token:.token}'
ACTIVATION_URL=$(echo "$CREATED" | jq -r .token.activation_url)

step "Alice grants SELECT on the share to the recipient"
admin PATCH "/shares/$SHARE/permissions" \
  "{\"changes\":[{\"recipient_name\":\"$RECIPIENT\",\"add\":[\"SELECT\"]}]}" \
  | jq '.items[] | {share_name,recipient_name,privilege,granted_at,granted_by}'

step "Another provisioned admin can see Alice's share, but only she may change it"
BOB="bob@example.com"
BOB_TOKEN="bob-secret"
curl -sS "$ADMIN/shares/$SHARE" -H "Authorization: Bearer $BOB_TOKEN" | jq '{name,owner_id}'
curl -sS -X DELETE "$ADMIN/shares/$SHARE" -H "Authorization: Bearer $BOB_TOKEN" | jq .
curl -sS -X POST "$ADMIN/recipients/$RECIPIENT/rotate-token" \
  -H "Authorization: Bearer $BOB_TOKEN" | jq .

step "Recipient opens the activation URL once and receives config.share"
PROFILE=$(curl -sS "$ACTIVATION_URL")
echo "$PROFILE" | jq '{shareCredentialsVersion,endpoint,icebergEndpoint,expirationTime,bearerToken:"<redacted>"}'
TOKEN=$(echo "$PROFILE" | jq -r .bearerToken)

step "The activation URL cannot be replayed"
curl -sS "$ACTIVATION_URL" | jq .

recipient() { curl -sS "$PROTOCOL$1" -H "Authorization: Bearer $TOKEN"; }

step "Recipient discovers what it can read, under the aliases it was shared as"
recipient /shares | jq '.items[] | {name,displayName}'
recipient "/shares/$SHARE/schemas" | jq '.items[].name'
recipient "/shares/$SHARE/all-tables" | jq '.items[] | {name,schema,location,accessModes}'

step "Recipient asks for scoped, short-lived storage credentials (dir access mode)"
curl -sS -X POST "$PROTOCOL/shares/$SHARE/schemas/sales/tables/orders/temporary-table-credentials" \
  -H "Authorization: Bearer $TOKEN" | jq .

step "Url access mode reads the log where the catalog put it: an S3 bucket nobody owns here"
recipient "/shares/$SHARE/schemas/sales/tables/orders/metadata" | jq .

step "An Iceberg engine browses the same share as a warehouse, through the Iceberg REST catalog"
recipient "/iceberg/v1/config?warehouse=$SHARE" | jq .
ICEBERG="/iceberg/v1/shares/$SHARE"
recipient "$ICEBERG/namespaces" | jq -c .
echo "only the Iceberg tables of the namespace, since the others it could not load:"
recipient "$ICEBERG/namespaces/sales/tables" | jq -c .

step "Loading one relays its metadata document, which here is a file on S3 nobody wrote"
recipient "$ICEBERG/namespaces/sales/tables/forecast" | jq .

step "Rotation mints a replacement and gives the old token a grace window"
NEXT_URL=$(admin POST "/recipients/$RECIPIENT/rotate-token" \
  '{"existing_token_expire_in_seconds":60}' | jq -r .activation_url)
NEW_TOKEN=$(curl -sS "$NEXT_URL" | jq -r .bearerToken)
echo "old token still works:"
recipient /shares | jq '.items | length'
echo "new token works too:"
curl -sS "$PROTOCOL/shares" -H "Authorization: Bearer $NEW_TOKEN" | jq '.items | length'
admin GET "/recipients/$RECIPIENT" \
  | jq -c '.tokens[] | {token_id,activated,expires_at,superseded_at,revoked_at}'

step "Rotating with no grace cuts the outgoing token off at once"
FINAL_URL=$(admin POST "/recipients/$RECIPIENT/rotate-token" \
  '{"existing_token_expire_in_seconds":0}' | jq -r .activation_url)
FINAL_TOKEN=$(curl -sS "$FINAL_URL" | jq -r .bearerToken)
echo "the superseded token is refused:"
curl -sS "$PROTOCOL/shares" -H "Authorization: Bearer $NEW_TOKEN" | jq -c .
echo "the current token works:"
curl -sS "$PROTOCOL/shares" -H "Authorization: Bearer $FINAL_TOKEN" | jq '.items | length'

step "An IP access list holds the recipient to the networks it is allowed on"
admin PATCH "/recipients/$RECIPIENT" '{"ip_access_list":["203.0.113.0/24"]}' \
  | jq -c '{name,ip_access_list}'
curl -sS "$PROTOCOL/shares" -H "Authorization: Bearer $FINAL_TOKEN" | jq -c .
admin PATCH "/recipients/$RECIPIENT" '{"ip_access_list":[]}' >/dev/null

step "What Alice can see about the grant, from either side"
admin GET "/shares/$SHARE/permissions" | jq -c '.items[] | {recipient_name,privilege}'
admin GET "/recipients/$RECIPIENT/share-permissions" | jq -c '.items[] | {share_name,privilege}'

step "An unauthenticated request is refused"
curl -sS "$PROTOCOL/shares" | jq .
