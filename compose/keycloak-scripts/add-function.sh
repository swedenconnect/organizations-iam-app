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
# add-function.sh
#
# Convenience wrapper for the local Docker Compose environment. It calls
# keycloak/scripts/add-function.sh, the single implementation of this script, with the
# compose Keycloak URL and its CA certificate already supplied. Every other argument is
# passed through unchanged.
#
# Requires curl and python3 on the host, since the script runs here rather than inside a
# container.
#
# Against any other Keycloak, call keycloak/scripts/add-function.sh directly.

set -euo pipefail

# The URL the compose Keycloak is published on, and the certificate it serves.
KC_URL="https://local.dev.swedenconnect.se:17000"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

TARGET="${REPO_ROOT}/keycloak/scripts/add-function.sh"
CACERT="${REPO_ROOT}/compose/config/common/tls.crt"

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  cat <<USAGE
Add one or more functions to the client_functions attribute of an existing Keycloak client,
keeping the functions it already declares. Where set-client-functions.sh replaces the whole
list, this script only adds to it.

Runs against the compose Keycloak at ${KC_URL}.

Usage: $0 --realm <realm> --username <username> --password <password> --client-id <clientId> --function <function>

Options:
  --realm <realm>          Keycloak realm name
  --username <username>    Admin username for the Keycloak master realm
  --password <password>    Admin password for the Keycloak master realm
  --client-id <clientId>   Client ID of the target client
  --function <function>    Function to add, repeatable
  --functions <functions>  Comma-separated list of functions to add
  --help, -h               Show this help message

The function must already exist as a group under /functions in the realm.
USAGE
  exit 0
fi

[ -x "${TARGET}" ] || { echo "ERROR: ${TARGET} not found or not executable." >&2; exit 1; }
[ -r "${CACERT}" ] || { echo "ERROR: CA certificate ${CACERT} not readable." >&2; exit 1; }

exec "${TARGET}" --url "${KC_URL}" --cacert "${CACERT}" "$@"
