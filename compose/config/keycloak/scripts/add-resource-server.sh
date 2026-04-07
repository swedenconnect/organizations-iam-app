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
NAME=""
FUNCTIONS=""

KCADM="/opt/keycloak/bin/kcadm.sh"
SERVER_URL="http://keycloak:8080"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Register a resource server (audience-only client) in Keycloak.

Usage: $0 [OPTIONS]

Options:
  --realm <realm>          Keycloak realm name
  --username <username>    Admin username
  --password <password>    Admin password
  --client-id <id>         Client ID (the resource server's base URL,
                           e.g. https://api.example.com)
  --name <name>            Display name shown in the Keycloak admin UI
  --functions <functions>  Comma-separated list of functions to set as
                           the client_functions attribute
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
    --realm)      REALM="$2";     shift 2 ;;
    --username)   USERNAME="$2";  shift 2 ;;
    --password)   PASSWORD="$2";  shift 2 ;;
    --client-id)  CLIENT_ID="$2"; shift 2 ;;
    --name)       NAME="$2";      shift 2 ;;
    --functions)  FUNCTIONS="$2"; shift 2 ;;
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
  read -r -p "Client ID (the resource server's base URL, e.g. https://api.example.com): " CLIENT_ID
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
# Create the client
# ---------------------------------------------------------------------------

echo "==> Registering resource server '${CLIENT_ID}'..."
CLIENTS_TMP=$(mktemp)
( "${KCADM}" get clients -r "${REALM}" --fields id,clientId ) > "${CLIENTS_TMP}" 2>&1 || true
EXISTING_UUID=$(grep -B1 "\"clientId\" : \"${CLIENT_ID}\"" "${CLIENTS_TMP}" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/' || true)
rm -f "${CLIENTS_TMP}"

if [ -n "${EXISTING_UUID}" ]; then
  echo "    Client '${CLIENT_ID}' already exists (UUID: ${EXISTING_UUID}) — skipping."
  if [ -n "${NAME}" ]; then
    echo "    Updating display name to '${NAME}'..."
    "${KCADM}" update "clients/${EXISTING_UUID}" -r "${REALM}" \
      -s "name=${NAME}"
  fi
else
  CREATE_ARGS=(
    -s "clientId=${CLIENT_ID}"
    -s "protocol=openid-connect"
    -s "enabled=true"
    -s "publicClient=true"
    -s "standardFlowEnabled=false"
    -s "implicitFlowEnabled=false"
    -s "directAccessGrantsEnabled=false"
    -s "serviceAccountsEnabled=false"
    -s "authorizationServicesEnabled=false"
  )
  if [ -n "${NAME}" ]; then
    CREATE_ARGS+=(-s "name=${NAME}")
  fi

  CREATE_OUTPUT=$("${KCADM}" create clients -r "${REALM}" \
    "${CREATE_ARGS[@]}" 2>&1 || true)
  echo "${CREATE_OUTPUT}"
  EXISTING_UUID=$(echo "${CREATE_OUTPUT}" | sed "s/.*id '\(.*\)'.*/\1/" || true)
  echo "    Resource server registered (UUID: ${EXISTING_UUID})."
fi

# ---------------------------------------------------------------------------
# Set client_functions attribute (if --functions was provided)
# ---------------------------------------------------------------------------

if [ -n "${FUNCTIONS}" ]; then
  echo "==> Setting client_functions='${FUNCTIONS}' on '${CLIENT_ID}'..."
  "${KCADM}" update "clients/${EXISTING_UUID}" -r "${REALM}" \
    -s "attributes.client_functions=${FUNCTIONS}"
  echo "    client_functions set."
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> Resource server '${CLIENT_ID}' registered successfully."
if [ -n "${NAME}" ]; then
  echo "    Display name: ${NAME}"
fi
echo ""
echo "    The server can now be referenced via the OAuth2 resource parameter,"
echo "    causing Keycloak to set the aud claim in access tokens accordingly."
if [ -n "${FUNCTIONS}" ]; then
  echo ""
  echo "    client_functions: ${FUNCTIONS}"
  echo "    The resource-aud plugin will validate that the requested function"
  echo "    matches the client_functions attribute at token issuance time."
fi
echo ""
echo "    The resource server validates incoming Bearer tokens by verifying"
echo "    the signature against Keycloak's JWKS endpoint, checking the aud"
echo "    claim, and inspecting the scope and organization_identifier claims"
echo "    itself. See docs/keycloak-setup.md section 3.3 for details."
