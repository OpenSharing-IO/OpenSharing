#!/usr/bin/env bash
# Brings up Unity Catalog OSS with OpenSharing embedded in the same JVM.
#
# Run this once before recording or walking through ./scripts/demo-embedded.sh.
#
# Environment:
#   DEMO_HOME        runtime data and config               (default ~/.opensharing-embedded-demo)
#   UC_ROOT          path to a unitycatalog checkout       (default ~/unitycatalog)
#   UC_PORT          the one public port — UC and OpenSharing both answer here (default 8080)
#   OS_INTERNAL_PORT OpenSharing's own port, bound to 127.0.0.1 and reached only through UC_PORT
#                    (default 8099)
#   MVN_SETTINGS     Maven settings for OpenSharing build  (default server/.mvn/local-mirror-settings.xml when present)
#   MAVEN_PROXY_URL  Maven mirror for UC sbt               (default Databricks proxy when unset)
#   SERVER_SECRET    OpenSharing's own identity for on-behalf-of catalog access; only load-bearing
#                    with server.authorization=enable      (default: a fixed demo secret)
#   PROVIDER         name the provider's minted token is issued for (default admin@unitycatalog.local)
set -euo pipefail

DEMO_HOME="${DEMO_HOME:-$HOME/.opensharing-embedded-demo}"
UC_ROOT="${UC_ROOT:-$HOME/unitycatalog}"
UC_PORT="${UC_PORT:-8080}"
OS_INTERNAL_PORT="${OS_INTERNAL_PORT:-8099}"
UC_URI="http://localhost:$UC_PORT/api/2.1/unity-catalog"
SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$SERVER_DIR/opensharing-server-core/src/test/resources/delta-table/stocked"
SERVER_SECRET="${SERVER_SECRET:-demo-opensharing-server-secret}"
PROVIDER="${PROVIDER:-admin@unitycatalog.local}"

step() { printf '\n\033[1;36m== %s\033[0m\n' "$1"; }
note() { printf '   %s\n' "$1"; }

detached() { # pid-file command...
  python3 -c '
import os, sys
pid_file, argv = sys.argv[1], sys.argv[2:]
os.setsid()
with open(pid_file, "w") as f:
    f.write(str(os.getpid()) + "\n")
os.execvp(argv[0], argv)
' "$@"
}

embedded_classpath() {
  local raw="$UC_ROOT/server-embedded/target/classpath"
  [[ -s "$raw" ]] || {
    echo "Missing $raw; run the UC build step below first." >&2
    exit 1
  }
  tr ':' '\n' < "$raw" | grep -Ev 'log4j-to-slf4j|logback-|jul-to-slf4j' | paste -sd: -
}

if [[ -z "${MVN_SETTINGS:-}" && -f "$SERVER_DIR/.mvn/local-mirror-settings.xml" ]]; then
  MVN_SETTINGS="$SERVER_DIR/.mvn/local-mirror-settings.xml"
fi
MVN_CMD=(mvn -q)
if [[ -n "${MVN_SETTINGS:-}" ]]; then
  MVN_CMD+=(-s "$MVN_SETTINGS")
fi

if [[ -z "${MAVEN_PROXY_URL:-}" ]]; then
  export MAVEN_PROXY_URL=https://maven-proxy.dev.databricks.com
fi

step "Installing OpenSharing server-core into ~/.m2"
( cd "$SERVER_DIR" && "${MVN_CMD[@]}" install -DskipTests -pl opensharing-server-core )
note "io.opensharing:opensharing-server-core:0.1.0-SNAPSHOT"

step "Building Unity Catalog with embedded OpenSharing classpath"
[[ -d "$UC_ROOT" ]] || {
  echo "UC_ROOT=$UC_ROOT does not exist; clone unitycatalog and set UC_ROOT." >&2
  exit 1
}
(
  cd "$UC_ROOT"
  MAVEN_PROXY_URL="$MAVEN_PROXY_URL" ./build/sbt -batch \
    "server/compile" "serverSharing/compile" "serverEmbedded/exportEmbeddedClasspath"
)
note "classpath at $UC_ROOT/server-embedded/target/classpath"

