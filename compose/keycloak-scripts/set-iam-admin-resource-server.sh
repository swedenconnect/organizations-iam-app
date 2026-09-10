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
# set-iam-admin-resource-server.sh
#
# Convenience wrapper for the local Docker Compose environment. It calls
# keycloak/scripts/set-iam-admin-resource-server.sh, the single implementation of this script, with the
# compose Keycloak URL and its CA certificate already supplied. Every other argument is
# passed through unchanged.
#
# Requires curl and python3 on the host, since the script runs here rather than inside a
# container.
#
# Against any other Keycloak, call keycloak/scripts/set-iam-admin-resource-server.sh directly.

set -euo pipefail

# The URL the compose Keycloak is published on, and the certificate it serves.
KC_URL="https://local.dev.swedenconnect.se:17000"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

TARGET="${REPO_ROOT}/keycloak/scripts/set-iam-admin-resource-server.sh"
CACERT="${REPO_ROOT}/compose/config/common/tls.crt"

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  cat <<EOF
Mark a Keycloak client as a resource server administered by the IAM admin application, by
setting the iam_admin_resource_server=true client attribute. Nothing else on the client is
changed, and iam_admin_managed is left as it is.

Runs against the compose Keycloak at ${KC_URL}.

Usage: $0 --realm <realm> --client-id <clientId> --username <username> --password <password>

Options:
  --realm <realm>         Keycloak realm name
  --client-id <clientId>  Client ID of the target client
  --username <username>   Admin username for the Keycloak master realm
  --password <password>   Admin password for the Keycloak master realm
  --help, -h              Show this help message
EOF
  exit 0
fi

[ -x "${TARGET}" ] || { echo "ERROR: ${TARGET} not found or not executable." >&2; exit 1; }
[ -r "${CACERT}" ] || { echo "ERROR: CA certificate ${CACERT} not readable." >&2; exit 1; }

exec "${TARGET}" --url "${KC_URL}" --cacert "${CACERT}" "$@"
