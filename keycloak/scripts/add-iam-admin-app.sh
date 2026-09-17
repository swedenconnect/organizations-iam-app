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
# add-iam-admin-app.sh
#
# Register the IAM Admin application in Keycloak.
#
# The IAM Admin application is not an ordinary OIDC client. It plays three roles
# at once, and add-oidc-client.sh alone gives it only the first:
#
#   1. OIDC client:      it logs administrators in and obtains org-scoped
#                        tokens (iam_admin_managed=true)
#   2. Resource server:  it exposes /iam-api and other clients name it in the
#                        OAuth2 resource parameter (iam_admin_resource_server=true)
#   3. All functions:    its API serves every function, including the ones not
#                        created yet (iam_admin_all_functions=true)
#
# The third role is the one that needs care. resource-aud-plugin validates the
# resource parameter against the raw client_functions attribute inside Keycloak
# and knows nothing of the marker, so the attribute is seeded here with every
# function that exists. The IAM Admin application adds each new function to it
# as the function is created.
#
# Uses the Keycloak Admin REST API (curl + python3), delegating the OIDC client
# registration to add-oidc-client.sh in the same directory.
# Safe to re-run: also the repair path for an application registered before the
# markers existed.
#
# Usage: see --help

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

KC_URL=""
KC_BASE_PATH=""
KC_USER="admin"
KC_PASS=""
REALM=""
CLIENT_ID=""
NAME="IAM Admin"
REDIRECT_URIS=()
ROOT_URL=""
ROOT_URL_SET="false"
JWKS_URL=""
CACERT=""
INSECURE="false"

# The IAM Admin application's own callback path. Overridable, but it is the same
# in every deployment because the application owns it.
DEFAULT_REDIRECT_URI="/login/oauth2/code/*"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Register the IAM Admin application in Keycloak as an OIDC client, a resource
server, and a client handling all functions.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Options:
  --url <url>              Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>       URL path prefix (e.g. /auth). Default: empty
  --realm <realm>          Keycloak realm name
  --username <user>        Admin username (default: admin)
  --password <pass>        Admin password
  --client-id <id>         Client ID, the application's base URL
                           (e.g. https://iam-admin.example.com)
  --name <name>            Display name shown in the Keycloak admin UI
                           (default: IAM Admin)
  --redirect-uri <pattern> Redirect URI pattern (repeatable)
                           (default: ${DEFAULT_REDIRECT_URI})
  --root-url <url>         Client root URL (default: the client ID)
  --jwks-url <url>         JWKS endpoint URL (default: <client-id>/jwks)
  --cacert <file>          CA certificate file for TLS verification
  --insecure               Skip TLS certificate verification (dev only)
  --help, -h               Show this help message

All parameters are optional; missing required values will be prompted for.

The application always keeps a service account with realm-management roles: it
administers the realm through the Keycloak Admin API, and cannot work without
one. There is no flag to turn that off.

Run this after bootstrap-realm.sh. Re-run it at any time; it is idempotent, and
it is also how an application registered with add-oidc-client.sh alone is
brought up to the full three-role shape.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)       usage; exit 0 ;;
    --url)           KC_URL="$2";           shift 2 ;;
    --base-path)     KC_BASE_PATH="$2";     shift 2 ;;
    --realm)         REALM="$2";            shift 2 ;;
    --username)      KC_USER="$2";          shift 2 ;;
    --password)      KC_PASS="$2";          shift 2 ;;
    --client-id)     CLIENT_ID="$2";        shift 2 ;;
    --name)          NAME="$2";             shift 2 ;;
    --redirect-uri)  REDIRECT_URIS+=("$2"); shift 2 ;;
    --root-url)      ROOT_URL="$2"; ROOT_URL_SET="true"; shift 2 ;;
    --jwks-url)      JWKS_URL="$2";         shift 2 ;;
    --cacert)        CACERT="$2";           shift 2 ;;
    --insecure)      INSECURE="true";       shift ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

[ -z "${KC_URL}"    ] && { read -r -p "Keycloak URL: " KC_URL; }
[ -z "${REALM}"     ] && { read -r -p "Realm: " REALM; }
[ -z "${KC_USER}"   ] && { read -r -p "Admin username: " KC_USER; }
[ -z "${KC_PASS}"   ] && { read -r -s -p "Admin password: " KC_PASS; echo ""; }
[ -z "${CLIENT_ID}" ] && {
  read -r -p "Client ID (the IAM Admin base URL, e.g. https://iam-admin.example.com): " CLIENT_ID
}