step "Laying down demo data"
mkdir -p "$DEMO_HOME/etc/conf" "$DEMO_HOME/data/orders"
if [[ -d "$DEMO_HOME/data/orders/_delta_log" ]]; then
  note "already there: $DEMO_HOME/data/orders"
else
  cp -R "$FIXTURE/." "$DEMO_HOME/data/orders/"
  note "Delta fixture: 5 rows in 2 partitions"
fi

step "Configuring Unity Catalog (embedded OpenSharing routed through port $UC_PORT)"
# One public port: UC's own URLTranscoderVerticle forwards a request under protocol-prefix (and
# the /provider, /activation paths derived from it) to OpenSharing's own port instead of UC's, so
# a client never needs to know a second port exists. That internal port is bound to 127.0.0.1 —
# nothing reaches it except the transcoder on this same host. The external address a recipient's
# activation link and config.share point at is derived from UC's own public port too, not
# configured separately.
#
# Embedded OpenSharing has no datasource config of its own either: it reads UC's own
# etc/conf/hibernate.properties (hibernate.connection.url/username/password/driver_class) and
# connects to that same database. Safe within one JVM (H2 keeps one shared in-memory Database
# instance per canonical file path per process; a real database server is designed for exactly
# this), and none of OpenSharing's tables (all prefixed os_) collide with UC's (uc_-prefixed) ones.
#
# No stored credential of any kind either: OpenSharing asks UC's own new authorize API whose
# token this is on every provider-admin request, and (server.opensharing.server-secret) presents
# its own identity instead of a token for a recipient's read, where there is no owner token to
# present. This demo runs with authorization disabled, so the secret is set but not load-bearing
# — see server.authorization=enable in the README for what it is for.
cat > "$DEMO_HOME/etc/conf/server.properties" <<PROPERTIES
server.env=dev
server.authorization=disable
server.opensharing.enabled=true
server.opensharing.port=$OS_INTERNAL_PORT
server.opensharing.protocol-prefix=/api/2.1/opensharing
server.opensharing.server-secret=$SERVER_SECRET
PROPERTIES
cat > "$DEMO_HOME/etc/conf/hibernate.properties" <<'PROPERTIES'
hibernate.connection.driver_class=org.h2.Driver
hibernate.connection.url=jdbc:h2:file:./etc/db/h2db;DB_CLOSE_DELAY=-1
hibernate.dialect=org.hibernate.dialect.H2Dialect
hibernate.hbm2ddl.auto=update
hibernate.show_sql=false
hibernate.archive.autodetection=class
PROPERTIES
note "one H2 file at $DEMO_HOME/etc/db/h2db, shared by UC and embedded OpenSharing"

step "Starting Unity Catalog + embedded OpenSharing, both on port $UC_PORT"
if curl -s -o /dev/null -m 2 "$UC_URI/catalogs"; then
  note "something already answers on $UC_PORT, leaving it alone"
else
  CP="$(embedded_classpath)"
  (
    cd "$DEMO_HOME"
    detached unity-catalog.pid \
      java -cp "$CP" io.unitycatalog.server.UnityCatalogServer -p "$UC_PORT" \
      > unity-catalog.log 2>&1 &
  )
  for _ in $(seq 90); do
    curl -s -o /dev/null -m 2 "$UC_URI/catalogs" \
      && curl -s -o /dev/null -m 2 "http://localhost:$UC_PORT/api/2.1/opensharing/provider/shares" \
        -H "Authorization: Bearer demo" \
      && break
    sleep 2
  done
  curl -s -o /dev/null -m 2 "$UC_URI/catalogs" \
    || { echo "UC did not come up; see $DEMO_HOME/unity-catalog.log" >&2; exit 1; }
  # This is the same port UC itself just answered on: proof the transcoder is routing
  # /api/2.1/opensharing/provider to embedded OpenSharing rather than to Armeria, which knows
  # no such path.
  curl -s -o /dev/null -m 2 "http://localhost:$UC_PORT/api/2.1/opensharing/provider/shares" \
    -H "Authorization: Bearer demo" \
    || { echo "OpenSharing did not come up; see $DEMO_HOME/unity-catalog.log" >&2; exit 1; }
  UC_PID="$(cat "$DEMO_HOME/unity-catalog.pid")"
  note "pid $UC_PID, log at $DEMO_HOME/unity-catalog.log"
  if command -v lsof >/dev/null 2>&1; then
    note "one process, every port it holds — only *:$UC_PORT is public, the rest are 127.0.0.1:"
    lsof -a -p "$UC_PID" -iTCP -sTCP:LISTEN -nP
  else
    note "lsof not found; skipping the pid/port proof above"
  fi
