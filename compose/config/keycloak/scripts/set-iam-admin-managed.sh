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

set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<EOF
Mark a Keycloak client as managed by the IAM admin application by setting
the iam_admin_managed=true client attribute.

Usage: $0 <realm> <clientId> <username> <password>

Positional arguments:
  realm      Keycloak realm name
  clientId   Client ID of the target client
  username   Admin username for Keycloak master realm
  password   Admin password for Keycloak master realm

Note: This is the inner script invoked by the outer wrapper. Use the
wrapper script in compose/keycloak-scripts/ which accepts named arguments.
EOF
  exit 0
fi

if [ $# -ne 4 ]; then
  echo "Usage: $0 <realm> <clientId> <username> <password>" >&2
  exit 1
fi

REALM="$1"
CLIENT_ID="$2"
USERNAME="$3"
PASSWORD="$4"

KCADM="/opt/keycloak/bin/kcadm.sh"
SERVER_URL="http://keycloak:8080"

echo "Authenticating as '${USERNAME}' against Keycloak at ${SERVER_URL}..."
"${KCADM}" config credentials \
  --server "${SERVER_URL}" \
  --realm master \
  --user "${USERNAME}" \
  --password "${PASSWORD}"

echo "Looking up client UUID for clientId '${CLIENT_ID}' in realm '${REALM}'..."
CLIENTS_TMP=$(mktemp)
( "${KCADM}" get clients --fields id,clientId -r "${REALM}" ) > "${CLIENTS_TMP}" 2>&1 || true
CLIENT_UUID=$(grep -B1 "\"clientId\" : \"${CLIENT_ID}\"" "${CLIENTS_TMP}" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/' || true)
rm -f "${CLIENTS_TMP}"

if [ -z "${CLIENT_UUID}" ]; then
  echo "Error: client '${CLIENT_ID}' not found in realm '${REALM}'." >&2
  exit 1
fi

echo "Found client UUID: ${CLIENT_UUID}"
echo "Setting attribute iam_admin_managed=true..."

"${KCADM}" update "clients/${CLIENT_UUID}" \
  -r "${REALM}" \
  -s 'attributes.iam_admin_managed=true'

echo "Done. Client '${CLIENT_ID}' is now marked as iam_admin_managed."
