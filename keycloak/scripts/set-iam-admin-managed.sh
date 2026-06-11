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
# set-iam-admin-managed.sh
#
# Mark a Keycloak client as managed by the IAM admin application by setting
# the iam_admin_managed=true client attribute.
#
# Uses the Keycloak Admin REST API (curl + python3)
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
CACERT=""
INSECURE="false"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Mark a Keycloak client as managed by the IAM admin application by setting
the iam_admin_managed=true client attribute.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Options:
  --url <url>          Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>   URL path prefix (e.g. /auth). Default: empty
  --realm <realm>      Keycloak realm name
  --username <user>    Admin username (default: admin)
  --password <pass>    Admin password
  --client-id <id>     Client ID of the target client
  --cacert <file>      CA certificate file for TLS verification
  --insecure           Skip TLS certificate verification (dev only)
  --help, -h           Show this help message
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)       usage; exit 0 ;;
    --url)           KC_URL="$2";     shift 2 ;;
    --base-path)     KC_BASE_PATH="$2"; shift 2 ;;
    --realm)         REALM="$2";      shift 2 ;;
    --username)      KC_USER="$2";    shift 2 ;;
    --password)      KC_PASS="$2";    shift 2 ;;
    --client-id)     CLIENT_ID="$2";  shift 2 ;;
    --cacert)        CACERT="$2";     shift 2 ;;
    --insecure)      INSECURE="true"; shift ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

[ -z "${KC_URL}"    ] && { read -r -p "Keycloak URL: " KC_URL; }
[ -z "${REALM}"     ] && { read -r -p "Realm: " REALM; }
[ -z "${KC_USER}"   ] && { read -r -p "Admin username: " KC_USER; }
[ -z "${KC_PASS}"   ] && { read -r -s -p "Admin password: " KC_PASS; echo ""; }
[ -z "${CLIENT_ID}" ] && { read -r -p "Client ID: " CLIENT_ID; }

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

api_put() {
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "$2" \
    "${ADMIN_BASE}${1}"
}

urlencode() {
  python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$1"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

echo ""
echo "==> Authenticating as '${KC_USER}'..."
TOKEN=$(get_token)
[ -z "${TOKEN}" ] && { echo "ERROR: Failed to obtain admin token." >&2; exit 1; }
echo "    Token obtained."

echo "==> Looking up client '${CLIENT_ID}'..."
CLIENT_ID_ENC=$(urlencode "${CLIENT_ID}")
CLIENT_UUID=$(api_get "/${REALM}/clients?clientId=${CLIENT_ID_ENC}&max=1" | python3 -c "
import sys, json
clients = json.load(sys.stdin)
print(clients[0]['id'] if clients else '')
" 2>/dev/null || echo "")

[ -z "${CLIENT_UUID}" ] && { echo "ERROR: Client '${CLIENT_ID}' not found in realm '${REALM}'." >&2; exit 1; }
echo "    Found (UUID: ${CLIENT_UUID})."

echo "==> Setting iam_admin_managed=true..."
CURRENT=$(api_get "/${REALM}/clients/${CLIENT_UUID}")
UPDATED=$(CURRENT_JSON="${CURRENT}" python3 -c "
import os, json
client = json.loads(os.environ['CURRENT_JSON'])
if not client.get('attributes'):
    client['attributes'] = {}
client['attributes']['iam_admin_managed'] = 'true'
print(json.dumps(client))
")
STATUS=$(api_put "/${REALM}/clients/${CLIENT_UUID}" "${UPDATED}")
[ "${STATUS}" = "204" ] && echo "    Done." || { echo "ERROR: Unexpected HTTP status: ${STATUS}" >&2; exit 1; }

echo ""
echo "==> Client '${CLIENT_ID}' is now marked as iam_admin_managed."
