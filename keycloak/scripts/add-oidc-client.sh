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
# add-oidc-client.sh
#
# Register an OIDC relying-party client in Keycloak with private_key_jwt
# authentication, Authorization Services, and protocol mappers.
#
# Uses the Keycloak Admin REST API (curl + python3)
# Safe to re-run: idempotent for existing clients and mappers.
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
REDIRECT_URIS=()
JWKS_URL=""
SERVICE_ACCOUNT="false"
ORG_RIGHTS_ID_TOKEN="true"
ORG_RIGHTS_ACCESS_TOKEN="true"
CACERT=""
INSECURE="false"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Register an OIDC relying-party client in Keycloak with private_key_jwt
authentication, Authorization Services, and protocol mappers.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Options:
  --url <url>                      Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>               URL path prefix (e.g. /auth). Default: empty
  --realm <realm>                  Keycloak realm name
  --username <username>            Admin username (default: admin)
  --password <password>            Admin password
  --client-id <id>                 Client ID (e.g. https://my-app.example.com)
  --name <name>                    Display name shown in the Keycloak admin UI
  --redirect-uri <pattern>         Redirect URI pattern (repeatable)
  --jwks-url <url>                 JWKS endpoint URL (default: <client-id>/jwks)
  --service-account                Keep the service account and assign
                                   realm-management roles
  --no-org-rights-id-token         Exclude org_rights from the ID token
  --no-org-rights-access-token     Exclude org_rights from the access token
  --cacert <file>                  CA certificate file for TLS verification
  --insecure                       Skip TLS certificate verification (dev only)
  --help, -h                       Show this help message

All parameters are optional on the command line; missing required values
will be prompted for interactively. --redirect-uri may be specified
multiple times.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)                      usage; exit 0 ;;
    --url)                          KC_URL="$2";              shift 2 ;;
    --base-path)                    KC_BASE_PATH="$2";        shift 2 ;;
    --realm)                        REALM="$2";               shift 2 ;;
    --username)                     KC_USER="$2";             shift 2 ;;
    --password)                     KC_PASS="$2";             shift 2 ;;
    --client-id)                    CLIENT_ID="$2";           shift 2 ;;
    --name)                         NAME="$2";                shift 2 ;;
    --redirect-uri)                 REDIRECT_URIS+=("$2");    shift 2 ;;
    --jwks-url)                     JWKS_URL="$2";            shift 2 ;;
    --service-account)              SERVICE_ACCOUNT="true";   shift ;;
    --no-org-rights-id-token)       ORG_RIGHTS_ID_TOKEN="false";    shift ;;
    --no-org-rights-access-token)   ORG_RIGHTS_ACCESS_TOKEN="false"; shift ;;
    --cacert)                       CACERT="$2";              shift 2 ;;
    --insecure)                     INSECURE="true";          shift ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Interactive prompts for missing required values
# ---------------------------------------------------------------------------

if [ -z "${KC_URL}" ]; then
  read -r -p "Keycloak URL: " KC_URL
fi
if [ -z "${REALM}" ]; then
  read -r -p "Realm: " REALM
fi
if [ -z "${KC_USER}" ]; then
  read -r -p "Admin username: " KC_USER
fi
if [ -z "${KC_PASS}" ]; then
  read -r -s -p "Admin password: " KC_PASS
  echo ""
fi
if [ -z "${CLIENT_ID}" ]; then
  read -r -p "Client ID (e.g. https://my-app.example.com): " CLIENT_ID
