#!/usr/bin/env bash
#
# Copyright 2026 Sweden Connect
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# get-keycloak-plugins.sh
#
# Fetch the Keycloak provider JARs for a given version of this project and unpack them into a
# directory of your choosing, ready to be copied into a Keycloak providers directory.
#
# The JARs come from the distribution ZIP,
# se.swedenconnect.iam.keycloak:keycloak-plugin-distribution:<version>:zip:plugins, which
# defines the provider set of that release. A release version is fetched from Maven Central. A
# version ending in -SNAPSHOT is taken from the local Maven repository, since snapshots of this
# project are not published.
#
# Does not need a Keycloak to be running, and does not talk to one.
#
# Usage: see --help

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

VERSION=""
OUTPUT_DIR=""

DIST_GROUP="se.swedenconnect.iam.keycloak"
DIST_ARTIFACT="keycloak-plugin-distribution"
DIST_CLASSIFIER="plugins"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Fetch the Keycloak provider JARs for a version of this project.

The JARs are taken from the distribution ZIP
${DIST_GROUP}:${DIST_ARTIFACT}:<version>:zip:${DIST_CLASSIFIER}, which defines
the provider set of that release. A release version comes from Maven Central; a -SNAPSHOT
version comes from the local Maven repository, so build the project first in that case.

Usage: $0 [OPTIONS]

Options:
  --version <version>      Version of this project to fetch, e.g. 0.9.3 or 0.9.3-SNAPSHOT
  --output-dir <dir>       Directory to unpack the JARs into. Created if missing
  --help, -h               Show this help message

All parameters are optional; missing required values will be prompted for.

Requires mvn and unzip. Does not need a running Keycloak.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)      usage; exit 0 ;;
    --version)      VERSION="$2";    shift 2 ;;
    --output-dir)   OUTPUT_DIR="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

[ -z "${VERSION}"    ] && { read -r -p "Version (e.g. 0.9.3): " VERSION; }
[ -z "${OUTPUT_DIR}" ] && { read -r -p "Output directory: " OUTPUT_DIR; }

[ -z "${VERSION}"    ] && { echo "ERROR: A version is required." >&2; exit 1; }
[ -z "${OUTPUT_DIR}" ] && { echo "ERROR: An output directory is required." >&2; exit 1; }

command -v mvn   >/dev/null || { echo "ERROR: mvn is required but was not found on PATH." >&2; exit 1; }
command -v unzip >/dev/null || { echo "ERROR: unzip is required but was not found on PATH." >&2; exit 1; }

ZIP_NAME="${DIST_ARTIFACT}-${VERSION}-${DIST_CLASSIFIER}.zip"
COORDINATES="${DIST_GROUP}:${DIST_ARTIFACT}:${VERSION}:zip:${DIST_CLASSIFIER}"

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

echo ""
echo "==> Resolving ${COORDINATES}..."

# ---------------------------------------------------------------------------
# Resolve the ZIP
# ---------------------------------------------------------------------------

case "${VERSION}" in
  *-SNAPSHOT)
    # Snapshots of this project are not published, so the local Maven repository is the only
    # place they can come from.
    LOCAL_REPO=$(mvn -q help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null || echo "")
    [ -z "${LOCAL_REPO}" ] && LOCAL_REPO="${HOME}/.m2/repository"

    LOCAL_ZIP="${LOCAL_REPO}/${DIST_GROUP//.//}/${DIST_ARTIFACT}/${VERSION}/${ZIP_NAME}"

    if [ ! -f "${LOCAL_ZIP}" ]; then
      echo "ERROR: ${VERSION} is a snapshot, and it is not in the local Maven repository." >&2
      echo "       Looked for: ${LOCAL_ZIP}" >&2
      echo "" >&2
      echo "       Snapshots are never published, so build and install the project first:" >&2
      echo "         mvn -DskipTests install" >&2
      echo "" >&2
      echo "       Or pass a released version instead." >&2
      exit 1
    fi

    echo "    Taken from the local Maven repository."
    cp "${LOCAL_ZIP}" "${TEMP_DIR}/${ZIP_NAME}"
    ;;

  *)
    # A release. mvn takes it from the local repository if it is already there, and from Maven
    # Central otherwise.
    if ! mvn -q dependency:copy \
        -Dartifact="${COORDINATES}" \
        -DoutputDirectory="${TEMP_DIR}" \
        -Dmdep.useBaseVersion=true; then
      echo "ERROR: Could not resolve ${COORDINATES}." >&2
      echo "       Check the version number, and that it has been released to Maven Central." >&2
      exit 1
    fi
    echo "    Resolved."
    ;;
esac

if [ ! -f "${TEMP_DIR}/${ZIP_NAME}" ]; then
  echo "ERROR: Expected ${ZIP_NAME} was not produced." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Unpack
# ---------------------------------------------------------------------------

mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR_ABS="$( cd "${OUTPUT_DIR}" && pwd )"

UNPACK_DIR="${TEMP_DIR}/unpacked"
unzip -q "${TEMP_DIR}/${ZIP_NAME}" -d "${UNPACK_DIR}"

# The ZIP holds a single top level directory with the JARs in it.
shopt -s nullglob
JARS=( "${UNPACK_DIR}"/*/*.jar )
shopt -u nullglob

if [ ${#JARS[@]} -eq 0 ]; then
  echo "ERROR: No JARs found in ${ZIP_NAME}." >&2
  exit 1
fi

echo "==> Writing ${#JARS[@]} provider JARs to ${OUTPUT_DIR_ABS}..."
for jar in "${JARS[@]}"; do
  cp "${jar}" "${OUTPUT_DIR_ABS}/"
  echo "    + $(basename "${jar}")"
done

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

cat <<EOF

==> Done. The provider JARs for ${VERSION} are in ${OUTPUT_DIR_ABS}.

    Next, on the Keycloak host:

      1. Copy them into Keycloak's providers directory:
           cp ${OUTPUT_DIR_ABS}/*.jar /opt/keycloak/providers/

      2. Rebuild Keycloak, which is required whenever a provider is added or changed:
           /opt/keycloak/bin/kc.sh build

      3. Restart Keycloak:
           /opt/keycloak/bin/kc.sh start --optimized

    A provider stays invisible to Keycloak until the rebuild has happened. Once Keycloak is
    back up, bootstrap-realm.sh reports whether it can see every provider type it needs.
EOF
