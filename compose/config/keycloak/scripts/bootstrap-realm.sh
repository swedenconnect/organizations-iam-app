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
Bootstrap a Keycloak realm with groups, roles, client scopes, user profile
attributes, and client policies required by the IAM system.

Usage: $0 <realm> <username> <password> [display-name]

Positional arguments:
  realm          Keycloak realm name to create
  username       Admin username for Keycloak master realm
  password       Admin password for Keycloak master realm
  display-name   Realm display name (defaults to the realm name)

Note: This is the inner script invoked by the outer wrapper. Use the
wrapper script in compose/keycloak-scripts/ which accepts named arguments.
EOF
  exit 0
fi

if [ $# -lt 3 ]; then
  echo "Usage: $0 <realm> <username> <password> [display-name]" >&2
  exit 1
fi

REALM="$1"
USERNAME="$2"
PASSWORD="$3"
DISPLAY_NAME="${4:-$REALM}"

KCADM="/opt/keycloak/bin/kcadm.sh"
SERVER_URL="http://keycloak:8080"

# ---------------------------------------------------------------------------
# 0. Pre-flight: verify required provider JARs are deployed
# ---------------------------------------------------------------------------
#
# The bootstrap script itself only requires the oidc-sweden-claims-mapper
# provider type. The org-rights-mapper and scope-org-identifier-mapper are
# needed later by add-oidc-client.sh. We check all three here so that a
# missing JAR is caught as early as possible in the setup flow.
#
# We query Keycloak's server-info endpoint for the list of known
# protocol-mapper provider types. Each entry in the response contains a
# providerType id field that matches the name used when registering a mapper.
#
# Failures are warnings, not errors: the script continues regardless so that
# all idempotent realm-level setup steps are not blocked by a missing JAR.

echo "==> Pre-flight: checking deployed provider JARs..."

PROVIDER_CHECK_OK=true

check_provider() {
  local PROVIDER_TYPE="$1"
  local JAR_HINT="$2"
  local SERVERINFO_FILE="$3"

  local TYPE_FOUND
  TYPE_FOUND=$(grep "\"${PROVIDER_TYPE}\" :" "${SERVERINFO_FILE}" || true)

  if [ -z "${TYPE_FOUND}" ]; then
    echo ""
    echo "  WARNING: Provider type '${PROVIDER_TYPE}' not found in Keycloak."
    echo "           Expected JAR: ${JAR_HINT}"
    echo "           Deploy the JAR to Keycloak's providers directory and rebuild"
    echo "           Keycloak (kc.sh build) before this provider type will be available."
    echo ""
    PROVIDER_CHECK_OK=false
  else
    echo "    [OK] ${PROVIDER_TYPE}"
  fi
}

# ---------------------------------------------------------------------------
# Authenticate
# ---------------------------------------------------------------------------

echo "==> Authenticating as '${USERNAME}'..."
"${KCADM}" config credentials \
  --server "${SERVER_URL}" \
  --realm master \
  --user "${USERNAME}" \
  --password "${PASSWORD}"

# Fetch serverinfo once and reuse for all provider checks.
SERVERINFO_TMP=$(mktemp)
( "${KCADM}" get serverinfo ) > "${SERVERINFO_TMP}" 2>&1 || true

check_provider "oidc-sweden-claims-mapper"    "oidc-sweden-claims-plugin-*.jar"   "${SERVERINFO_TMP}"
check_provider "org-rights-mapper"            "org-rights-mapper-*.jar"           "${SERVERINFO_TMP}"
check_provider "scope-org-identifier-mapper"  "scope-org-identifier-mapper-*.jar" "${SERVERINFO_TMP}"
check_provider "resource-audience-mapper"     "resource-aud-plugin-*.jar"         "${SERVERINFO_TMP}"
rm -f "${SERVERINFO_TMP}"

if [ "${PROVIDER_CHECK_OK}" = "false" ]; then
  echo "  One or more provider JARs are missing (see warnings above)."
  echo "  Bootstrap will continue, but mappers that depend on these providers"
  echo "  will not be fully configured until the JARs are deployed and Keycloak"
  echo "  is rebuilt."
  echo ""
fi

# ---------------------------------------------------------------------------
# 1. Create realm
# ---------------------------------------------------------------------------

echo "==> Creating realm '${REALM}'..."
if "${KCADM}" get realms/"${REALM}" > /dev/null 2>&1; then
  echo "    Realm '${REALM}' already exists — skipping creation."
else
  "${KCADM}" create realms \
    -s "realm=${REALM}" \
    -s "enabled=true" \
    -s "displayName=${DISPLAY_NAME}" \
    -s "displayNameHtml=${DISPLAY_NAME}" \
    -s "registrationAllowed=false" \
    -s "loginWithEmailAllowed=false" \
    -s "resetPasswordAllowed=false"
  echo "    Realm '${REALM}' created."
fi

# ACR-to-LoA mappings — stored as realm attributes.
# kcadm does not accept a JSON object as an inline -s value, so we pipe the
# payload as a JSON file instead.
echo "==> Configuring ACR-to-LoA mappings..."
"${KCADM}" update realms/"${REALM}" -f - <<'EOF'
{
  "attributes": {
    "acr.loa.map": "{\"0\":\"http://id.elegnamnden.se/loa/1.0/loa2\",\"1\":\"http://id.swedenconnect.se/loa/1.0/uncertified-loa3\",\"2\":\"http://id.elegnamnden.se/loa/1.0/loa3\",\"3\":\"http://id.elegnamnden.se/loa/1.0/loa4\"}"
  }
}
EOF

# Enable Fine-Grained Admin Permissions — required for the service account
# to manage Authorization Services artifacts (policies, permissions, scopes)
# via the Admin REST API using a realm-level token.
echo "==> Enabling Fine-Grained Admin Permissions..."
"${KCADM}" update realms/"${REALM}" -s "adminPermissionsEnabled=true"
echo "    Done."

# ---------------------------------------------------------------------------
# 2. Top-level groups
# ---------------------------------------------------------------------------

echo "==> Creating top-level groups..."

for GROUP in orgs functions; do
  EXISTING=$("${KCADM}" get groups -r "${REALM}" --fields name 2>/dev/null \
    | grep "\"name\" : \"${GROUP}\"" || true)
  if [ -n "${EXISTING}" ]; then
    echo "    Group '${GROUP}' already exists — skipping."
  else
    "${KCADM}" create groups -r "${REALM}" -s "name=${GROUP}"
    echo "    Group '${GROUP}' created."
  fi
done

# ---------------------------------------------------------------------------
# 3. Realm role: superuser
# ---------------------------------------------------------------------------

echo "==> Creating realm role 'superuser'..."
EXISTING_ROLE=$("${KCADM}" get roles -r "${REALM}" --fields name 2>/dev/null \
  | grep '"name" : "superuser"' || true)
if [ -n "${EXISTING_ROLE}" ]; then
  echo "    Role 'superuser' already exists — skipping."
else
  "${KCADM}" create roles -r "${REALM}" \
    -s "name=superuser" \
    -s "description=Full access to all organizations, functions and users."
  echo "    Role 'superuser' created."
fi

# ---------------------------------------------------------------------------
# 4. naturalPersonNumber client scope
# ---------------------------------------------------------------------------

PNR_SCOPE="https://id.oidc.se/scope/naturalPersonNumber"

echo "==> Creating client scope '${PNR_SCOPE}'..."
EXISTING_SCOPE=$("${KCADM}" get client-scopes -r "${REALM}" --fields name 2>/dev/null \
  | grep "\"name\" : \"${PNR_SCOPE}\"" || true)

if [ -n "${EXISTING_SCOPE}" ]; then
  echo "    Client scope already exists — skipping creation."
else
  "${KCADM}" create client-scopes -r "${REALM}" \
    -s "name=${PNR_SCOPE}" \
    -s "protocol=openid-connect" \
    -s 'attributes."include.in.token.scope"=true' \
    -s 'attributes."display.on.consent.screen"=true'
  echo "    Client scope created."
fi

# Add the swedish-oidc-claims-mapper to the scope.
# First resolve the scope ID.
SCOPE_ID=$("${KCADM}" get client-scopes -r "${REALM}" --fields id,name 2>/dev/null \
  | grep -B1 "\"name\" : \"${PNR_SCOPE}\"" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/')

if [ -z "${SCOPE_ID}" ]; then
  echo "ERROR: Could not resolve ID for client scope '${PNR_SCOPE}'." >&2
  exit 1
fi

echo "==> Adding oidc-sweden-claims-mapper to scope '${PNR_SCOPE}'..."
EXISTING_MAPPER=$("${KCADM}" get client-scopes/"${SCOPE_ID}"/protocol-mappers/models \
  -r "${REALM}" --fields name 2>/dev/null \
  | grep '"name" : "oidc-sweden-claims-mapper"' || true)

if [ -n "${EXISTING_MAPPER}" ]; then
  echo "    Mapper already exists — skipping."
else
  # Attempt to create the mapper. If the OIDC Sweden provider type is not deployed,
  # kcadm.sh will return an error — we catch this and warn rather than fail.
  if ! "${KCADM}" create client-scopes/"${SCOPE_ID}"/protocol-mappers/models \
    -r "${REALM}" \
    -s "name=oidc-sweden-claims-mapper" \
    -s "protocol=openid-connect" \
    -s "protocolMapper=oidc-sweden-claims-mapper" \
    -s "consentRequired=false" \
    -s 'config."id.token.claim"=true' \
    -s 'config."access.token.claim"=true' \
    -s 'config."userinfo.token.claim"=true' 2>/dev/null; then
    echo ""
    echo "WARNING: Could not add 'oidc-sweden-claims-mapper' to scope '${PNR_SCOPE}'."
    echo "         The 'oidc-sweden-claims-mapper' provider type was not found."
    echo "         Ensure the oidc-sweden-claims-plugin JAR is deployed in Keycloak's"
    echo "         providers directory and that Keycloak has been rebuilt (kc.sh build)."
    echo ""
  else
    echo "    Mapper added."
  fi
fi

# ---------------------------------------------------------------------------
# 5. phone client scope (built-in — create only if missing)
# ---------------------------------------------------------------------------

echo "==> Checking phone client scope..."
EXISTING_PHONE=$("${KCADM}" get client-scopes -r "${REALM}" --fields name 2>/dev/null \
  | grep '"name" : "phone"' || true)

if [ -n "${EXISTING_PHONE}" ]; then
  echo "    Built-in 'phone' scope already exists — skipping."
else
  echo "    Built-in 'phone' scope not found — creating..."
  "${KCADM}" create client-scopes -r "${REALM}" \
    -s "name=phone" \
    -s "protocol=openid-connect" \
    -s 'attributes."include.in.token.scope"=true' \
    -s 'attributes."display.on.consent.screen"=false'

  PHONE_SCOPE_ID=$("${KCADM}" get client-scopes -r "${REALM}" --fields id,name 2>/dev/null \
    | grep -B1 '"name" : "phone"' \
    | grep '"id"' \
    | sed 's/.*: "\(.*\)".*/\1/')

  "${KCADM}" create client-scopes/"${PHONE_SCOPE_ID}"/protocol-mappers/models \
    -r "${REALM}" \
    -s "name=phone number" \
    -s "protocol=openid-connect" \
    -s "protocolMapper=oidc-usermodel-attribute-mapper" \
    -s "consentRequired=false" \
    -s 'config."user.attribute"=phoneNumber' \
    -s 'config."claim.name"=phone_number' \
    -s 'config."jsonType.label"=String' \
    -s 'config."id.token.claim"=true' \
    -s 'config."access.token.claim"=true' \
    -s 'config."userinfo.token.claim"=true'
  echo "    'phone' scope created with phone_number mapper."
fi

# ---------------------------------------------------------------------------
# 6. personalIdentityNumber user profile attribute
# ---------------------------------------------------------------------------

echo "==> Configuring personalIdentityNumber user profile attribute..."

# The user profile is managed via the /users/profile endpoint.
# We fetch the current profile, check if the attribute already exists,
# and add it if not.
PROFILE_JSON=$("${KCADM}" get users/profile -r "${REALM}" 2>/dev/null || echo "{}")

EXISTING_ATTR=$(echo "${PROFILE_JSON}" | grep '"personalIdentityNumber"' || true)

if [ -n "${EXISTING_ATTR}" ]; then
  echo "    Attribute 'personalIdentityNumber' already exists — skipping."
else
  # Fetch the current profile into a temp file, inject our attribute before the
  # closing ] of the attributes array, and PUT the result back.
  # We use sed instead of python3/jq since neither is available in the Keycloak image.
  PROFILE_TMP=$(mktemp)
  UPDATED_TMP=$(mktemp)
  trap 'rm -f "${PROFILE_TMP}" "${UPDATED_TMP}"' EXIT

  "${KCADM}" get users/profile -r "${REALM}" > "${PROFILE_TMP}"

  # The new attribute JSON to inject — inserted before the closing ] of the
  # attributes array. A leading comma separates it from the preceding entry.
  NEW_ATTR='  , {"name":"personalIdentityNumber","displayName":"Personal Identity Number","validations":{},"annotations":{},"permissions":{"view":["admin","user"],"edit":["admin"]},"multivalued":false,"selector":{"scopes":["https://id.oidc.se/scope/naturalPersonNumber"]}}'

  # Insert before the line that contains exactly "  ]" — the closing bracket
  # of the top-level attributes array in kcadm's pretty-printed JSON output.
  sed "/^  \]$/i\\${NEW_ATTR}" "${PROFILE_TMP}" > "${UPDATED_TMP}"

  "${KCADM}" update users/profile -r "${REALM}" -f "${UPDATED_TMP}"
  echo "    Attribute 'personalIdentityNumber' added."
fi

# ---------------------------------------------------------------------------
# 7. Client Policy: resource-function-executor
# ---------------------------------------------------------------------------
#
# Creates a Client Policy profile containing the resource-function-executor,
# and a Client Policy that applies the profile to all confidential clients.
# This validates the OAuth2 resource parameter against the target client's
# client_functions attribute at token issuance time.
#
# NOTE: The PUT to client-policies/profiles and client-policies/policies
# replaces the ENTIRE list of user-defined profiles/policies. In a fresh
# orgiam realm this is fine. If the realm later has other custom profiles or
# policies, this logic must be changed to fetch-merge-put.

echo "==> Configuring resource-function-executor Client Policy..."

EXISTING_PROFILES=$("${KCADM}" get client-policies/profiles -r "${REALM}" 2>/dev/null || echo '{"profiles":[]}')
EXISTING_POLICIES=$("${KCADM}" get client-policies/policies -r "${REALM}" 2>/dev/null || echo '{"policies":[]}')

PROFILE_EXISTS=$(echo "${EXISTING_PROFILES}" | grep '"resource-function-profile"' || true)

if [ -n "${PROFILE_EXISTS}" ]; then
  echo "    Client Policy profile 'resource-function-profile' already exists — skipping."
else
  PROFILES_TMP=$(mktemp)
  cat > "${PROFILES_TMP}" <<'PROFILES_EOF'
{
  "profiles": [
    {
      "name": "resource-function-profile",
      "description": "Validates the resource parameter against the target client's client_functions attribute.",
      "executors": [
        {
          "executor": "resource-function-executor",
          "configuration": {}
        }
      ]
    }
  ]
}
PROFILES_EOF

  if ! "${KCADM}" update client-policies/profiles -r "${REALM}" \
    -f "${PROFILES_TMP}" 2>/dev/null; then
    echo ""
    echo "WARNING: Could not create Client Policy profile 'resource-function-profile'."
    echo "         The 'resource-function-executor' provider type may not be deployed."
    echo "         Ensure the resource-aud-plugin JAR is deployed in Keycloak's"
    echo "         providers directory and that Keycloak has been rebuilt (kc.sh build)."
    echo ""
  else
    echo "    Client Policy profile 'resource-function-profile' created."
  fi
  rm -f "${PROFILES_TMP}"
fi

POLICY_EXISTS=$(echo "${EXISTING_POLICIES}" | grep '"resource-function-policy"' || true)

if [ -n "${POLICY_EXISTS}" ]; then
  echo "    Client Policy 'resource-function-policy' already exists — skipping."
else
  POLICIES_TMP=$(mktemp)
  cat > "${POLICIES_TMP}" <<'POLICIES_EOF'
{
  "policies": [
    {
      "name": "resource-function-policy",
      "description": "Applies resource parameter validation to all confidential clients.",
      "enabled": true,
      "conditions": [
        {
          "condition": "client-access-type",
          "configuration": {
            "type": ["confidential"]
          }
        }
      ],
      "profiles": ["resource-function-profile"]
    }
  ]
}
POLICIES_EOF

  if ! "${KCADM}" update client-policies/policies -r "${REALM}" \
    -f "${POLICIES_TMP}" 2>/dev/null; then
    echo ""
    echo "WARNING: Could not create Client Policy 'resource-function-policy'."
    echo "         The profile may not have been created successfully."
    echo ""
  else
    echo "    Client Policy 'resource-function-policy' created."
  fi
  rm -f "${POLICIES_TMP}"
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> Bootstrap of realm '${REALM}' complete."
echo ""
echo "    Next steps:"
echo "    - Create the initial superuser account (see create-admin-user.sh)"
echo "    - Register OAuth/OIDC clients"
echo "    - Attach functions to organizations as needed"
