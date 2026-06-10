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
# bootstrap-realm.sh
#
# Bootstrap a Keycloak realm with groups, roles, client scopes, user profile
# attributes, and client policies required by the IAM system.
#
# Uses the Keycloak Admin REST API (curl + python3)
# Safe to re-run: all steps are idempotent.
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
DISPLAY_NAME=""
CACERT=""
INSECURE="false"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Bootstrap a Keycloak realm with groups, roles, client scopes, user profile
attributes, and client policies required by the IAM system.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Options:
  --url <url>              Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>       URL path prefix (e.g. /auth). Default: empty
  --realm <realm>          Keycloak realm name to create
  --username <user>        Admin username for master realm (default: admin)
  --password <pass>        Admin password for master realm
  --display-name <name>    Realm display name (defaults to realm name)
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
    --url)            KC_URL="$2";          shift 2 ;;
    --base-path)      KC_BASE_PATH="$2";    shift 2 ;;
    --realm)          REALM="$2";           shift 2 ;;
    --username)       KC_USER="$2";         shift 2 ;;
    --password)       KC_PASS="$2";         shift 2 ;;
    --display-name)   DISPLAY_NAME="$2";    shift 2 ;;
    --cacert)         CACERT="$2";          shift 2 ;;
    --insecure)       INSECURE="true";      shift ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

[ -z "${KC_URL}"  ] && { read -r -p "Keycloak URL: " KC_URL; }
[ -z "${REALM}"   ] && { read -r -p "Realm: " REALM; }
[ -z "${KC_USER}" ] && { read -r -p "Admin username: " KC_USER; }
[ -z "${KC_PASS}" ] && { read -r -s -p "Admin password: " KC_PASS; echo ""; }
[ -z "${DISPLAY_NAME}" ] && DISPLAY_NAME="${REALM}"

# ---------------------------------------------------------------------------
# curl setup
# ---------------------------------------------------------------------------

CURL_OPTS=(-s)
[ -n "${CACERT}"          ] && CURL_OPTS+=(--cacert "${CACERT}")
[ "${INSECURE}" = "true"  ] && CURL_OPTS+=(-k)

ADMIN_BASE="${KC_URL}${KC_BASE_PATH}/admin"
REALM_BASE="${ADMIN_BASE}/realms/${REALM}"
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
  curl "${CURL_OPTS[@]}" -H "Authorization: Bearer ${TOKEN}" "${1}"
}

api_get_status() {
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" "${1}"
}

api_post() {
  local url="$1" body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X POST \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${url}"
}

api_put() {
  local url="$1" body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${url}"
}

# ---------------------------------------------------------------------------
# Step 0 — Authenticate
# ---------------------------------------------------------------------------

echo ""
echo "==> Authenticating as '${KC_USER}'..."
TOKEN=$(get_token)
[ -z "${TOKEN}" ] && { echo "ERROR: Failed to obtain admin token." >&2; exit 1; }
echo "    Token obtained."

# ---------------------------------------------------------------------------
# Step 0b — Pre-flight: check deployed provider JARs
# ---------------------------------------------------------------------------
#
# Checks for custom protocol mapper / executor providers in Keycloak's
# server-info. Failures are warnings only — bootstrap continues so that
# all idempotent realm-level steps are not blocked by a missing JAR.

echo "==> Pre-flight: checking deployed provider JARs..."

SERVERINFO=$(api_get "${KC_URL}${KC_BASE_PATH}/admin/serverinfo")
PROVIDER_CHECK_OK=true

