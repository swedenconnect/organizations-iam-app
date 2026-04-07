#!/bin/bash
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

set -euo pipefail

OIDC_SWEDEN_PLUGIN_GROUP="se.oidc.keycloak"
OIDC_SWEDEN_PLUGIN_ARTIFACT="oidc-sweden-claims-plugin"
OIDC_SWEDEN_PLUGIN_VERSION="1.0.1"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SPI_DIR="${SCRIPT_DIR}/../config/keycloak/spi"

KEY_CLOAK_PLUGIN_DIR="${KEY_CLOAK_PLUGIN_DIR:-${SCRIPT_DIR}/../../keycloak}"

# ---------------------------------------------------------------------------
# 1. Build local plugins
# ---------------------------------------------------------------------------

echo "==> Building local Keycloak plugins..."
pushd "${KEY_CLOAK_PLUGIN_DIR}" > /dev/null
mvn -q clean package -DskipTests
popd > /dev/null

# ---------------------------------------------------------------------------
# 2. Collect local plugin JARs (exclude original-*.jar shades and test JARs)
# ---------------------------------------------------------------------------

echo "==> Collecting local plugin JARs..."
LOCAL_JARS=$(find "${KEY_CLOAK_PLUGIN_DIR}" \
  -path "*/target/*.jar" \
  ! -name "original-*.jar" \
  ! -name "*-tests.jar" \
  ! -name "*-sources.jar")

# ---------------------------------------------------------------------------
# 3. Resolve the external OIDC Sweden claims plugin
# ---------------------------------------------------------------------------

OIDC_SWEDEN_COORDINATES="${OIDC_SWEDEN_PLUGIN_GROUP}:${OIDC_SWEDEN_PLUGIN_ARTIFACT}:${OIDC_SWEDEN_PLUGIN_VERSION}"

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

EXTERNAL_JAR="${TEMP_DIR}/${OIDC_SWEDEN_PLUGIN_ARTIFACT}-${OIDC_SWEDEN_PLUGIN_VERSION}.jar"

# Check local Maven cache before downloading from Maven Central.
M2_GROUP_PATH="${OIDC_SWEDEN_PLUGIN_GROUP//.//}"
M2_CACHED_JAR="${HOME}/.m2/repository/${M2_GROUP_PATH}/${OIDC_SWEDEN_PLUGIN_ARTIFACT}/${OIDC_SWEDEN_PLUGIN_VERSION}/${OIDC_SWEDEN_PLUGIN_ARTIFACT}-${OIDC_SWEDEN_PLUGIN_VERSION}.jar"

if [[ -f "${M2_CACHED_JAR}" ]]; then
  echo "==> Using cached ${OIDC_SWEDEN_COORDINATES} from local Maven repository..."
  cp "${M2_CACHED_JAR}" "${EXTERNAL_JAR}"
else
  echo "==> Downloading ${OIDC_SWEDEN_COORDINATES} from Maven Central..."
  mvn -q dependency:copy \
    -Dartifact="${OIDC_SWEDEN_COORDINATES}" \
    -DoutputDirectory="${TEMP_DIR}" \
    -Dmdep.useBaseVersion=true
fi

if [[ ! -f "${EXTERNAL_JAR}" ]]; then
  echo "ERROR: Expected JAR not found: ${EXTERNAL_JAR}" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 4. Install all JARs into spi/
# ---------------------------------------------------------------------------

echo "==> Installing JARs into ${SPI_DIR}..."
rm -f "${SPI_DIR}"/*.jar

for jar in ${LOCAL_JARS}; do
  cp "${jar}" "${SPI_DIR}/"
  echo "    + $(basename "${jar}")"
done

cp "${EXTERNAL_JAR}" "${SPI_DIR}/"
echo "    + $(basename "${EXTERNAL_JAR}")"

echo ""
echo "==> Done. JARs installed in ${SPI_DIR}:"
ls -1 "${SPI_DIR}"/*.jar | xargs -I{} basename {}
