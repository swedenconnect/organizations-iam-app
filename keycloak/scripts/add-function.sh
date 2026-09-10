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
# Add one or more functions to the client_functions attribute of an existing
# Keycloak client, keeping the functions it already declares.
#
# Where set-client-functions.sh replaces the whole list, this script only adds
# to it, which is what a service that gains a function after registration needs.
#
# Uses the Keycloak Admin REST API (curl + python3)
# Safe to re-run: a function the client already declares is left alone.
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
FUNCTIONS=()
CACERT=""
INSECURE="false"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Add one or more functions to the client_functions attribute of an existing
Keycloak client, keeping the functions it already declares.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Options:
  --url <url>              Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>       URL path prefix (e.g. /auth). Default: empty
  --realm <realm>          Keycloak realm name
  --username <user>        Admin username (default: admin)
  --password <pass>        Admin password
  --client-id <id>         Client ID (e.g. https://api.example.com)
  --function <function>    Function to add (repeatable)
  --functions <functions>  Comma-separated list of functions to add
  --cacert <file>          CA certificate file for TLS verification
  --insecure               Skip TLS certificate verification (dev only)
  --help, -h               Show this help message

All parameters are optional; missing required values will be prompted for.
--function and --functions may be combined; the two lists are merged.

The function must already exist as a group under /functions in the realm.
Create it in the IAM Admin application first.

A client marked iam_admin_all_functions=true already handles every function.
This script reports that and makes no change; the IAM Admin application adds
new functions to such a client by itself as they are created.
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
    --function)      FUNCTIONS+=("$2"); shift 2 ;;
    --functions)     IFS=',' read -r -a _SPLIT <<< "$2"
                     # Guarded: bash 3.2 (the macOS default) treats the expansion
                     # of an empty array as an unbound variable under 'set -u'.
                     [ ${#_SPLIT[@]} -gt 0 ] && FUNCTIONS+=("${_SPLIT[@]}")
                     shift 2 ;;
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

if [ ${#FUNCTIONS[@]} -eq 0 ]; then
  read -r -p "Functions to add (comma-separated, e.g. demo,walletreg): " _INPUT
  IFS=',' read -r -a FUNCTIONS <<< "${_INPUT}"
fi
[ ${#FUNCTIONS[@]} -eq 0 ] && { echo "ERROR: No functions given." >&2; exit 1; }

ADD_LIST=$(IFS=','; echo "${FUNCTIONS[*]}")

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
# Step 1 — Authenticate
# ---------------------------------------------------------------------------

echo ""
echo "==> Authenticating as '${KC_USER}'..."
TOKEN=$(get_token)
[ -z "${TOKEN}" ] && { echo "ERROR: Failed to obtain admin token." >&2; exit 1; }
echo "    Token obtained."

# ---------------------------------------------------------------------------
# Step 2 — Resolve the client
# ---------------------------------------------------------------------------

echo "==> Looking up client '${CLIENT_ID}'..."
CLIENT_ID_ENC=$(urlencode "${CLIENT_ID}")
CLIENT_UUID=$(api_get "/${REALM}/clients?clientId=${CLIENT_ID_ENC}&max=1" | python3 -c "
import sys, json
clients = json.load(sys.stdin)
print(clients[0]['id'] if clients else '')
" 2>/dev/null || echo "")

[ -z "${CLIENT_UUID}" ] && { echo "ERROR: Client '${CLIENT_ID}' not found in realm '${REALM}'." >&2; exit 1; }
echo "    Found (UUID: ${CLIENT_UUID})."

# ---------------------------------------------------------------------------
# Step 3 — Check that every function exists
# ---------------------------------------------------------------------------
#
# A function that does not exist would be written into client_functions and then
# never match anything, leaving a client that silently fails at token issuance.

echo "==> Checking that the functions exist..."
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
print(','.join(g.get('name','') for g in json.load(sys.stdin)))
" 2>/dev/null || echo "")

MISSING=$(_EXISTING="${EXISTING_FUNCTIONS}" _WANTED="${ADD_LIST}" python3 -c "
import os
existing = {f for f in os.environ['_EXISTING'].split(',') if f}
wanted = [f.strip() for f in os.environ['_WANTED'].split(',') if f.strip()]
print(','.join(f for f in wanted if f not in existing))
")

if [ -n "${MISSING}" ]; then
  echo "ERROR: These functions do not exist in realm '${REALM}': ${MISSING}" >&2
  echo "       Create them in the IAM Admin application first." >&2
  exit 1
fi
echo "    All functions exist."

# ---------------------------------------------------------------------------
# Step 4 — Merge into client_functions
# ---------------------------------------------------------------------------

CURRENT=$(api_get "/${REALM}/clients/${CLIENT_UUID}")

ALL_FUNCTIONS=$(CURRENT_JSON="${CURRENT}" python3 -c "
import os, json
client = json.loads(os.environ['CURRENT_JSON'])
print(client.get('attributes', {}).get('iam_admin_all_functions', ''))
")

if [ "${ALL_FUNCTIONS}" = "true" ]; then
  echo ""
  echo "==> Client '${CLIENT_ID}' is marked iam_admin_all_functions=true."
  echo ""
  echo "    It already handles every function, including the ones not created yet."
  echo "    Its client_functions attribute is maintained by the IAM Admin application"
  echo "    as functions are created. Nothing to do."
  exit 0
fi

echo "==> Adding functions to client_functions..."
UPDATED=$(CURRENT_JSON="${CURRENT}" _ADD="${ADD_LIST}" python3 -c "
import os, json
client = json.loads(os.environ['CURRENT_JSON'])
attributes = client.get('attributes') or {}

current = [f.strip() for f in attributes.get('client_functions', '').split(',') if f.strip()]
added = []
for function in (f.strip() for f in os.environ['_ADD'].split(',')):
    if function and function not in current:
        current.append(function)
        added.append(function)

attributes['client_functions'] = ','.join(current)
client['attributes'] = attributes
print(json.dumps({'client': client, 'added': added, 'result': current}))
")

ADDED=$(echo "${UPDATED}" | python3 -c "import sys,json; print(','.join(json.load(sys.stdin)['added']))")
RESULT=$(echo "${UPDATED}" | python3 -c "import sys,json; print(','.join(json.load(sys.stdin)['result']))")
BODY=$(echo "${UPDATED}" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['client']))")

if [ -z "${ADDED}" ]; then
  echo "    Client already declares every given function. Nothing to do."
  echo ""
  echo "    client_functions: ${RESULT}"
  exit 0
fi

STATUS=$(api_put "/${REALM}/clients/${CLIENT_UUID}" "${BODY}")
[ "${STATUS}" = "204" ] || { echo "ERROR: Unexpected HTTP status: ${STATUS}" >&2; exit 1; }
echo "    Done."

echo ""
echo "==> Functions added to '${CLIENT_ID}'."
echo ""
echo "    Added:            ${ADDED}"
echo "    client_functions: ${RESULT}"
echo ""
echo "    For an OIDC client, run the reconciliation so that it receives the scopes,"
echo "    policies and permissions for the new functions:"
echo ""
echo "      curl -X POST <iam-admin-app>/api/clients/reconcile"
echo ""
echo "    A resource server holds no artifacts and needs no reconciliation."
