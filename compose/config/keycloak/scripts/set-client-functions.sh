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

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

REALM=""
USERNAME=""
PASSWORD=""
CLIENT_ID=""
FUNCTIONS=""

KCADM="/opt/keycloak/bin/kcadm.sh"
SERVER_URL="http://keycloak:8080"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Set the client_functions attribute on an existing Keycloak client.

Usage: $0 [OPTIONS]

Options:
  --realm <realm>          Keycloak realm name
  --username <username>    Admin username
  --password <password>    Admin password
  --client-id <id>         Client ID (e.g. https://api.example.com)
  --functions <functions>  Comma-separated list of functions
                           (e.g. demo,walletreg)
  --help, -h               Show this help message

All parameters are optional on the command line; missing required values
will be prompted for interactively.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)    usage; exit 0 ;;
    --realm)      REALM="$2";      shift 2 ;;
    --username)   USERNAME="$2";   shift 2 ;;
    --password)   PASSWORD="$2";   shift 2 ;;
    --client-id)  CLIENT_ID="$2";  shift 2 ;;
    --functions)  FUNCTIONS="$2";  shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Interactive prompts for missing required values
# ---------------------------------------------------------------------------

if [ -z "${REALM}" ]; then
  read -r -p "Realm: " REALM
fi
if [ -z "${USERNAME}" ]; then
  read -r -p "Admin username: " USERNAME
fi
if [ -z "${PASSWORD}" ]; then
  read -r -s -p "Admin password: " PASSWORD
  echo ""
fi
if [ -z "${CLIENT_ID}" ]; then
  read -r -p "Client ID (e.g. https://api.example.com): " CLIENT_ID
fi
if [ -z "${FUNCTIONS}" ]; then
  read -r -p "Functions (comma-separated, e.g. demo,walletreg): " FUNCTIONS
fi

# ---------------------------------------------------------------------------
# Authenticate
# ---------------------------------------------------------------------------

echo "==> Authenticating as '${USERNAME}'..."
"${KCADM}" config credentials \
  --server "${SERVER_URL}" \
  --realm master \
  --user "${USERNAME}" \
  --password "${PASSWORD}"

# ---------------------------------------------------------------------------
# Resolve client UUID
# ---------------------------------------------------------------------------

echo "==> Looking up client '${CLIENT_ID}'..."
CLIENTS_TMP=$(mktemp)
( "${KCADM}" get clients -r "${REALM}" --fields id,clientId ) > "${CLIENTS_TMP}" 2>&1 || true
CLIENT_UUID=$(grep -B1 "\"clientId\" : \"${CLIENT_ID}\"" "${CLIENTS_TMP}" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/' || true)
rm -f "${CLIENTS_TMP}"

if [ -z "${CLIENT_UUID}" ]; then
  echo "ERROR: Client '${CLIENT_ID}' not found in realm '${REALM}'." >&2
  exit 1
fi

echo "    Found (UUID: ${CLIENT_UUID})."

# ---------------------------------------------------------------------------
# Set client_functions attribute
# ---------------------------------------------------------------------------

echo "==> Setting client_functions='${FUNCTIONS}' on '${CLIENT_ID}'..."
"${KCADM}" update "clients/${CLIENT_UUID}" -r "${REALM}" \
  -s "attributes.client_functions=${FUNCTIONS}"
echo "    Done."

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> client_functions set on '${CLIENT_ID}'."
echo ""
echo "    Functions: ${FUNCTIONS}"
echo ""
echo "    The resource-aud plugin will validate that the requested function"
echo "    matches this attribute at token issuance time."
