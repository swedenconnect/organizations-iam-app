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
# add-resource-server.sh
#
# Register a resource server (audience-only client) in Keycloak.
# Resource servers are public clients with all grant flows disabled — they
# exist only to represent an OAuth2 audience and carry a client_functions
# attribute that the resource-aud plugin validates at token issuance time.
#
# Uses the Keycloak Admin REST API (curl + python3)
# Safe to re-run: idempotent for existing clients.
#
# Usage: see --help

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

KC_URL=""
KC_BASE_PATH=""
KC_USER="admin"
KC_PASS=""
REALM=""
CLIENT_ID=""
NAME=""
FUNCTIONS=""
CACERT=""
INSECURE="false"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Register a resource server (audience-only client) in Keycloak.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Options:
  --url <url>              Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>       URL path prefix (e.g. /auth). Default: empty
  --realm <realm>          Keycloak realm name
  --username <user>        Admin username (default: admin)
  --password <pass>        Admin password
  --client-id <id>         Client ID (the resource server's base URL,
                           e.g. https://api.example.com)
  --name <name>            Display name shown in the Keycloak admin UI
  --functions <functions>  Comma-separated list of functions to set as
                           the client_functions attribute (e.g. demo,walletreg)
  --cacert <file>          CA certificate file for TLS verification
  --insecure               Skip TLS certificate verification (dev only)
  --help, -h               Show this help message

All parameters are optional; missing required values will be prompted for.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)       usage; exit 0 ;;
    --url)           KC_URL="$2";       shift 2 ;;
    --base-path)     KC_BASE_PATH="$2"; shift 2 ;;
    --realm)         REALM="$2";        shift 2 ;;
    --username)      KC_USER="$2";      shift 2 ;;
    --password)      KC_PASS="$2";      shift 2 ;;
    --client-id)     CLIENT_ID="$2";    shift 2 ;;
    --name)          NAME="$2";         shift 2 ;;
    --functions)     FUNCTIONS="$2";    shift 2 ;;
    --cacert)        CACERT="$2";       shift 2 ;;
    --insecure)      INSECURE="true";   shift ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

[ -z "${KC_URL}"    ] && { read -r -p "Keycloak URL: " KC_URL; }
[ -z "${REALM}"     ] && { read -r -p "Realm: " REALM; }
[ -z "${KC_USER}"   ] && { read -r -p "Admin username: " KC_USER; }
[ -z "${KC_PASS}"   ] && { read -r -s -p "Admin password: " KC_PASS; echo ""; }
[ -z "${CLIENT_ID}" ] && { read -r -p "Client ID (e.g. https://api.example.com): " CLIENT_ID; }

# ---------------------------------------------------------------------------
# curl setup
# ---------------------------------------------------------------------------

CURL_OPTS=(-s)
[ -n "${CACERT}"          ] && CURL_OPTS+=(--cacert "${CACERT}")
[ "${INSECURE}" = "true"  ] && CURL_OPTS+=(-k)

ADMIN_BASE="${KC_URL}${KC_BASE_PATH}/admin/realms"
TOKEN_URL="${KC_URL}${KC_BASE_PATH}/realms/master/protocol/openid-connect/token"

get_token() {
  curl "${CURL_OPTS[@]}" -X POST "${TOKEN_URL}" \
    -d "grant_type=password&client_id=admin-cli&username=${KC_USER}&password=${KC_PASS}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])" 2>/dev/null
}

api_get() {
  curl "${CURL_OPTS[@]}" -H "Authorization: Bearer ${TOKEN}" "${ADMIN_BASE}${1}"
}

api_create() {
  local path="$1" body="$2"
  curl "${CURL_OPTS[@]}" -i -X POST \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}" \
  | grep -i '^location:' | sed 's|.*/||' | tr -d '\r\n'
}

api_put() {
  local path="$1" body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}"
}

urlencode() {
  python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$1"
}

# ---------------------------------------------------------------------------
# Authenticate
# ---------------------------------------------------------------------------

echo ""
echo "==> Authenticating as '${KC_USER}'..."
TOKEN=$(get_token)
[ -z "${TOKEN}" ] && { echo "ERROR: Failed to obtain admin token." >&2; exit 1; }
echo "    Token obtained."

