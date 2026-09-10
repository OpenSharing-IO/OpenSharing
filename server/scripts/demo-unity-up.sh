#!/usr/bin/env bash
# Brings up an open-source Unity Catalog beside this server, and gives it something worth sharing:
# an external Delta table over the parquet this repository already carries as a test fixture.
#
# Run this once, off camera. It leaves the catalog running and prints the command that starts
# OpenSharing against it; ./scripts/demo-unity.sh is then the walkthrough worth recording.
#
# Environment:
#   DEMO_HOME        where the catalog, its database and its data live   (default ~/.opensharing-demo)
#   UC_VERSION       Unity Catalog release to run                        (default 0.6.0)
#   UC_PORT          port the catalog listens on                         (default 8080)
#   MAVEN_PROXY_URL  Maven mirror; defaults to Databricks proxy on machines with no local mirror
#   MVN_SETTINGS     explicit settings.xml; overrides everything above           (default none)
#   UC_CLASSPATH     a classpath file from a local Unity Catalog build, used verbatim in place of
#                    resolving UC_VERSION from Maven — for a feature this server needs that has not
#                    reached a release yet, e.g. ~/unitycatalog/server-embedded/target/classpath
#                    after `sbt serverEmbedded/Compile/dependencyClasspath` (or however that
#                    checkout builds one); anything on the classpath at
#                    io.unitycatalog.server.UnityCatalogServer's main() will do
set -euo pipefail

DEMO_HOME="${DEMO_HOME:-$HOME/.opensharing-demo}"
UC_VERSION="${UC_VERSION:-0.6.0}"
UC_PORT="${UC_PORT:-8080}"
UC_URI="http://localhost:$UC_PORT/api/2.1/unity-catalog"
SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$SERVER_DIR/opensharing-server-core/src/test/resources/delta-table/stocked"

mkdir -p "$DEMO_HOME/etc/conf" "$DEMO_HOME/data"

# Maven: MVN_SETTINGS wins; else MAVEN_PROXY_URL when set or defaulted for corp; else the repo's
# local-mirror-settings.xml when this checkout carries one for machines that cannot reach Central.
if [[ -z "${MVN_SETTINGS:-}" ]]; then
  if [[ -n "${MAVEN_PROXY_URL:-}" ]]; then
    :
  elif [[ -f "$SERVER_DIR/.mvn/local-mirror-settings.xml" ]]; then
    MVN_SETTINGS="$SERVER_DIR/.mvn/local-mirror-settings.xml"
  else
    export MAVEN_PROXY_URL=https://maven-proxy.dev.databricks.com
  fi
fi
if [[ -z "${MVN_SETTINGS:-}" && -n "${MAVEN_PROXY_URL:-}" ]]; then
  MVN_SETTINGS="$DEMO_HOME/maven-settings.xml"
  cat > "$MVN_SETTINGS" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <mirrors>
    <mirror>
      <id>maven-proxy</id>
      <name>Maven proxy</name>
      <url>${MAVEN_PROXY_URL%/}</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
XML
fi

step() { printf '\n\033[1;36m== %s\033[0m\n' "$1"; }
note() { printf '   %s\n' "$1"; }

# Runs a command in a session of its own, so that whatever tidies up after this script — a terminal
# closing, a task runner reaping its process group — leaves it running. Python stands in for setsid,
# which macOS does not ship, and records the pid from inside the process that goes on to become the
# command, which is the only place it can be read off exactly.
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

step "Resolving Unity Catalog $UC_VERSION"
if [[ -n "${UC_CLASSPATH:-}" ]]; then
  cp "$UC_CLASSPATH" "$DEMO_HOME/classpath.txt"
  note "local build: $UC_CLASSPATH ($(tr ':' '\n' < "$DEMO_HOME/classpath.txt" | wc -l | tr -d ' ') jars, UC_VERSION ignored)"
elif [[ -s "$DEMO_HOME/classpath.txt" ]]; then
  note "already resolved: $DEMO_HOME/classpath.txt"
else
  if [[ -n "${MVN_SETTINGS:-}" ]]; then
    note "maven: $MVN_SETTINGS"
  elif [[ -n "${MAVEN_PROXY_URL:-}" ]]; then
    note "maven: $MAVEN_PROXY_URL"
  fi
  cat > "$DEMO_HOME/pom.xml" <<XML
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.opensharing.demo</groupId>
  <artifactId>unity-catalog-runner</artifactId>
  <version>0</version>
  <dependencies>
    <dependency>
      <groupId>io.unitycatalog</groupId>
      <artifactId>unitycatalog-server</artifactId>
      <version>$UC_VERSION</version>
    </dependency>
  </dependencies>
