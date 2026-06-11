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
# create-admin-user.sh
#
# Create a user in the specified Keycloak realm and assign the superuser role.
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
NEW_USERNAME=""
NEW_PASSWORD=""
EMAIL=""
CACERT=""
INSECURE="false"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Create a user in the specified Keycloak realm and assign the superuser role.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Options:
  --url <url>              Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>       URL path prefix (e.g. /auth). Default: empty
  --realm <realm>          Keycloak realm name
  --username <user>        Admin username for master realm (default: admin)
  --password <pass>        Admin password for master realm
  --new-username <user>    Username for the new user
  --new-password <pass>    Password for the new user
  --email <email>          Email address for the new user (optional)
  --cacert <file>          CA certificate file for TLS verification
  --insecure               Skip TLS certificate verification (dev only)
  --help, -h               Show this help message
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)        usage; exit 0 ;;
    --url)            KC_URL="$2";        shift 2 ;;
    --base-path)      KC_BASE_PATH="$2";  shift 2 ;;
    --realm)          REALM="$2";         shift 2 ;;
    --username)       KC_USER="$2";       shift 2 ;;
    --password)       KC_PASS="$2";       shift 2 ;;
    --new-username)   NEW_USERNAME="$2";  shift 2 ;;
    --new-password)   NEW_PASSWORD="$2";  shift 2 ;;
    --email)          EMAIL="$2";         shift 2 ;;
    --cacert)         CACERT="$2";        shift 2 ;;
    --insecure)       INSECURE="true";    shift ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

[ -z "${KC_URL}"       ] && { read -r -p "Keycloak URL: " KC_URL; }
[ -z "${REALM}"        ] && { read -r -p "Realm: " REALM; }
[ -z "${KC_USER}"      ] && { read -r -p "Admin username: " KC_USER; }
[ -z "${KC_PASS}"      ] && { read -r -s -p "Admin password: " KC_PASS; echo ""; }
[ -z "${NEW_USERNAME}" ] && { read -r -p "New username: " NEW_USERNAME; }
[ -z "${NEW_PASSWORD}" ] && { read -r -s -p "New password: " NEW_PASSWORD; echo ""; }

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

api_post() {
  local path="$1" body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X POST \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}"
}

# POST and return UUID from Location header
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
# Step 1 — Create user (idempotent)
# ---------------------------------------------------------------------------

echo "==> Looking up user '${NEW_USERNAME}' in realm '${REALM}'..."
NEW_USERNAME_ENC=$(urlencode "${NEW_USERNAME}")
USER_ID=$(api_get "/${REALM}/users?username=${NEW_USERNAME_ENC}&exact=true&max=1" | python3 -c "
import sys, json
users = json.load(sys.stdin)
print(users[0]['id'] if users else '')
" 2>/dev/null || echo "")

if [ -n "${USER_ID}" ]; then
  echo "    User '${NEW_USERNAME}' already exists (ID: ${USER_ID}) — skipping creation."
else
  echo "==> Creating user '${NEW_USERNAME}'..."
  CREATE_BODY=$(python3 -c '
import json, sys
body = {
  "username": sys.argv[1],
  "enabled": True,
}
if sys.argv[2]:
    body["email"] = sys.argv[2]
print(json.dumps(body))
' "${NEW_USERNAME}" "${EMAIL}")

  USER_ID=$(api_create "/${REALM}/users" "${CREATE_BODY}")
  if [ -z "${USER_ID}" ]; then
    # Location header not captured — fall back to lookup
    USER_ID=$(api_get "/${REALM}/users?username=${NEW_USERNAME_ENC}&exact=true&max=1" | python3 -c "
import sys, json
users = json.load(sys.stdin)
print(users[0]['id'] if users else '')
" 2>/dev/null || echo "")
  fi

  [ -z "${USER_ID}" ] && { echo "ERROR: Failed to create user '${NEW_USERNAME}'." >&2; exit 1; }
  echo "    User created (ID: ${USER_ID})."
fi

# ---------------------------------------------------------------------------
# Step 2 — Set password
# ---------------------------------------------------------------------------

echo "==> Setting password for '${NEW_USERNAME}'..."
PASS_BODY=$(python3 -c '
import json, sys
print(json.dumps({"type": "password", "value": sys.argv[1], "temporary": False}))
' "${NEW_PASSWORD}")
STATUS=$(api_put "/${REALM}/users/${USER_ID}/reset-password" "${PASS_BODY}")
[ "${STATUS}" = "204" ] && echo "    Password set." || { echo "ERROR: Unexpected HTTP status: ${STATUS}" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Step 3 — Assign superuser realm role
# ---------------------------------------------------------------------------

echo "==> Assigning 'superuser' realm role to '${NEW_USERNAME}'..."
ROLE_JSON=$(api_get "/${REALM}/roles/superuser")
ROLE_ID=$(echo "${ROLE_JSON}" | python3 -c "
import sys, json
try:
  print(json.load(sys.stdin).get('id', ''))
except:
  print('')
" 2>/dev/null || echo "")

[ -z "${ROLE_ID}" ] && { echo "ERROR: Realm role 'superuser' not found. Run bootstrap-realm.sh first." >&2; exit 1; }

ROLE_BODY=$(python3 -c "
import json, sys
print(json.dumps([{'id': sys.argv[1], 'name': 'superuser'}]))
" "${ROLE_ID}")
STATUS=$(api_post "/${REALM}/users/${USER_ID}/role-mappings/realm" "${ROLE_BODY}")
case "${STATUS}" in
  204) echo "    Role 'superuser' assigned." ;;
  409) echo "    Role 'superuser' already assigned." ;;
  *)   echo "ERROR: Unexpected HTTP status: ${STATUS}" >&2; exit 1 ;;
esac

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> User '${NEW_USERNAME}' is ready."
echo ""
echo "    Summary:"
echo "      Realm    : ${REALM}"
echo "      Username : ${NEW_USERNAME}"
[ -n "${EMAIL}" ] && echo "      Email    : ${EMAIL}"
echo "      Role     : superuser"
echo ""
