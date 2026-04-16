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
# --redirect-uri may be specified multiple times to register more than one redirect
# URI pattern on the client. Multiple patterns are only needed when the application
# combines OIDC login and OAuth2 client flows that use different callback base paths.
# For a plain OIDC relying party a single pattern (e.g. /login/oauth2/code/*) is
# sufficient.
REDIRECT_URIS=()
JWKS_URL=""
SERVICE_ACCOUNT="false"
ORG_RIGHTS_ID_TOKEN="true"
ORG_RIGHTS_ACCESS_TOKEN="true"

KCADM="/opt/keycloak/bin/kcadm.sh"
SERVER_URL="http://keycloak:8080"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Register an OIDC relying-party client in Keycloak with private_key_jwt
authentication, Authorization Services, and protocol mappers.

Usage: $0 [OPTIONS]

Options:
  --realm <realm>                  Keycloak realm name
  --username <username>            Admin username
  --password <password>            Admin password
  --client-id <id>                 Client ID (e.g. https://my-app.example.com)
  --name <name>                    Display name shown in the Keycloak admin UI
  --redirect-uri <pattern>         Redirect URI pattern (repeatable)
  --jwks-url <url>                 JWKS endpoint URL (default: <client-id>/jwks)
  --service-account                Keep the service account and assign
                                   realm-management roles
  --no-org-rights-id-token         Exclude org_rights from the ID token
  --no-org-rights-access-token     Exclude org_rights from the access token
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
    --help|-h)                  usage; exit 0 ;;
    --realm)                    REALM="$2";         shift 2 ;;
    --username)                 USERNAME="$2";      shift 2 ;;
    --password)                 PASSWORD="$2";      shift 2 ;;
    --client-id)                CLIENT_ID="$2";     shift 2 ;;
    --name)                     NAME="$2";          shift 2 ;;
    --redirect-uri)             REDIRECT_URIS+=("$2"); shift 2 ;;
    --jwks-url)                 JWKS_URL="$2";      shift 2 ;;
    --service-account)          SERVICE_ACCOUNT="true"; shift ;;
    --no-org-rights-id-token)   ORG_RIGHTS_ID_TOKEN="false";    shift ;;
    --no-org-rights-access-token) ORG_RIGHTS_ACCESS_TOKEN="false"; shift ;;
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
# Authenticate
# ---------------------------------------------------------------------------

echo "==> Authenticating as '${USERNAME}'..."
"${KCADM}" config credentials \
  --server "${SERVER_URL}" \
  --realm master \
  --user "${USERNAME}" \
  --password "${PASSWORD}"

# ---------------------------------------------------------------------------
# Step 1 — Create the client (if missing)
# ---------------------------------------------------------------------------