# ---------------------------------------------------------------------------
# Step 1 — Resolve or create client
# ---------------------------------------------------------------------------

echo "==> Resolving client '${CLIENT_ID}'..."
CLIENT_ID_ENC=$(urlencode "${CLIENT_ID}")
CLIENT_UUID=$(api_get "/${REALM}/clients?clientId=${CLIENT_ID_ENC}&max=1" | python3 -c "
import sys, json
clients = json.load(sys.stdin)
print(clients[0]['id'] if clients else '')
" 2>/dev/null || echo "")

if [ -n "${CLIENT_UUID}" ]; then
  echo "    Client already exists (UUID: ${CLIENT_UUID})."
else
  echo "    Creating resource server client..."
  CREATE_BODY=$(python3 -c '
import json, sys
body = {
  "clientId": sys.argv[1],
  "protocol": "openid-connect",
  "enabled": True,
  "publicClient": True,
  "standardFlowEnabled": False,
  "implicitFlowEnabled": False,
  "directAccessGrantsEnabled": False,
  "serviceAccountsEnabled": False,
  "authorizationServicesEnabled": False,
}
if sys.argv[2]:
    body["name"] = sys.argv[2]
print(json.dumps(body))
' "${CLIENT_ID}" "${NAME}")

  CLIENT_UUID=$(api_create "/${REALM}/clients" "${CREATE_BODY}")
  if [ -z "${CLIENT_UUID}" ]; then
    CLIENT_UUID=$(api_get "/${REALM}/clients?clientId=${CLIENT_ID_ENC}&max=1" | python3 -c "
import sys, json
clients = json.load(sys.stdin)
print(clients[0]['id'] if clients else '')
" 2>/dev/null || echo "")
  fi

  [ -z "${CLIENT_UUID}" ] && { echo "ERROR: Failed to create client '${CLIENT_ID}'." >&2; exit 1; }
  echo "    Client created (UUID: ${CLIENT_UUID})."
fi

# ---------------------------------------------------------------------------
# Step 2 — Sync settings (always runs — read-merge-write)
# ---------------------------------------------------------------------------

echo "==> Syncing client settings..."
CURRENT=$(api_get "/${REALM}/clients/${CLIENT_UUID}")
UPDATED=$(CURRENT_JSON="${CURRENT}" _NAME="${NAME}" _FUNCTIONS="${FUNCTIONS}" python3 -c "
import os, json
client = json.loads(os.environ['CURRENT_JSON'])
name = os.environ['_NAME']
functions = os.environ['_FUNCTIONS']

client['publicClient'] = True
client['standardFlowEnabled'] = False
client['implicitFlowEnabled'] = False
client['directAccessGrantsEnabled'] = False
client['serviceAccountsEnabled'] = False
client['authorizationServicesEnabled'] = False

if name:
    client['name'] = name

if functions:
    if not client.get('attributes'):
        client['attributes'] = {}
    client['attributes']['client_functions'] = functions

print(json.dumps(client))
")
STATUS=$(api_put "/${REALM}/clients/${CLIENT_UUID}" "${UPDATED}")
[ "${STATUS}" = "204" ] && echo "    Done." || { echo "ERROR: Unexpected HTTP status: ${STATUS}" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> Resource server '${CLIENT_ID}' registered successfully."
[ -n "${NAME}"      ] && echo "    Display name: ${NAME}"
[ -n "${FUNCTIONS}" ] && echo "    client_functions: ${FUNCTIONS}"
echo ""
echo "    The server can now be referenced via the OAuth2 resource parameter,"
echo "    causing Keycloak to set the aud claim in access tokens accordingly."
if [ -n "${FUNCTIONS}" ]; then
  echo ""
  echo "    The resource-aud plugin will validate that the requested function"
  echo "    matches the client_functions attribute at token issuance time."
fi
echo ""
echo "    The resource server validates incoming Bearer tokens by verifying"
echo "    the signature against Keycloak's JWKS endpoint, checking the aud"
echo "    claim, and inspecting the scope and organization_identifier claims"
echo "    itself. See docs/keycloak-setup.md section 3.3 for details."