</project>
XML
  if ! mvn -q -f "$DEMO_HOME/pom.xml" ${MVN_SETTINGS:+-s "$MVN_SETTINGS"} \
    dependency:build-classpath -Dmdep.outputFile="$DEMO_HOME/classpath.txt"; then
    echo "Maven could not resolve Unity Catalog $UC_VERSION." >&2
    if [[ -n "${MAVEN_PROXY_URL:-}" ]]; then
      echo "If $MAVEN_PROXY_URL answered 403, you are off the corp network; unset MAVEN_PROXY_URL" >&2
      echo "or set MVN_SETTINGS to server/.mvn/local-mirror-settings.xml." >&2
    elif [[ -z "${MVN_SETTINGS:-}" ]]; then
      echo "Set MAVEN_PROXY_URL to your mirror, or MVN_SETTINGS to a settings.xml." >&2
    fi
    exit 1
  fi
  note "resolved $(tr ':' '\n' < "$DEMO_HOME/classpath.txt" | wc -l | tr -d ' ') jars"
fi

step "Laying down the data the catalog will point at"
if [[ -d "$DEMO_HOME/data/orders/_delta_log" ]]; then
  note "already there: $DEMO_HOME/data/orders"
else
  mkdir -p "$DEMO_HOME/data/orders"
  cp -R "$FIXTURE/." "$DEMO_HOME/data/orders/"
  note "a Delta table of 5 rows in 2 partitions, copied from the repository's fixture"
fi
step "Configuring the catalog"
cat > "$DEMO_HOME/etc/conf/server.properties" <<'PROPERTIES'
server.env=dev
# With authorization on, the catalog decides who may see and read each table, which is the whole
# point of asking it as the provider rather than as this server.
server.authorization=enable
PROPERTIES
cat > "$DEMO_HOME/etc/conf/hibernate.properties" <<'PROPERTIES'
hibernate.connection.driver_class=org.h2.Driver
hibernate.connection.url=jdbc:h2:file:./etc/db/h2db;DB_CLOSE_DELAY=-1
hibernate.dialect=org.hibernate.dialect.H2Dialect
hibernate.hbm2ddl.auto=update
hibernate.show_sql=false
hibernate.archive.autodetection=class
PROPERTIES
note "H2 on disk under $DEMO_HOME/etc/db, so the catalog keeps what you seed"

step "Starting it on port $UC_PORT"
if curl -s -o /dev/null -m 2 "$UC_URI/catalogs"; then
  note "something already answers on $UC_PORT, leaving it alone"
else
  # Started from DEMO_HOME because the catalog reads etc/conf and puts its database under etc/db,
  # both relative to where it runs. In a session of its own, so that whatever tidies up after this
  # script — a terminal closing, a task runner reaping its process group — leaves it running.
  (
    cd "$DEMO_HOME"
    detached unity-catalog.pid \
      java -cp "$(cat classpath.txt)" io.unitycatalog.server.UnityCatalogServer \
      > unity-catalog.log 2>&1 &
  )
  for _ in $(seq 60); do
    curl -s -o /dev/null -m 2 "$UC_URI/catalogs" && break
    sleep 1
  done
  curl -s -o /dev/null -m 2 "$UC_URI/catalogs" \
    || { echo "the catalog did not come up; see $DEMO_HOME/unity-catalog.log" >&2; exit 1; }
  note "pid $(cat "$DEMO_HOME/unity-catalog.pid"), log at $DEMO_HOME/unity-catalog.log"
fi