echo "==> Resolving client '${CLIENT_ID}'..."
CLIENTS_TMP=$(mktemp)
( "${KCADM}" get clients -r "${REALM}" --fields id,clientId ) > "${CLIENTS_TMP}" 2>&1 || true
EXISTING_UUID=$(grep -B1 "\"clientId\" : \"${CLIENT_ID}\"" "${CLIENTS_TMP}" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/' || true)
rm -f "${CLIENTS_TMP}"

if [ -n "${EXISTING_UUID}" ]; then
  echo "    Client '${CLIENT_ID}' already exists (UUID: ${EXISTING_UUID})."
  CLIENT_UUID="${EXISTING_UUID}"
else
  echo "    Creating client..."
  CREATE_ARGS=(
    -s "clientId=${CLIENT_ID}"
    -s "protocol=openid-connect"
    -s "enabled=true"
    -s "clientAuthenticatorType=client-jwt"
    -s "standardFlowEnabled=true"
    -s "implicitFlowEnabled=false"
    -s "directAccessGrantsEnabled=false"
    -s "serviceAccountsEnabled=true"
    -s "publicClient=false"
  )
  CREATE_OUTPUT=$("${KCADM}" create clients -r "${REALM}" \
    "${CREATE_ARGS[@]}" 2>&1 || true)
  echo "${CREATE_OUTPUT}"
  CLIENT_UUID=$(echo "${CREATE_OUTPUT}" | sed "s/.*id '\(.*\)'.*/\1/" || true)
  if [ -z "${CLIENT_UUID}" ]; then
    echo "ERROR: failed to create client '${CLIENT_ID}'." >&2
    exit 1
  fi
  echo "    Client created (UUID: ${CLIENT_UUID})."
fi

# ---------------------------------------------------------------------------
# Step 1b — Sync invocation-driven fields (always)
#
# Runs unconditionally so that re-invocations with changed redirect URIs,
# root URL, or display name overwrite the stored values. Any redirect URIs
# added manually in the Keycloak UI will be replaced by the supplied set —
# this matches the documented "idempotent — safe to re-run" contract.
# ---------------------------------------------------------------------------

REDIRECT_URIS_JSON="["
for i in "${!REDIRECT_URIS[@]}"; do
  [ $i -gt 0 ] && REDIRECT_URIS_JSON+=","
  REDIRECT_URIS_JSON+="\"${REDIRECT_URIS[$i]}\""
done
REDIRECT_URIS_JSON+="]"

echo "==> Syncing rootUrl, redirectUris, attributes, name..."
UPDATE_ARGS=(
  -s "rootUrl=${CLIENT_ID}"
  -s "redirectUris=${REDIRECT_URIS_JSON}"
  -s 'attributes.iam_admin_managed=true'
)
if [ -n "${NAME}" ]; then
  UPDATE_ARGS+=(-s "name=${NAME}")
fi
"${KCADM}" update "clients/${CLIENT_UUID}" -r "${REALM}" "${UPDATE_ARGS[@]}"
echo "    Done."

# ---------------------------------------------------------------------------
# Step 2 — Configure private_key_jwt with JWKS URL
# ---------------------------------------------------------------------------

echo "==> Configuring private_key_jwt client authentication..."
echo "    JWKS URL: ${JWKS_URL}"
"${KCADM}" update "clients/${CLIENT_UUID}" \
  -r "${REALM}" \
  -s "clientAuthenticatorType=client-jwt" \
  -s 'attributes."use.jwks.url"=true' \
  -s "attributes.\"jwks.url\"=${JWKS_URL}"
echo "    Done."

# ---------------------------------------------------------------------------
# Step 3 — Enable Authorization Services
#
# Keycloak requires serviceAccountsEnabled=true to enable Authorization Services.
# Both flags are therefore always set together. If the service account is not
# wanted, its user account is deleted in Step 4b.
# ---------------------------------------------------------------------------

echo "==> Enabling Authorization Services..."
"${KCADM}" update "clients/${CLIENT_UUID}" \
  -r "${REALM}" \
  -s "authorizationServicesEnabled=true" \
  -s "serviceAccountsEnabled=true"
echo "    Done."

# ---------------------------------------------------------------------------
# Step 4 — Assign service account roles (conditional)
# ---------------------------------------------------------------------------

if [ "${SERVICE_ACCOUNT}" = "true" ]; then
  echo "==> Assigning realm-management roles to service account..."

  SA_TMP=$(mktemp)
  ( "${KCADM}" get "clients/${CLIENT_UUID}/service-account-user" \
    -r "${REALM}" --fields id ) > "${SA_TMP}" 2>&1 || true
  SA_USER_ID=$(grep '"id"' "${SA_TMP}" | sed 's/.*: "\(.*\)".*/\1/' || true)
  rm -f "${SA_TMP}"

  if [ -z "${SA_USER_ID}" ]; then
    echo "ERROR: Could not resolve service account user ID for client '${CLIENT_ID}'." >&2
    exit 1
  fi

  for ROLE in manage-users query-groups view-users query-users manage-realm view-clients manage-clients; do
    "${KCADM}" add-roles \
      -r "${REALM}" \
      --uid "${SA_USER_ID}" \
      --cclientid realm-management \
      --rolename "${ROLE}" 2>/dev/null && \
      echo "    Assigned: ${ROLE}" || \
      echo "    Role '${ROLE}' already assigned or not found — skipping."
  done
fi

# ---------------------------------------------------------------------------
# Step 4b — Delete service account user if not wanted
#
# If --service-account was not passed, delete the service account user that
# Keycloak created as a prerequisite for enabling Authorization Services.
# This prevents the account from being used while keeping
# authorizationServicesEnabled=true intact.
# ---------------------------------------------------------------------------

if [ "${SERVICE_ACCOUNT}" = "false" ]; then
  echo "==> Removing service account user (not requested)..."
  SA_TMP=$(mktemp)
  ( "${KCADM}" get "clients/${CLIENT_UUID}/service-account-user" \
    -r "${REALM}" --fields id ) > "${SA_TMP}" 2>&1 || true
  SA_USER_ID=$(grep '"id"' "${SA_TMP}" | sed 's/.*: "\(.*\)".*/\1/' || true)
  rm -f "${SA_TMP}"

  if [ -n "${SA_USER_ID}" ]; then
    "${KCADM}" delete "users/${SA_USER_ID}" -r "${REALM}"
    echo "    Service account user deleted."
  else
    echo "    Service account user not found — skipping."
  fi
fi

# ---------------------------------------------------------------------------
# Step 5 — Add protocol mappers
# ---------------------------------------------------------------------------

MAPPERS_TMP=$(mktemp)
( "${KCADM}" get "clients/${CLIENT_UUID}/protocol-mappers/models" \
  -r "${REALM}" --fields name ) > "${MAPPERS_TMP}" 2>&1 || true
EXISTING_MAPPER=$(grep '"name" : "org-rights-mapper"' "${MAPPERS_TMP}" || true)
EXISTING_OI_MAPPER=$(grep '"name" : "scope-org-identifier-mapper"' "${MAPPERS_TMP}" || true)
EXISTING_RA_MAPPER=$(grep '"name" : "resource-audience-mapper"' "${MAPPERS_TMP}" || true)
rm -f "${MAPPERS_TMP}"

echo "==> Adding org-rights protocol mapper..."
if [ -n "${EXISTING_MAPPER}" ]; then
  echo "    org-rights mapper already exists — skipping."
else
  "${KCADM}" create "clients/${CLIENT_UUID}/protocol-mappers/models" \
    -r "${REALM}" \
    -s "name=org-rights-mapper" \
    -s "protocol=openid-connect" \
    -s "protocolMapper=org-rights-mapper" \
    -s "consentRequired=false" \
    -s "config.\"id.token.claim\"=${ORG_RIGHTS_ID_TOKEN}" \
    -s "config.\"access.token.claim\"=${ORG_RIGHTS_ACCESS_TOKEN}"
  echo "    org-rights mapper added (ID token: ${ORG_RIGHTS_ID_TOKEN}, access token: ${ORG_RIGHTS_ACCESS_TOKEN})."
fi

echo "==> Adding scope-org-identifier-mapper..."
if [ -n "${EXISTING_OI_MAPPER}" ]; then
  echo "    scope-org-identifier-mapper already exists — skipping."
else
  "${KCADM}" create "clients/${CLIENT_UUID}/protocol-mappers/models" \
    -r "${REALM}" \
    -s "name=scope-org-identifier-mapper" \
    -s "protocol=openid-connect" \
    -s "protocolMapper=scope-org-identifier-mapper" \
    -s "consentRequired=false" \
    -s 'config."id.token.claim"=false' \
    -s 'config."access.token.claim"=true'
  echo "    scope-org-identifier-mapper added."
fi

echo "==> Adding resource-audience-mapper..."
if [ -n "${EXISTING_RA_MAPPER}" ]; then
  echo "    resource-audience-mapper already exists — skipping."
else
  if ! "${KCADM}" create "clients/${CLIENT_UUID}/protocol-mappers/models" \
    -r "${REALM}" \
    -s "name=resource-audience-mapper" \
    -s "protocol=openid-connect" \
    -s "protocolMapper=resource-audience-mapper" \
    -s "consentRequired=false" \
    -s 'config."id.token.claim"=false' \
    -s 'config."access.token.claim"=true' 2>/dev/null; then
    echo ""
    echo "WARNING: Could not add 'resource-audience-mapper'."
    echo "         The provider type may not be deployed. Ensure the"
    echo "         resource-aud-plugin JAR is in Keycloak's providers directory"
    echo "         and that Keycloak has been rebuilt (kc.sh build)."
    echo ""
  else
    echo "    resource-audience-mapper added."
  fi
fi

# ---------------------------------------------------------------------------
# Step 6 — Add optional client scopes
# ---------------------------------------------------------------------------

PNR_SCOPE="https://id.oidc.se/scope/naturalPersonNumber"

SCOPES_TMP=$(mktemp)
( "${KCADM}" get client-scopes -r "${REALM}" --fields id,name ) > "${SCOPES_TMP}" 2>&1 || true
PNR_SCOPE_ID=$(grep -B1 "\"name\" : \"${PNR_SCOPE}\"" "${SCOPES_TMP}" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/' || true)
PHONE_SCOPE_ID=$(grep -B1 '"name" : "phone"' "${SCOPES_TMP}" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/' || true)
rm -f "${SCOPES_TMP}"

echo "==> Adding '${PNR_SCOPE}' as optional client scope..."
if [ -z "${PNR_SCOPE_ID}" ]; then
  echo "    WARNING: Client scope '${PNR_SCOPE}' not found in realm '${REALM}'."
  echo "             Run bootstrap-realm.sh first to create the required realm-level scopes."
else
  "${KCADM}" update "clients/${CLIENT_UUID}/optional-client-scopes/${PNR_SCOPE_ID}" \
    -r "${REALM}" 2>/dev/null && \
    echo "    Added." || \
    echo "    Already present — skipping."
fi

echo "==> Adding 'phone' as optional client scope..."
if [ -z "${PHONE_SCOPE_ID}" ]; then
  echo "    WARNING: Client scope 'phone' not found in realm '${REALM}'."
  echo "             Run bootstrap-realm.sh first to create the required realm-level scopes."
else
  "${KCADM}" update "clients/${CLIENT_UUID}/optional-client-scopes/${PHONE_SCOPE_ID}" \
    -r "${REALM}" 2>/dev/null && \
    echo "    Added." || \
    echo "    Already present — skipping."
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> Client '${CLIENT_ID}' registered successfully."
echo ""
echo "    Summary:"
echo "      Client ID          : ${CLIENT_ID}"
if [ -n "${NAME}" ]; then
  echo "      Display name       : ${NAME}"
fi
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
