#!/usr/bin/env bash
# Builds the PetClinic Docker images locally from the checked-out commit and tags them with the short SHA.
# PetClinic is read-only input: the build only writes ignored target/ folders (and ~/.m2), never tracked files.
#
# Usage: infra/scripts/build-petclinic.sh [path-to-petclinic]
#   default path: project.path from config/analyzer.yml (relative to log2code/), else ../spring-petclinic-microservices
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"
ENV_FILE="$INFRA_DIR/.env"

# The JDK is only used to run Maven; the images themselves are built for Java 17 by PetClinic's Dockerfile.
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/env.sh" >/dev/null 2>&1

MODULES=(config-server discovery-server customers-service visits-service vets-service api-gateway)
PREFIX="$(sed -n 's/^PETCLINIC_IMAGE_PREFIX=//p' "$ENV_FILE" | tail -n 1)"
PREFIX="${PREFIX:-log2code}"

default_petclinic_path() {
  local cfg="$ROOT_DIR/config/analyzer.yml" p=""
  if [ -f "$cfg" ]; then
    p="$(awk '/^project:/{f=1;next} /^[^ #]/{f=0} f && $1=="path:"{print $2; exit}' "$cfg")"
  fi
  echo "${p:-../spring-petclinic-microservices}"
}

set_env_var() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    echo "${key}=${value}" >> "$ENV_FILE"
  fi
}

if [ $# -ge 1 ]; then
  PETCLINIC_DIR="$(cd "$1" && pwd)"
else
  PETCLINIC_DIR="$(cd "$ROOT_DIR" && cd "$(default_petclinic_path)" && pwd)"
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi
if ! git -C "$PETCLINIC_DIR" rev-parse --git-dir >/dev/null 2>&1; then
  echo "$PETCLINIC_DIR is not a git repository." >&2
  exit 1
fi
if [ -n "$(git -C "$PETCLINIC_DIR" status --porcelain)" ]; then
  echo "PetClinic working tree is not clean; the image would not match the commit. Aborting." >&2
  git -C "$PETCLINIC_DIR" status --short >&2
  exit 1
fi

SHA="$(git -C "$PETCLINIC_DIR" rev-parse HEAD)"
SHORT="${SHA:0:7}"
echo "PetClinic: $PETCLINIC_DIR"
echo "Commit   : $SHA (tag $SHORT)"

# Chaos Monkey 3.1.0 (PetClinic's own default) references org.springframework.boot.web.client.RestTemplateCustomizer,
# which no longer exists in Spring Boot 4, so every service started with the chaos-monkey profile crashes (ADR-004).
# Overriding the Maven property is build configuration only; no PetClinic file is modified.
CHAOS_MONKEY_VERSION="${CHAOS_MONKEY_VERSION:-4.0.0}"
# Chaos Monkey 4.0.0 additionally needs spring-boot-restclient (and its module spring-boot-http-client) on the classpath;
# they are added as a thin image layer below. The version must match the Spring Boot version inside the images
# (PetClinic 3858f9c uses 4.0.1).
RESTCLIENT_VERSION="${RESTCLIENT_VERSION:-4.0.1}"
EXTRA_ARTIFACTS=(spring-boot-restclient spring-boot-http-client)
CHAOS_MODULES=(customers-service visits-service vets-service)

# SKIP_MAVEN_BUILD=1 reuses the existing <prefix>/spring-petclinic-*:latest images (only re-applies the layer and the tags).
if [ "${SKIP_MAVEN_BUILD:-0}" != "1" ]; then
  # container.build.extraarg is deliberately left alone: its default (--load) is required to get the image into the local daemon.
  (cd "$PETCLINIC_DIR" && ./mvnw clean install -P buildDocker -DskipTests \
    "-Ddocker.image.prefix=$PREFIX" "-Dchaos-monkey-spring-boot.version=$CHAOS_MONKEY_VERSION")
fi

is_chaos_module() {
  local m
  for m in "${CHAOS_MODULES[@]}"; do [ "$m" = "$1" ] && return 0; done
  return 1
}

# Context for the thin layer: only the extra jars (from ~/.m2, else downloaded from Maven Central).
CHAOS_CTX="$(mktemp -d)"
trap 'rm -rf "$CHAOS_CTX"' EXIT
mkdir "$CHAOS_CTX/jars"
EXTRA_JARS=""
for a in "${EXTRA_ARTIFACTS[@]}"; do
  jar="$a-$RESTCLIENT_VERSION.jar"
  m2_jar="$HOME/.m2/repository/org/springframework/boot/$a/$RESTCLIENT_VERSION/$jar"
  if [ -f "$m2_jar" ]; then
    cp "$m2_jar" "$CHAOS_CTX/jars/$jar"
  else
    curl -fsSL -o "$CHAOS_CTX/jars/$jar" \
      "https://repo.maven.apache.org/maven2/org/springframework/boot/$a/$RESTCLIENT_VERSION/$jar"
  fi
  EXTRA_JARS="$EXTRA_JARS $jar"
done
EXTRA_JARS="${EXTRA_JARS# }"

for m in "${MODULES[@]}"; do
  base="$PREFIX/spring-petclinic-$m:latest"
  if is_chaos_module "$m"; then
    # Fail early if the images use another Spring Boot version than the restclient jar we are about to add.
    docker run --rm --entrypoint sh "$base" -c "test -f /application/BOOT-INF/lib/spring-boot-$RESTCLIENT_VERSION.jar" \
      || { echo "$base does not contain spring-boot-$RESTCLIENT_VERSION.jar; set RESTCLIENT_VERSION to the Spring Boot version of the images." >&2; exit 1; }
    docker build --quiet -f "$INFRA_DIR/docker/petclinic-chaos/Dockerfile" \
      --build-arg "BASE_IMAGE=$base" --build-arg "EXTRA_JARS=$EXTRA_JARS" \
      -t "$PREFIX/spring-petclinic-$m:$SHORT" "$CHAOS_CTX" >/dev/null
  else
    docker tag "$base" "$PREFIX/spring-petclinic-$m:$SHORT"
  fi
done

set_env_var PETCLINIC_TAG "$SHORT"
set_env_var PETCLINIC_COMMIT "$SHA"

if [ -n "$(git -C "$PETCLINIC_DIR" status --porcelain)" ]; then
  echo "WARNING: PetClinic working tree is dirty after the build:" >&2
  git -C "$PETCLINIC_DIR" status --short >&2
  exit 1
fi

echo "Done. Images:"
docker images --filter "reference=$PREFIX/spring-petclinic-*:$SHORT" --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'
echo "PETCLINIC_TAG=$SHORT and PETCLINIC_COMMIT=$SHA written to $ENV_FILE"