[ ${#REDIRECT_URIS[@]} -eq 0 ] && REDIRECT_URIS=("${DEFAULT_REDIRECT_URI}")
# The redirect URI defaults to a path, which Keycloak resolves against the root
# URL. Supplying the root URL here keeps add-oidc-client.sh from prompting.
[ "${ROOT_URL_SET}" = "false" ] && ROOT_URL="${CLIENT_ID}"

# ---------------------------------------------------------------------------
# Step 1: Register the OIDC client
# ---------------------------------------------------------------------------

DELEGATE=("${SCRIPT_DIR}/add-oidc-client.sh"
  --url "${KC_URL}"
  --realm "${REALM}"
  --username "${KC_USER}"
  --password "${KC_PASS}"
  --client-id "${CLIENT_ID}"
  --name "${NAME}"
  --root-url "${ROOT_URL}"
  --service-account)

[ -n "${KC_BASE_PATH}" ] && DELEGATE+=(--base-path "${KC_BASE_PATH}")
[ -n "${JWKS_URL}"     ] && DELEGATE+=(--jwks-url "${JWKS_URL}")
[ -n "${CACERT}"       ] && DELEGATE+=(--cacert "${CACERT}")
[ "${INSECURE}" = "true" ] && DELEGATE+=(--insecure)

for URI in "${REDIRECT_URIS[@]}"; do
  DELEGATE+=(--redirect-uri "${URI}")
done

echo ""
echo "==> Registering the OIDC client via add-oidc-client.sh..."
"${DELEGATE[@]}"

# ---------------------------------------------------------------------------
# curl setup for the remaining steps
# ---------------------------------------------------------------------------

CURL_OPTS=(-s)
[ -n "${CACERT}"         ] && CURL_OPTS+=(--cacert "${CACERT}")
[ "${INSECURE}" = "true" ] && CURL_OPTS+=(-k)

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

[ -z "${CLIENT_UUID}" ] && { echo "ERROR: Client '${CLIENT_ID}' not found after registration." >&2; exit 1; }
echo "    Found (UUID: ${CLIENT_UUID})."

# ---------------------------------------------------------------------------
# Step 2: Collect every function that exists
# ---------------------------------------------------------------------------

echo "==> Collecting the functions defined in realm '${REALM}'..."
FUNCTIONS_GROUP_ID=$(api_get "/${REALM}/groups?search=functions&max=100" | python3 -c "
import sys, json
for g in json.load(sys.stdin):
    if g.get('name') == 'functions':
        print(g['id'])
        break
" 2>/dev/null || echo "")

if [ -z "${FUNCTIONS_GROUP_ID}" ]; then
  echo "ERROR: The '/functions' group was not found in realm '${REALM}'." >&2
  echo "       Run bootstrap-realm.sh first." >&2
  exit 1
fi

EXISTING_FUNCTIONS=$(api_get "/${REALM}/groups/${FUNCTIONS_GROUP_ID}/children?max=1000" | python3 -c "
import sys, json
print(','.join(g.get('name','') for g in json.load(sys.stdin) if g.get('name')))
" 2>/dev/null || echo "")

if [ -n "${EXISTING_FUNCTIONS}" ]; then
  echo "    Found: ${EXISTING_FUNCTIONS}"
else
  echo "    No functions defined yet. The attribute starts empty and the application"
  echo "    fills it in as functions are created."
fi

# ---------------------------------------------------------------------------
# Step 3: Set the resource server and all-functions markers
# ---------------------------------------------------------------------------

echo "==> Setting the resource server and all-functions markers..."
CURRENT=$(api_get "/${REALM}/clients/${CLIENT_UUID}")
UPDATED=$(CURRENT_JSON="${CURRENT}" _FUNCTIONS="${EXISTING_FUNCTIONS}" python3 -c "
import os, json
client = json.loads(os.environ['CURRENT_JSON'])
attributes = client.get('attributes') or {}

attributes['iam_admin_resource_server'] = 'true'
attributes['iam_admin_all_functions'] = 'true'

# Union rather than replace: a re-run must not drop a function the application
# added since the last one.
current = [f.strip() for f in attributes.get('client_functions', '').split(',') if f.strip()]
for function in (f.strip() for f in os.environ['_FUNCTIONS'].split(',')):
    if function and function not in current:
        current.append(function)
attributes['client_functions'] = ','.join(current)

client['attributes'] = attributes
print(json.dumps(client))
")

STATUS=$(api_put "/${REALM}/clients/${CLIENT_UUID}" "${UPDATED}")
[ "${STATUS}" = "204" ] || { echo "ERROR: Unexpected HTTP status: ${STATUS}" >&2; exit 1; }
echo "    Done."

RESULT=$(echo "${UPDATED}" | python3 -c "
import sys, json
print(json.load(sys.stdin).get('attributes', {}).get('client_functions', ''))
")

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "==> IAM Admin application registered."
echo ""
echo "    Client ID:                 ${CLIENT_ID}"
echo "    Roles:                     OIDC client, resource server, all functions"
echo "    iam_admin_managed:         true"
echo "    iam_admin_oidc_client:     true"
echo "    iam_admin_resource_server: true"
echo "    iam_admin_all_functions:   true"
echo "    client_functions:          ${RESULT:-<empty>}"
echo ""
echo "    The application handles every function, including the ones not created"
echo "    yet. It writes each new function into client_functions as the function"
echo "    is created, so that resource-aud-plugin accepts a token request naming"
echo "    this application in the OAuth2 resource parameter."
echo ""
echo "    ACTION REQUIRED: run a reconciliation."
echo ""
echo "    The markers above decide which artifacts the client should hold; they do"
echo "    not create them. Until a reconciliation runs, this client has no scopes,"
echo "    policies, permissions or optional client scope bindings for any"
echo "    organization, and no user can obtain an org-scoped token from it."
echo ""
echo "    Log in to the IAM Admin application as a superuser and press"
echo "    'Reconcile All' on the Services tab, or:"
echo ""
echo "      curl -X POST <iam-admin-app>/api/clients/reconcile"
echo ""
echo "    (that endpoint uses the caller's admin-application session, so it cannot"
echo "    be driven from this script). The Services tab flags a client whose"
echo "    artifacts are missing, so it also shows when this step is still pending."
echo ""
echo "    Next: create the initial superuser with create-admin-user.sh, if that"
echo "    has not been done already."