[[ -s "$DEMO_HOME/etc/conf/token.txt" ]] || {
  echo "the catalog wrote no admin token to $DEMO_HOME/etc/conf/token.txt, which it does at its" >&2
  echo "first start with authorization on; see $DEMO_HOME/unity-catalog.log" >&2
  exit 1
}
TOKEN="$(tr -d '\n' < "$DEMO_HOME/etc/conf/token.txt")"
uc() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$UC_URI$path" -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -X "$method" "$UC_URI$path" -H "Authorization: Bearer $TOKEN"
  fi
}
# The catalog's own admin, whose token it wrote to etc/conf/token.txt at first start. Everything
# seeded below is created and granted as them, and they are the provider the demo shares as.
CLAIMS="$(cut -d. -f2 <<<"$TOKEN" | tr '_-' '/+')"
case $(( ${#CLAIMS} % 4 )) in 2) CLAIMS+='==' ;; 3) CLAIMS+='=' ;; esac
ADMIN="$(base64 -d <<<"$CLAIMS" | jq -r .sub)"
note "the catalog's admin is '$ADMIN'"

step "Seeding a catalog, a schema and the grants to use them"
uc POST /catalogs '{"name":"main"}' > /dev/null
uc POST /schemas '{"name":"sales","catalog_name":"main"}' > /dev/null
uc PATCH /permissions/catalog/main \
  "{\"changes\":[{\"principal\":\"$ADMIN\",\"add\":[\"USE CATALOG\"]}]}" > /dev/null
uc PATCH /permissions/schema/main.sales \
  "{\"changes\":[{\"principal\":\"$ADMIN\",\"add\":[\"USE SCHEMA\",\"CREATE TABLE\"]}]}" > /dev/null
note "main.sales, with USE CATALOG, USE SCHEMA and CREATE TABLE on it"

column() { # name type position [partition_index]
  local extra=""
  [[ -n "${4:-}" ]] && extra=",\"partition_index\":$4"
  printf '{"name":"%s","type_text":"%s","type_name":"%s","type_json":"{\\"name\\":\\"%s\\",\\"type\\":\\"%s\\",\\"nullable\\":true,\\"metadata\\":{}}","position":%s,"nullable":true%s}' \
    "$1" "$(tr '[:upper:]' '[:lower:]' <<<"$2")" "$2" "$1" "$(tr '[:upper:]' '[:lower:]' <<<"$2")" "$3" "$extra"
}

step "Registering the external Delta table"
# Kept on disk because demo-unity.sh registers the very same table again, after showing what
# dropping it in the catalog does to a share.
cat > "$DEMO_HOME/orders-table.json" <<JSON
{"name":"orders","catalog_name":"main","schema_name":"sales",
 "table_type":"EXTERNAL","data_source_format":"DELTA",
 "storage_location":"file://$DEMO_HOME/data/orders",
 "columns":[$(column order_id LONG 0),$(column amount DOUBLE 1),$(column country STRING 2 0)]}
JSON
curl -sS -X POST "$UC_URI/tables" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @"$DEMO_HOME/orders-table.json" > /dev/null
uc PATCH /permissions/table/main.sales.orders \
  "{\"changes\":[{\"principal\":\"$ADMIN\",\"add\":[\"SELECT\"]}]}" > /dev/null
uc GET /tables/main.sales.orders \
  | jq -c '{full_name:"main.sales.orders",table_type,data_source_format,storage_location}'

step "Minting a second provider's token, whom the catalog grants nothing"
# Minted once and kept: the server stores it hashed when the walkthrough registers them, so a fresh
# token on every run would leave that principal unable to log in on the next one.
if [[ -s "$DEMO_HOME/etc/conf/mallory.token" ]]; then
  note "already minted: $DEMO_HOME/etc/conf/mallory.token"
else
  java -cp "$(cat "$DEMO_HOME/classpath.txt")" "$SERVER_DIR/scripts/MintToken.java" \
    "$DEMO_HOME/etc/conf" mallory@example.com > "$DEMO_HOME/etc/conf/mallory.token"
  note "a token the catalog authenticates and then refuses everything to"
fi

cat > "$DEMO_HOME/demo.env" <<ENV
# Written by demo-unity-up.sh; read by demo-unity.sh.
UC_URI=$UC_URI
UC_TOKEN=$TOKEN
UC_ADMIN=$ADMIN
UC_UNGRANTED_TOKEN=$(cat "$DEMO_HOME/etc/conf/mallory.token")
DEMO_DATA=$DEMO_HOME/data
ENV

step "Ready. Start OpenSharing against the catalog, in the terminal you will record"
MVN_CMD="mvn -q"
if [[ -n "${MVN_SETTINGS:-}" && -f "$MVN_SETTINGS" ]]; then
  MVN_CMD="mvn -q -s $MVN_SETTINGS"
elif [[ -f "$SERVER_DIR/.mvn/local-mirror-settings.xml" ]]; then
  MVN_CMD="mvn -q -s $SERVER_DIR/.mvn/local-mirror-settings.xml"
fi
cat <<NEXT

  cd $SERVER_DIR && $MVN_CMD -pl opensharing-server spring-boot:run -Dspring-boot.run.arguments="\\
    --server.port=8099 \\
    --opensharing.activation.external-base-url=http://localhost:8099 \\
    --opensharing.catalog.type=unity \\
    --opensharing.catalog.unity.uri=$UC_URI"

The port matters twice over: the catalog is on $UC_PORT, which is where this server would
otherwise sit, and the activation url a recipient is handed has to point back here.

No admin principal to configure: with catalog.type=unity, every provider-admin request is
authenticated by asking the catalog itself (GET \$UC_URI/opensharing/authorize) whose token this
is — the same call standalone mode has always made for asset resolution, now also made for
identity. \$UC_TOKEN (\$UC_ADMIN's own token, from demo.env) is what demo-unity.sh presents as
PROVIDER_TOKEN; this requires a Unity Catalog build carrying that endpoint (not yet in a release
as of this writing — see the OpenSharing-Unity Catalog embedding PR; run this script with
UC_CLASSPATH pointed at a local build in the meantime).

Its own store is a file under $SERVER_DIR/data, and it keeps what a run of the walkthrough
put there. Delete it before recording, so the take starts from nothing:  rm -rf data

Then, in the terminal you are recording:

  ./scripts/demo-unity.sh          # or PAUSE=ask, to step through it while you talk

To stop the catalog:  kill \$(cat $DEMO_HOME/unity-catalog.pid)
To start over:        rm -rf $DEMO_HOME
NEXT
