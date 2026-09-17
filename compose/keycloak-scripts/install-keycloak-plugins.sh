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
# install-keycloak-plugins.sh
#
# Builds the Keycloak plugin modules and installs the provider JARs of the current build into
# the compose SPI directory, which the Keycloak container mounts as its providers directory.
#
# The JARs come from the distribution ZIP that keycloak/plugin-distribution assembles, so what
# is installed is exactly the provider set that module declares. Nothing is collected from
# build output, so a JAR left behind by an earlier build is not installed.
#
# Usage:
#   ./install-keycloak-plugins.sh
#
# The KEY_CLOAK_PLUGIN_DIR variable may point at the keycloak module root. It defaults to the
# keycloak directory of this repository.

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SPI_DIR="${SCRIPT_DIR}/../config/keycloak/spi"

KEY_CLOAK_PLUGIN_DIR="${KEY_CLOAK_PLUGIN_DIR:-${SCRIPT_DIR}/../../keycloak}"
DIST_TARGET_DIR="${KEY_CLOAK_PLUGIN_DIR}/plugin-distribution/target"

# ---------------------------------------------------------------------------
# 1. Build the plugin modules and the distribution ZIP
# ---------------------------------------------------------------------------
#
# The ZIP of a previous run is removed first. A failed build has to stop the script rather
# than leave an older ZIP to be installed as if it were current.

echo "==> Building the Keycloak plugins and the distribution ZIP..."
rm -f "${DIST_TARGET_DIR}"/*-plugins.zip

if ! ( cd "${KEY_CLOAK_PLUGIN_DIR}" && mvn -q clean package -DskipTests ); then
  echo "ERROR: The build failed. No JARs were installed." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 2. Locate the ZIP the build produced
# ---------------------------------------------------------------------------

shopt -s nullglob
DIST_ZIPS=( "${DIST_TARGET_DIR}"/*-plugins.zip )
shopt -u nullglob

if [ ${#DIST_ZIPS[@]} -eq 0 ]; then
  echo "ERROR: The build produced no plugin distribution ZIP in ${DIST_TARGET_DIR}." >&2
  exit 1
fi
if [ ${#DIST_ZIPS[@]} -gt 1 ]; then
  echo "ERROR: Several distribution ZIPs found in ${DIST_TARGET_DIR}:" >&2
  printf '       %s\n' "${DIST_ZIPS[@]}" >&2
  exit 1
fi

DIST_ZIP="${DIST_ZIPS[0]}"
echo "    $(basename "${DIST_ZIP}")"

# ---------------------------------------------------------------------------
# 3. Install the JARs from the ZIP into the SPI directory
# ---------------------------------------------------------------------------

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

unzip -q "${DIST_ZIP}" -d "${TEMP_DIR}"

# The ZIP holds a single top level directory with the JARs in it.
shopt -s nullglob
UNPACKED_JARS=( "${TEMP_DIR}"/*/*.jar )
shopt -u nullglob

if [ ${#UNPACKED_JARS[@]} -eq 0 ]; then
  echo "ERROR: No JARs found in $(basename "${DIST_ZIP}")." >&2
  exit 1
fi

echo "==> Installing JARs into ${SPI_DIR}..."
mkdir -p "${SPI_DIR}"
rm -f "${SPI_DIR}"/*.jar

for jar in "${UNPACKED_JARS[@]}"; do
  cp "${jar}" "${SPI_DIR}/"
  echo "    + $(basename "${jar}")"
done

echo ""
echo "==> Done. JARs installed in ${SPI_DIR}:"
ls -1 "${SPI_DIR}"/*.jar | xargs -I{} basename {}
echo ""
echo "    Restart Keycloak to load them:"
echo "      docker compose -f compose/docker-compose.yml restart keycloak"