fi

column() { # name type position [partition_index]
  local extra=""
  [[ -n "${4:-}" ]] && extra=",\"partition_index\":$4"
  printf '{"name":"%s","type_text":"%s","type_name":"%s","type_json":"{\\"name\\":\\"%s\\",\\"type\\":\\"%s\\",\\"nullable\\":true,\\"metadata\\":{}}","position":%s,"nullable":true%s}' \
    "$1" "$(tr '[:upper:]' '[:lower:]' <<<"$2")" "$2" "$1" "$(tr '[:upper:]' '[:lower:]' <<<"$2")" "$3" "$extra"
}

step "Seeding catalog main.sales.orders"
uc() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$UC_URI$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -X "$method" "$UC_URI$path"
  fi
}
uc POST /catalogs '{"name":"main"}' >/dev/null || true
uc POST /schemas '{"name":"sales","catalog_name":"main"}' >/dev/null || true
cat > "$DEMO_HOME/orders-table.json" <<JSON
{"name":"orders","catalog_name":"main","schema_name":"sales",
 "table_type":"EXTERNAL","data_source_format":"DELTA",
 "storage_location":"file://$DEMO_HOME/data/orders",
 "columns":[$(column order_id LONG 0),$(column amount DOUBLE 1),$(column country STRING 2 0)]}
JSON
uc POST /tables "$(cat "$DEMO_HOME/orders-table.json")" >/dev/null || true
uc GET /tables/main.sales.orders \
  | jq -c '{full_name:"main.sales.orders",table_type,data_source_format,storage_location}'

step "Minting the provider's token"
# OpenSharing calls UC's own GET .../opensharing/authorize with whatever token a provider-admin
# request presents, on every request — never storing or configuring an identity of its own (see
# io.opensharing.catalog.unity.UnityCatalogProviderIdentityResolver). Authorization is disabled in
# this demo, so that call doesn't verify the signature either, only reads this token's own JWT
# subject — minting a real JWT (rather than an arbitrary string) with subject $PROVIDER is what
# attributes shares and grants to that name, with no UC-side config saying so.
PROVIDER_TOKEN="$(java -cp "$(embedded_classpath)" "$SERVER_DIR/scripts/MintToken.java" \
  "$DEMO_HOME/etc/conf" "$PROVIDER")"
note "signed by UC's own key, subject '$PROVIDER'"

cat > "$DEMO_HOME/demo.env" <<ENV
# Written by demo-embedded-up.sh; sourced by demo-embedded.sh.
# Only what demo-embedded.sh actually reads: it derives its own ADMIN and PROTOCOL from SERVER
# (see its \${ADMIN:-...} / \${PROTOCOL:-...} defaults), so there is nothing to keep in sync here
# if that path scheme ever changes. UC_URI, UC_PORT, OS_INTERNAL_PORT and DEMO_DATA are this
# script's own local variables, not something demo-embedded.sh reads.
SERVER=http://localhost:$UC_PORT
PROVIDER=$PROVIDER
PROVIDER_TOKEN=$PROVIDER_TOKEN
ENV

step "Ready"
ADMIN_URL="http://localhost:$UC_PORT/api/2.1/opensharing/provider"
PROTOCOL_URL="http://localhost:$UC_PORT/api/2.1/opensharing"
cat <<NEXT

  $SERVER_DIR/scripts/demo-embedded.sh
  # (it finds and sources $DEMO_HOME/demo.env on its own; nothing to source by hand)

  One address for both — dispatched by URL path, not by port:
    Unity Catalog:   $UC_URI
    Provider admin:  $ADMIN_URL  (Authorization: Bearer \$PROVIDER_TOKEN)
    Protocol base:   $PROTOCOL_URL

  OpenSharing's own port ($OS_INTERNAL_PORT) is internal — bound to 127.0.0.1, reached only
  through $UC_PORT above.

  Stop:  kill \$(cat $DEMO_HOME/unity-catalog.pid)
  Reset: rm -rf $DEMO_HOME
NEXT