fi
if [ ${#REDIRECT_URIS[@]} -eq 0 ]; then
  echo "Enter redirect URI patterns, one per line. Empty line to finish."
  while true; do
    read -r -p "Redirect URI pattern (e.g. /login/oauth2/code/*): " _URI
    [ -z "${_URI}" ] && break
    REDIRECT_URIS+=("${_URI}")
  done
  if [ ${#REDIRECT_URIS[@]} -eq 0 ]; then
    echo "ERROR: at least one redirect URI is required." >&2
    exit 1
  fi
fi

# Derive default JWKS URL from client ID if not provided
if [ -z "${JWKS_URL}" ]; then
  JWKS_URL="${CLIENT_ID}/jwks"
fi

# ---------------------------------------------------------------------------
# curl setup
# ---------------------------------------------------------------------------

CURL_OPTS=(-s)
if [ -n "${CACERT}" ]; then
  CURL_OPTS+=(--cacert "${CACERT}")
fi
if [ "${INSECURE}" = "true" ]; then
  CURL_OPTS+=(-k)
fi

ADMIN_BASE="${KC_URL}${KC_BASE_PATH}/admin/realms"
TOKEN_URL="${KC_URL}${KC_BASE_PATH}/realms/master/protocol/openid-connect/token"

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

get_token() {
  curl "${CURL_OPTS[@]}" -X POST "${TOKEN_URL}" \
    -d "grant_type=password&client_id=admin-cli&username=${KC_USER}&password=${KC_PASS}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])" 2>/dev/null
}

api_get() {
  curl "${CURL_OPTS[@]}" \
    -H "Authorization: Bearer ${TOKEN}" \
    "${ADMIN_BASE}${1}"
}

# Returns HTTP status code
api_post() {
  local path="$1"
  local body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X POST \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}"
}

# Returns HTTP status code; also writes response body to stdout on error
api_put() {
  local path="$1"
  local body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}"
}

# PUT with no request body (e.g. adding a scope to a client)
api_put_empty() {
  local path="$1"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    "${ADMIN_BASE}${path}"
}

api_delete() {
  local path="$1"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X DELETE \
    -H "Authorization: Bearer ${TOKEN}" \
    "${ADMIN_BASE}${path}"
}

# POST a new resource; extracts and returns the UUID from the Location header
api_create() {
  local path="$1"
  local body="$2"
  curl "${CURL_OPTS[@]}" -i -X POST \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}" \
  | grep -i '^location:' | sed 's|.*/||' | tr -d '\r\n'
}

urlencode() {
  python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$1"
}

# ---------------------------------------------------------------------------
# Authenticate
# ---------------------------------------------------------------------------

echo ""
echo "==> Authenticating as '${KC_USER}' against ${KC_URL}${KC_BASE_PATH}..."
TOKEN=$(get_token)
if [ -z "${TOKEN}" ]; then
  echo "ERROR: Failed to obtain admin token." >&2
  exit 1
fi
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
  echo "    Creating client..."
  CREATE_BODY=$(python3 -c "
import json, sys
print(json.dumps({
  'clientId': sys.argv[1],
  'protocol': 'openid-connect',
  'enabled': True,
  'clientAuthenticatorType': 'client-jwt',
  'standardFlowEnabled': True,
  'implicitFlowEnabled': False,
  'directAccessGrantsEnabled': False,
  'serviceAccountsEnabled': True,
  'publicClient': False,
}))
" "${CLIENT_ID}")

  CLIENT_UUID=$(api_create "/${REALM}/clients" "${CREATE_BODY}")
  if [ -z "${CLIENT_UUID}" ]; then
    # Location header not captured — fall back to lookup
    CLIENT_UUID=$(api_get "/${REALM}/clients?clientId=${CLIENT_ID_ENC}&max=1" | python3 -c "
import sys, json
clients = json.load(sys.stdin)
print(clients[0]['id'] if clients else '')
" 2>/dev/null || echo "")
  fi

  if [ -z "${CLIENT_UUID}" ]; then
    echo "ERROR: Failed to create client '${CLIENT_ID}'." >&2
    exit 1
  fi
  echo "    Client created (UUID: ${CLIENT_UUID})."
fi

# ---------------------------------------------------------------------------
# Step 2 — Configure client (read-merge-write to avoid clobbering settings)
# ---------------------------------------------------------------------------

echo "==> Configuring client settings..."
REDIRECT_URIS_JSON=$(python3 -c "import json,sys; print(json.dumps(sys.argv[1:]))" "${REDIRECT_URIS[@]}")

CURRENT_CLIENT=$(api_get "/${REALM}/clients/${CLIENT_UUID}")
UPDATED_CLIENT=$(
  CURRENT_JSON="${CURRENT_CLIENT}" \
  _NAME="${NAME}" \
  _JWKS_URL="${JWKS_URL}" \
  _ROOT_URL="${CLIENT_ID}" \
  _REDIRECT_URIS="${REDIRECT_URIS_JSON}" \
  python3 -c "
import os, json
client = json.loads(os.environ['CURRENT_JSON'])
redirect_uris = json.loads(os.environ['_REDIRECT_URIS'])

client['rootUrl'] = os.environ['_ROOT_URL']
client['redirectUris'] = redirect_uris
client['clientAuthenticatorType'] = 'client-jwt'
client['standardFlowEnabled'] = True
client['implicitFlowEnabled'] = False
client['directAccessGrantsEnabled'] = False
client['authorizationServicesEnabled'] = True
client['serviceAccountsEnabled'] = True

if not client.get('attributes'):
    client['attributes'] = {}
client['attributes']['iam_admin_managed'] = 'true'
client['attributes']['use.jwks.url'] = 'true'
client['attributes']['jwks.url'] = os.environ['_JWKS_URL']

name = os.environ['_NAME']
if name:
    client['name'] = name

print(json.dumps(client))
")

STATUS=$(api_put "/${REALM}/clients/${CLIENT_UUID}" "${UPDATED_CLIENT}")
if [ "${STATUS}" = "204" ]; then
  echo "    Done."
else
  echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2
fi

# ---------------------------------------------------------------------------
# Step 3 — Handle service account
# ---------------------------------------------------------------------------

SA_USER_ID=$(api_get "/${REALM}/clients/${CLIENT_UUID}/service-account-user" | python3 -c "
import sys, json
try:
  print(json.load(sys.stdin).get('id', ''))
except:
  print('')
" 2>/dev/null || echo "")

if [ "${SERVICE_ACCOUNT}" = "true" ]; then
  echo "==> Assigning realm-management roles to service account..."
  if [ -z "${SA_USER_ID}" ]; then
    echo "ERROR: Could not resolve service account user ID." >&2
    exit 1
  fi

  RM_UUID=$(api_get "/${REALM}/clients?clientId=realm-management&max=1" | python3 -c "
import sys, json
clients = json.load(sys.stdin)
print(clients[0]['id'] if clients else '')
" 2>/dev/null || echo "")

  if [ -z "${RM_UUID}" ]; then
    echo "ERROR: Could not find realm-management client." >&2
    exit 1
  fi

  for ROLE_NAME in manage-users query-groups view-users query-users manage-realm view-clients manage-clients; do
    ROLE_JSON=$(api_get "/${REALM}/clients/${RM_UUID}/roles/${ROLE_NAME}" 2>/dev/null || echo "{}")
    ROLE_ID=$(echo "${ROLE_JSON}" | python3 -c "
import sys, json
try:
  print(json.load(sys.stdin).get('id', ''))
except:
  print('')
" 2>/dev/null || echo "")

    if [ -z "${ROLE_ID}" ]; then
      echo "    Role '${ROLE_NAME}' not found — skipping."
      continue
    fi

    ROLE_BODY=$(python3 -c "
import json, sys
print(json.dumps([{'id': sys.argv[1], 'name': sys.argv[2]}]))
" "${ROLE_ID}" "${ROLE_NAME}")

    STATUS=$(api_post "/${REALM}/users/${SA_USER_ID}/role-mappings/clients/${RM_UUID}" "${ROLE_BODY}")
    case "${STATUS}" in
      204) echo "    Assigned: ${ROLE_NAME}" ;;
      409) echo "    Role '${ROLE_NAME}' already assigned — skipping." ;;
      *)   echo "    Role '${ROLE_NAME}': unexpected status ${STATUS} — skipping." ;;
    esac
  done
else
  echo "==> Removing service account user (not requested)..."
  if [ -n "${SA_USER_ID}" ]; then
    STATUS=$(api_delete "/${REALM}/users/${SA_USER_ID}")
    [ "${STATUS}" = "204" ] && echo "    Deleted." || echo "    Already absent or status: ${STATUS} — skipping."
  else
    echo "    Service account user not found — skipping."
  fi
fi

# ---------------------------------------------------------------------------
# Step 4 — Add protocol mappers
# ---------------------------------------------------------------------------

MAPPERS_RESP=$(api_get "/${REALM}/clients/${CLIENT_UUID}/protocol-mappers/models")

has_mapper() {
  local name="$1"
  echo "${MAPPERS_RESP}" | python3 -c "
import sys, json
mappers = json.load(sys.stdin)
print('yes' if any(m.get('name') == '${name}' for m in mappers) else 'no')
" 2>/dev/null
}

echo "==> Adding org-rights protocol mapper..."
if [ "$(has_mapper org-rights-mapper)" = "yes" ]; then
  echo "    Already exists — skipping."
else
  ORG_RIGHTS_BODY=$(python3 -c '
import json, sys
print(json.dumps({
  "name": "org-rights-mapper",
  "protocol": "openid-connect",
  "protocolMapper": "org-rights-mapper",
  "consentRequired": False,
  "config": {
    "id.token.claim": sys.argv[1],
    "access.token.claim": sys.argv[2],
  }
}))
' "${ORG_RIGHTS_ID_TOKEN}" "${ORG_RIGHTS_ACCESS_TOKEN}")
  STATUS=$(api_post "/${REALM}/clients/${CLIENT_UUID}/protocol-mappers/models" "${ORG_RIGHTS_BODY}")
  [ "${STATUS}" = "201" ] \
    && echo "    Added (ID token: ${ORG_RIGHTS_ID_TOKEN}, access token: ${ORG_RIGHTS_ACCESS_TOKEN})." \
    || echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2
fi

echo "==> Adding scope-org-identifier-mapper..."
if [ "$(has_mapper scope-org-identifier-mapper)" = "yes" ]; then
  echo "    Already exists — skipping."
else
  STATUS=$(api_post "/${REALM}/clients/${CLIENT_UUID}/protocol-mappers/models" \
    '{"name":"scope-org-identifier-mapper","protocol":"openid-connect","protocolMapper":"scope-org-identifier-mapper","consentRequired":false,"config":{"id.token.claim":"false","access.token.claim":"true"}}')
  [ "${STATUS}" = "201" ] && echo "    Added." || echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2
fi

echo "==> Adding resource-audience-mapper..."
if [ "$(has_mapper resource-audience-mapper)" = "yes" ]; then
  echo "    Already exists — skipping."
else
  STATUS=$(api_post "/${REALM}/clients/${CLIENT_UUID}/protocol-mappers/models" \
    '{"name":"resource-audience-mapper","protocol":"openid-connect","protocolMapper":"resource-audience-mapper","consentRequired":false,"config":{"id.token.claim":"false","access.token.claim":"true"}}')
  case "${STATUS}" in
    201) echo "    Added." ;;
    400|404)
      echo ""
      echo "    WARNING: Could not add 'resource-audience-mapper' (HTTP ${STATUS})."
      echo "             The provider type may not be deployed. Ensure the"
      echo "             resource-aud-plugin JAR is in Keycloak's providers directory"
      echo "             and that Keycloak has been rebuilt (kc.sh build)."
      echo ""
      ;;
    *) echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2 ;;
  esac
fi

# ---------------------------------------------------------------------------
# Step 5 — Add optional client scopes
# ---------------------------------------------------------------------------

PNR_SCOPE="https://id.oidc.se/scope/naturalPersonNumber"
ALL_SCOPES=$(api_get "/${REALM}/client-scopes")

PNR_SCOPE_ID=$(echo "${ALL_SCOPES}" | python3 -c "
import sys, json
scopes = json.load(sys.stdin)
nxt = next((s['id'] for s in scopes if s.get('name') == '${PNR_SCOPE}'), '')
print(nxt)
" 2>/dev/null || echo "")

PHONE_SCOPE_ID=$(echo "${ALL_SCOPES}" | python3 -c "
import sys, json
scopes = json.load(sys.stdin)
nxt = next((s['id'] for s in scopes if s.get('name') == 'phone'), '')
print(nxt)
" 2>/dev/null || echo "")

echo "==> Adding '${PNR_SCOPE}' as optional client scope..."
if [ -z "${PNR_SCOPE_ID}" ]; then
  echo "    WARNING: Scope not found in realm '${REALM}'. Run bootstrap-realm.sh first."
else
  STATUS=$(api_put_empty "/${REALM}/clients/${CLIENT_UUID}/optional-client-scopes/${PNR_SCOPE_ID}")
  [ "${STATUS}" = "204" ] && echo "    Added." || echo "    Already present or status: ${STATUS} — skipping."
fi

echo "==> Adding 'phone' as optional client scope..."
if [ -z "${PHONE_SCOPE_ID}" ]; then
  echo "    WARNING: Scope 'phone' not found in realm '${REALM}'. Run bootstrap-realm.sh first."
else
  STATUS=$(api_put_empty "/${REALM}/clients/${CLIENT_UUID}/optional-client-scopes/${PHONE_SCOPE_ID}")
  [ "${STATUS}" = "204" ] && echo "    Added." || echo "    Already present or status: ${STATUS} — skipping."
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> Client '${CLIENT_ID}' registered successfully."
echo ""
echo "    Summary:"
echo "      Client ID          : ${CLIENT_ID}"
[ -n "${NAME}" ] && echo "      Display name       : ${NAME}"
echo "      Client UUID        : ${CLIENT_UUID}"
echo "      JWKS URL           : ${JWKS_URL}"
echo "      Redirect URIs      : ${REDIRECT_URIS[*]}"
echo "      Service account    : ${SERVICE_ACCOUNT}"
echo "      org-rights ID token: ${ORG_RIGHTS_ID_TOKEN}"
echo "      org-rights acc.tok.: ${ORG_RIGHTS_ACCESS_TOKEN}"
echo ""
echo "    Next steps:"
echo "      - Ensure the application is running and its JWKS endpoint is reachable"
echo "        at ${JWKS_URL} before the first token request is made."
echo "      - {org}:{function}:{right} scopes and their Authorization Services policies"
echo "        are created automatically by the IAM admin application when functions are"
echo "        attached to organizations."