check_provider() {
  local provider_type="$1" jar_hint="$2"
  local found
  found=$(echo "${SERVERINFO}" | python3 -c "
import sys, json
info = json.load(sys.stdin)
# Check protocolMapperTypes (openid-connect and saml)
for proto_mappers in info.get('protocolMapperTypes', {}).values():
    for m in proto_mappers:
        if m.get('id') == '${provider_type}':
            print('yes')
            sys.exit(0)
# Check clientInstallations (executor providers live elsewhere in serverinfo)
for section in info.get('providers', {}).values():
    if isinstance(section, dict) and '${provider_type}' in section.get('providers', {}):
        print('yes')
        sys.exit(0)
print('no')
" 2>/dev/null || echo "no")
  if [ "${found}" = "yes" ]; then
    echo "    [OK] ${provider_type}"
  else
    echo ""
    echo "    WARNING: Provider type '${provider_type}' not found in Keycloak."
    echo "             Expected JAR: ${jar_hint}"
    echo "             Deploy the JAR to Keycloak's providers directory and rebuild"
    echo "             Keycloak (kc.sh build) before this provider type will be available."
    echo ""
    PROVIDER_CHECK_OK=false
  fi
}

check_provider "oidc-sweden-claims-mapper"   "oidc-sweden-claims-plugin-*.jar"
check_provider "org-rights-mapper"           "org-rights-mapper-*.jar"
check_provider "scope-org-identifier-mapper" "scope-org-identifier-mapper-*.jar"
check_provider "resource-audience-mapper"    "resource-aud-plugin-*.jar"

if [ "${PROVIDER_CHECK_OK}" = "false" ]; then
  echo "    One or more provider JARs are missing (see warnings above)."
  echo "    Bootstrap will continue, but mappers that depend on these providers"
  echo "    will not be fully configured until the JARs are deployed and Keycloak"
  echo "    is rebuilt."
  echo ""
fi

# ---------------------------------------------------------------------------
# Step 1 — Create realm
# ---------------------------------------------------------------------------

echo "==> Creating realm '${REALM}'..."
REALM_STATUS=$(api_get_status "${REALM_BASE}")
if [ "${REALM_STATUS}" = "200" ]; then
  echo "    Realm '${REALM}' already exists — skipping creation."
else
  REALM_BODY=$(python3 -c '
import json, sys
print(json.dumps({
  "realm": sys.argv[1],
  "enabled": True,
  "displayName": sys.argv[2],
  "displayNameHtml": sys.argv[2],
  "registrationAllowed": False,
  "loginWithEmailAllowed": False,
  "resetPasswordAllowed": False,
}))
' "${REALM}" "${DISPLAY_NAME}")
  STATUS=$(api_post "${ADMIN_BASE}/realms" "${REALM_BODY}")
  [ "${STATUS}" = "201" ] && echo "    Realm '${REALM}' created." || { echo "ERROR: Failed to create realm (HTTP ${STATUS})." >&2; exit 1; }
fi

# ---------------------------------------------------------------------------
# Step 1b — Configure ACR-to-LoA mappings and Fine-Grained Admin Permissions
# ---------------------------------------------------------------------------

echo "==> Configuring realm settings (ACR-to-LoA, admin permissions)..."

# Read-merge-write to avoid clobbering other realm settings
CURRENT_REALM=$(api_get "${REALM_BASE}")
UPDATED_REALM=$(CURRENT_JSON="${CURRENT_REALM}" python3 -c "
import os, json
realm = json.loads(os.environ['CURRENT_JSON'])

if not realm.get('attributes'):
    realm['attributes'] = {}
realm['attributes']['acr.loa.map'] = (
    '{\"http://id.elegnamnden.se/loa/1.0/loa2\":\"0\",'
    '\"http://id.swedenconnect.se/loa/1.0/uncertified-loa3\":\"1\",'
    '\"http://id.elegnamnden.se/loa/1.0/loa3\":\"2\",'
    '\"http://id.elegnamnden.se/loa/1.0/loa4\":\"3\"}'
)
realm['adminPermissionsEnabled'] = True
print(json.dumps(realm))
")
STATUS=$(api_put "${REALM_BASE}" "${UPDATED_REALM}")
[ "${STATUS}" = "204" ] && echo "    Done." || echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2

# ---------------------------------------------------------------------------
# Step 2 — Top-level groups
# ---------------------------------------------------------------------------

echo "==> Creating top-level groups..."
EXISTING_GROUPS=$(api_get "${REALM_BASE}/groups" | python3 -c "
import sys, json
groups = json.load(sys.stdin)
print(' '.join(g.get('name','') for g in groups))
" 2>/dev/null || echo "")

for GROUP in orgs functions; do
  if echo "${EXISTING_GROUPS}" | grep -qw "${GROUP}"; then
    echo "    Group '${GROUP}' already exists — skipping."
  else
    STATUS=$(api_post "${REALM_BASE}/groups" "{\"name\":\"${GROUP}\"}")
    [ "${STATUS}" = "201" ] && echo "    Group '${GROUP}' created." || echo "    WARNING: Group '${GROUP}' status: ${STATUS}" >&2
  fi
done

# ---------------------------------------------------------------------------
# Step 3 — Realm role: superuser
# ---------------------------------------------------------------------------

echo "==> Creating realm role 'superuser'..."
ROLE_STATUS=$(api_get_status "${REALM_BASE}/roles/superuser")
if [ "${ROLE_STATUS}" = "200" ]; then
  echo "    Role 'superuser' already exists — skipping."
else
  STATUS=$(api_post "${REALM_BASE}/roles" \
    '{"name":"superuser","description":"Full access to all organizations, functions and users."}')
  [ "${STATUS}" = "201" ] && echo "    Role 'superuser' created." || echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2
fi

# ---------------------------------------------------------------------------
# Step 4 — naturalPersonNumber client scope
# ---------------------------------------------------------------------------

PNR_SCOPE="https://id.oidc.se/scope/naturalPersonNumber"
echo "==> Creating client scope '${PNR_SCOPE}'..."

ALL_SCOPES=$(api_get "${REALM_BASE}/client-scopes")
PNR_SCOPE_ID=$(echo "${ALL_SCOPES}" | python3 -c "
import sys, json
scopes = json.load(sys.stdin)
nxt = next((s['id'] for s in scopes if s.get('name') == '${PNR_SCOPE}'), '')
print(nxt)
" 2>/dev/null || echo "")

if [ -n "${PNR_SCOPE_ID}" ]; then
  echo "    Client scope already exists (ID: ${PNR_SCOPE_ID}) — skipping creation."
else
  STATUS=$(api_post "${REALM_BASE}/client-scopes" \
    "$(python3 -c '
import json, sys
print(json.dumps({
  "name": sys.argv[1],
  "protocol": "openid-connect",
  "attributes": {
    "include.in.token.scope": "true",
    "display.on.consent.screen": "true",
  }
}))
' "${PNR_SCOPE}")")
  if [ "${STATUS}" = "201" ]; then
    echo "    Client scope created."
    # Re-fetch to get the ID
    ALL_SCOPES=$(api_get "${REALM_BASE}/client-scopes")
    PNR_SCOPE_ID=$(echo "${ALL_SCOPES}" | python3 -c "
import sys, json
scopes = json.load(sys.stdin)
nxt = next((s['id'] for s in scopes if s.get('name') == '${PNR_SCOPE}'), '')
print(nxt)
" 2>/dev/null || echo "")
  else
    echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2
  fi
fi

# Add oidc-sweden-claims-mapper to the scope
if [ -n "${PNR_SCOPE_ID}" ]; then
  echo "==> Adding oidc-sweden-claims-mapper to scope '${PNR_SCOPE}'..."
  EXISTING_MAPPER=$(api_get "${REALM_BASE}/client-scopes/${PNR_SCOPE_ID}/protocol-mappers/models" | python3 -c "
import sys, json
mappers = json.load(sys.stdin)
print('yes' if any(m.get('name') == 'oidc-sweden-claims-mapper' for m in mappers) else 'no')
" 2>/dev/null || echo "no")

  if [ "${EXISTING_MAPPER}" = "yes" ]; then
    echo "    Mapper already exists — skipping."
  else
    STATUS=$(api_post "${REALM_BASE}/client-scopes/${PNR_SCOPE_ID}/protocol-mappers/models" \
      '{"name":"oidc-sweden-claims-mapper","protocol":"openid-connect","protocolMapper":"oidc-sweden-claims-mapper","consentRequired":false,"config":{"id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"true"}}')
    case "${STATUS}" in
      201) echo "    Mapper added." ;;
      400|404)
        echo ""
        echo "    WARNING: Could not add 'oidc-sweden-claims-mapper' (HTTP ${STATUS})."
        echo "             Ensure the oidc-sweden-claims-plugin JAR is deployed and"
        echo "             Keycloak has been rebuilt (kc.sh build)."
        echo ""
        ;;
      *) echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2 ;;
    esac
  fi
fi

# ---------------------------------------------------------------------------
# Step 5 — phone client scope (built-in — create only if missing)
# ---------------------------------------------------------------------------

echo "==> Checking phone client scope..."
PHONE_SCOPE_ID=$(echo "${ALL_SCOPES}" | python3 -c "
import sys, json
scopes = json.load(sys.stdin)
nxt = next((s['id'] for s in scopes if s.get('name') == 'phone'), '')
print(nxt)
" 2>/dev/null || echo "")

if [ -n "${PHONE_SCOPE_ID}" ]; then
  echo "    Built-in 'phone' scope already exists — skipping."
else
  echo "    Built-in 'phone' scope not found — creating..."
  STATUS=$(api_post "${REALM_BASE}/client-scopes" \
    '{"name":"phone","protocol":"openid-connect","attributes":{"include.in.token.scope":"true","display.on.consent.screen":"false"}}')
  if [ "${STATUS}" = "201" ]; then
    # Fetch new ID
    PHONE_SCOPE_ID=$(api_get "${REALM_BASE}/client-scopes" | python3 -c "
import sys, json
scopes = json.load(sys.stdin)
nxt = next((s['id'] for s in scopes if s.get('name') == 'phone'), '')
print(nxt)
" 2>/dev/null || echo "")

    if [ -n "${PHONE_SCOPE_ID}" ]; then
      STATUS=$(api_post "${REALM_BASE}/client-scopes/${PHONE_SCOPE_ID}/protocol-mappers/models" \
        '{"name":"phone number","protocol":"openid-connect","protocolMapper":"oidc-usermodel-attribute-mapper","consentRequired":false,"config":{"user.attribute":"phoneNumber","claim.name":"phone_number","jsonType.label":"String","id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"true"}}')
      [ "${STATUS}" = "201" ] \
        && echo "    'phone' scope created with phone_number mapper." \
        || echo "    WARNING: 'phone' scope created but mapper status: ${STATUS}" >&2
    fi
  else
    echo "    WARNING: Unexpected HTTP status creating 'phone' scope: ${STATUS}" >&2
  fi
fi

# ---------------------------------------------------------------------------
# Step 6 — personalIdentityNumber user profile attribute
# ---------------------------------------------------------------------------

echo "==> Configuring personalIdentityNumber user profile attribute..."
PROFILE_JSON=$(api_get "${REALM_BASE}/users/profile")

ATTR_EXISTS=$(echo "${PROFILE_JSON}" | python3 -c "
import sys, json
profile = json.load(sys.stdin)
attrs = profile.get('attributes', [])
print('yes' if any(a.get('name') == 'personalIdentityNumber' for a in attrs) else 'no')
" 2>/dev/null || echo "no")

if [ "${ATTR_EXISTS}" = "yes" ]; then
  echo "    Attribute 'personalIdentityNumber' already exists — skipping."
else
  UPDATED_PROFILE=$(PROFILE_JSON="${PROFILE_JSON}" python3 -c "
import os, json
profile = json.loads(os.environ['PROFILE_JSON'])
if 'attributes' not in profile:
    profile['attributes'] = []
profile['attributes'].append({
    'name': 'personalIdentityNumber',
    'displayName': 'Personal Identity Number',
    'validations': {},
    'annotations': {},
    'permissions': {
        'view': ['admin', 'user'],
        'edit': ['admin']
    },
    'multivalued': False,
    'selector': {
        'scopes': ['https://id.oidc.se/scope/naturalPersonNumber']
    }
})
print(json.dumps(profile))
")
  STATUS=$(api_put "${REALM_BASE}/users/profile" "${UPDATED_PROFILE}")
  [ "${STATUS}" = "200" ] && echo "    Attribute 'personalIdentityNumber' added." || echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2
fi

# ---------------------------------------------------------------------------
# Step 7 — Client Policy: resource-function-executor
# ---------------------------------------------------------------------------
#
# Creates a Client Policy profile containing the resource-function-executor,
# and a Client Policy that applies the profile to all confidential clients.
# Uses fetch-merge-put to avoid clobbering any existing custom profiles/policies.

echo "==> Configuring resource-function-executor Client Policy..."

EXISTING_PROFILES_JSON=$(api_get "${REALM_BASE}/client-policies/profiles")
PROFILE_EXISTS=$(echo "${EXISTING_PROFILES_JSON}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
profiles = data.get('profiles', [])
print('yes' if any(p.get('name') == 'resource-function-profile' for p in profiles) else 'no')
" 2>/dev/null || echo "no")

if [ "${PROFILE_EXISTS}" = "yes" ]; then
  echo "    Client Policy profile 'resource-function-profile' already exists — skipping."
else
  UPDATED_PROFILES=$(PROFILES_JSON="${EXISTING_PROFILES_JSON}" python3 -c "
import os, json
data = json.loads(os.environ['PROFILES_JSON'])
profiles = data.get('profiles', [])
profiles.append({
    'name': 'resource-function-profile',
    'description': 'Validates the resource parameter against the target client\\'s client_functions attribute.',
    'executors': [{
        'executor': 'resource-function-executor',
        'configuration': {}
    }]
})
data['profiles'] = profiles
print(json.dumps(data))
")
  STATUS=$(api_put "${REALM_BASE}/client-policies/profiles" "${UPDATED_PROFILES}")
  case "${STATUS}" in
    200|204) echo "    Client Policy profile 'resource-function-profile' created." ;;
    400|404)
      echo ""
      echo "    WARNING: Could not create Client Policy profile (HTTP ${STATUS})."
      echo "             The 'resource-function-executor' provider may not be deployed."
      echo "             Ensure the resource-aud-plugin JAR is deployed and"
      echo "             Keycloak has been rebuilt (kc.sh build)."
      echo ""
      ;;
    *) echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2 ;;
  esac
fi

EXISTING_POLICIES_JSON=$(api_get "${REALM_BASE}/client-policies/policies")
POLICY_EXISTS=$(echo "${EXISTING_POLICIES_JSON}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
policies = data.get('policies', [])
print('yes' if any(p.get('name') == 'resource-function-policy' for p in policies) else 'no')
" 2>/dev/null || echo "no")

if [ "${POLICY_EXISTS}" = "yes" ]; then
  echo "    Client Policy 'resource-function-policy' already exists — skipping."
else
  UPDATED_POLICIES=$(POLICIES_JSON="${EXISTING_POLICIES_JSON}" python3 -c "
import os, json
data = json.loads(os.environ['POLICIES_JSON'])
policies = data.get('policies', [])
policies.append({
    'name': 'resource-function-policy',
    'description': 'Applies resource parameter validation to all confidential clients.',
    'enabled': True,
    'conditions': [{
        'condition': 'client-access-type',
        'configuration': {'type': ['confidential']}
    }],
    'profiles': ['resource-function-profile']
})
data['policies'] = policies
print(json.dumps(data))
")
  STATUS=$(api_put "${REALM_BASE}/client-policies/policies" "${UPDATED_POLICIES}")
  case "${STATUS}" in
    200|204) echo "    Client Policy 'resource-function-policy' created." ;;
    *) echo "    WARNING: Unexpected HTTP status: ${STATUS}" >&2 ;;
  esac
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> Bootstrap of realm '${REALM}' complete."
echo ""
echo "    Next steps:"
echo "    - Create the initial superuser account (see create-admin-user.sh)"
echo "    - Register OAuth/OIDC clients (see add-oidc-client.sh)"
echo "    - Register resource servers (see add-resource-server.sh)"
echo "    - Attach functions to organizations as needed"
