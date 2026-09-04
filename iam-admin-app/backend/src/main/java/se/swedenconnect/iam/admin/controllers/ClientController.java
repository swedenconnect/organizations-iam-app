/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.admin.controllers;

import com.nimbusds.jose.jwk.JWKSet;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.controllers.dto.CreateManagedClientRequest;
import se.swedenconnect.iam.admin.controllers.dto.ManagedClientResponse;
import se.swedenconnect.iam.admin.controllers.dto.ReconciliationReportResponse;
import se.swedenconnect.iam.admin.controllers.dto.UpdateManagedClientRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.service.ClientReconciliationService;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST controller for managed client administration.
 *
 * <p>All operations are restricted to superusers. A managed client is realm-wide — it is not owned
 * by any organization — so there is no organization-scoped view of it.</p>
 *
 * <p>A client plays one or both of two roles — OIDC client and resource server — set with the
 * {@code oidcClient} and {@code resourceServer} flags on the create and update requests. Redirect
 * URIs and client keys apply to the OIDC client role only, and are rejected without it.</p>
 *
 * <p>Per-client endpoints address a client by its Keycloak UUID, not by its client_id. A client_id
 * is a URL, and a URL-encoded one in a path segment is rejected with 400 by Spring Security's
 * strict HTTP firewall before the request reaches this controller.</p>
 *
 * @author Felix Hellman
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ClientController {

  /** The reason returned when a client holding a service account is put up for deletion. */
  private static final String SERVICE_ACCOUNT_CLIENT_PROTECTED =
      "A client holding a service account cannot be deleted from the application";

  private final KeycloakAdminClient keycloakAdminClient;
  private final ClientReconciliationService reconciliationService;

  /**
   * Lists everything the application administers: managed clients and resource servers. Each entry
   * carries a {@code type} discriminating the two.
   *
   * @param request the HTTP servlet request
   * @return 200 with the managed clients; 403 if not superuser; 500 on Keycloak error
   */
  @GetMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> getClients(final HttpServletRequest request) {
    if (!isSuperuser(request)) {
      log.info("GET /api/clients — rejected: caller is not a superuser");
      return ResponseEntity.status(403).build();
    }

    try {
      return ResponseEntity.ok(this.keycloakAdminClient.resolveAdministeredClients().stream()
          .map(ClientController::toResponse)
          .toList());
    }
    catch (final KeycloakAdminException e) {
      log.error("GET /api/clients — Keycloak error: {}", e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Returns a single managed client.
   *
   * @param id the Keycloak UUID of the client
   * @param request the HTTP servlet request
   * @return 200 with the client; 403 if not superuser; 404 if not a managed client;
   *     500 on Keycloak error
   */
  @GetMapping(value = "/clients/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> getClient(
      @PathVariable final String id,
      final HttpServletRequest request) {

    if (!isSuperuser(request)) {
      log.info("GET /api/clients/{} — rejected: caller is not a superuser", id);
      return ResponseEntity.status(403).build();
    }

    try {
      return this.keycloakAdminClient.findManagedClientByUuid(id)
          .map(client -> ResponseEntity.ok((Object) toResponse(client)))
          .orElseGet(() -> ResponseEntity.notFound().build());
    }
    catch (final KeycloakAdminException e) {
      log.error("GET /api/clients/{} — Keycloak error: {}", id, e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Registers a managed client and reconciles it, so that it immediately holds the scopes,
   * policies and permissions for the organizations its functions are attached to.
   *
   * @param req the request body
   * @param request the HTTP servlet request
   * @return 201 with the created client; 400 on invalid input; 403 if not superuser;
   *     409 if the client_id is taken; 500 on Keycloak error
   */
  @PostMapping(value = "/clients",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createClient(
      @RequestBody final CreateManagedClientRequest req,
      final HttpServletRequest request) {

    if (!isSuperuser(request)) {
      log.info("POST /api/clients — rejected: caller is not a superuser");
      return ResponseEntity.status(403).build();
    }

    final String clientIdError = validateClientId(req.clientId());
    if (clientIdError != null) {
      log.info("POST /api/clients — rejected: {}", clientIdError);
      return ResponseEntity.badRequest().body(clientIdError);
    }

    final boolean oidcClient = !Boolean.FALSE.equals(req.oidcClient());
    final boolean resourceServer = Boolean.TRUE.equals(req.resourceServer());
    final String error = this.validate(oidcClient, resourceServer,
        req.redirectUris(), req.functions(), req.jwksUri(), req.jwksString());
    if (error != null) {
      log.info("POST /api/clients — rejected: {}", error);
      return ResponseEntity.badRequest().body(error);
    }

    try {
      if (this.keycloakAdminClient.clientExists(req.clientId())) {
        log.info("POST /api/clients — rejected: client '{}' already exists", req.clientId());
        return ResponseEntity.status(409).build();
      }

      final ManagedClientInfo created = this.keycloakAdminClient.createManagedClient(
          req.clientId(), req.name(), oidcClient, resourceServer,
          req.redirectUris() == null ? List.of() : req.redirectUris(),
          req.functions() == null ? Set.of() : req.functions(),
          req.jwksUri(), req.jwksString(),
          // A client registered here never keeps a service account — only the scripts create one
          false,
          !Boolean.FALSE.equals(req.orgRightsIdToken()),
          !Boolean.FALSE.equals(req.orgRightsAccessToken()));

      if (oidcClient) {
        this.reconciliationService.reconcileClient(req.clientId());
      }

      log.info("POST /api/clients — client '{}' created and reconciled", req.clientId());
      return ResponseEntity.status(201).body(toResponse(created));
    }
    catch (final KeycloakAdminException e) {
      log.error("POST /api/clients — Keycloak error: {}", e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Updates a managed client and reconciles it, so that artifacts for newly added functions are
   * created immediately.
   *
   * @param id the Keycloak UUID of the client
   * @param req the request body
   * @param request the HTTP servlet request
   * @return 200 with the updated client; 400 on invalid input; 403 if not superuser;
   *     404 if not a managed client; 500 on Keycloak error
   */
  @PutMapping(value = "/clients/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> updateClient(
      @PathVariable final String id,
      @RequestBody final UpdateManagedClientRequest req,
      final HttpServletRequest request) {

    if (!isSuperuser(request)) {
      log.info("PUT /api/clients/{} — rejected: caller is not a superuser", id);
      return ResponseEntity.status(403).build();
    }

    final boolean oidcClient = !Boolean.FALSE.equals(req.oidcClient());
    final boolean resourceServer = Boolean.TRUE.equals(req.resourceServer());
    final String error = this.validate(oidcClient, resourceServer,
        req.redirectUris(), req.functions(), req.jwksUri(), req.jwksString());
    if (error != null) {
      log.info("PUT /api/clients/{} — rejected: {}", id, error);
      return ResponseEntity.badRequest().body(error);
    }

    try {
      final ManagedClientInfo existing = this.keycloakAdminClient.findManagedClientByUuid(id).orElse(null);
      if (existing == null) {
        log.info("PUT /api/clients/{} — not found", id);
        return ResponseEntity.notFound().build();
      }

      final ManagedClientInfo updated = this.keycloakAdminClient.updateManagedClient(
          existing.clientId(), req.name(), oidcClient, resourceServer,
          req.redirectUris() == null ? List.of() : req.redirectUris(),
          req.functions() == null ? Set.of() : req.functions(),
          req.jwksUri(), req.jwksString(),
          // The service account is left exactly as it is — the application never creates or
          // removes one. An omitted org_rights switch keeps what the client has, so a caller that
          // sends only the fields it cares about does not silently reset the others
          existing.serviceAccount(),
          req.orgRightsIdToken() == null ? existing.orgRightsIdToken() : req.orgRightsIdToken(),
          req.orgRightsAccessToken() == null
              ? existing.orgRightsAccessToken() : req.orgRightsAccessToken());

      if (oidcClient) {
        this.reconciliationService.reconcileClient(existing.clientId());
      }

      log.info("PUT /api/clients/{} — client '{}' updated and reconciled", id, existing.clientId());
      return ResponseEntity.ok(toResponse(updated));
    }
    catch (final KeycloakAdminException e) {
      log.error("PUT /api/clients/{} — Keycloak error: {}", id, e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Permanently deletes a managed client from Keycloak.
   *
   * <p>The realm-level client scopes are shared between clients and are not deleted.</p>
   *
   * <p>A client holding a service account is refused: it was registered by script, it is what
   * gives the application — or another operator tool — its Keycloak Admin API access, and
   * deleting it from here would take that access away with no way to restore it in the GUI.</p>
   *
   * @param id the Keycloak UUID of the client
   * @param request the HTTP servlet request
   * @return 204 on success; 403 if not superuser; 404 if not a managed client; 409 if the client
   *     holds a service account; 500 on Keycloak error
   */
  @DeleteMapping(value = "/clients/{id}")
  public ResponseEntity<?> deleteClient(
      @PathVariable final String id,
      final HttpServletRequest request) {

    if (!isSuperuser(request)) {
      log.info("DELETE /api/clients/{} — rejected: caller is not a superuser", id);
      return ResponseEntity.status(403).build();
    }

    final String clientId;
    try {
      final ManagedClientInfo existing = this.keycloakAdminClient.findManagedClientByUuid(id).orElse(null);
      if (existing == null) {
        log.info("DELETE /api/clients/{} — not found", id);
        return ResponseEntity.notFound().build();
      }
      if (existing.serviceAccount()) {
        log.info("DELETE /api/clients/{} — rejected: client '{}' holds a service account",
            id, existing.clientId());
        return ResponseEntity.status(409).body(SERVICE_ACCOUNT_CLIENT_PROTECTED);
      }
      clientId = existing.clientId();
      this.keycloakAdminClient.deleteManagedClient(clientId);
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/clients/{} — Keycloak error: {}", id, e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }

    log.info("DELETE /api/clients/{} — client '{}' deleted successfully", id, clientId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Reconciles a single managed client against the current org/function topology.
   *
   * @param id the Keycloak UUID of the client
   * @param request the HTTP servlet request
   * @return 200 with the reconciliation report; 403 if not superuser; 404 if not a managed client;
   *     500 on Keycloak error
   */
  @PostMapping(value = "/clients/{id}/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> reconcileClient(
      @PathVariable final String id,
      final HttpServletRequest request) {

    if (!isSuperuser(request)) {
      log.info("POST /api/clients/{}/reconcile — rejected: caller is not a superuser", id);
      return ResponseEntity.status(403).build();
    }

    try {
      final ManagedClientInfo client = this.keycloakAdminClient.findManagedClientByUuid(id).orElse(null);
      if (client == null) {
        log.info("POST /api/clients/{}/reconcile — not found", id);
        return ResponseEntity.notFound().build();
      }
      if (!client.reconcilable()) {
        log.info("POST /api/clients/{}/reconcile — rejected: '{}' is a resource server only",
            id, client.clientId());
        return ResponseEntity.badRequest().body(
            "a resource server holds no scopes, policies or permissions and is not reconciled");
      }
      return ResponseEntity.ok(
          toResponse(this.reconciliationService.reconcileClient(client.clientId())));
    }
    catch (final KeycloakAdminException e) {
      log.error("POST /api/clients/{}/reconcile — Keycloak error: {}", id, e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Reconciles every managed client against the current org/function topology.
   *
   * @param request the HTTP servlet request
   * @return 200 with the reconciliation report; 403 if not superuser; 500 on Keycloak error
   */
  @PostMapping(value = "/clients/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> reconcileAll(final HttpServletRequest request) {

    if (!isSuperuser(request)) {
      log.info("POST /api/clients/reconcile — rejected: caller is not a superuser");
      return ResponseEntity.status(403).build();
    }

    try {
      return ResponseEntity.ok(toResponse(this.reconciliationService.reconcileAll()));
    }
    catch (final KeycloakAdminException e) {
      log.error("POST /api/clients/reconcile — Keycloak error: {}", e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Validates the parts of a create or update request that both share.
   *
   * @param redirectUris the redirect URIs
   * @param functions the functions the client handles
   * @param jwksUri the JWKS URI, or {@code null}
   * @param jwksString the inline JWK Set, or {@code null}
   * @return an error message, or {@code null} if the request is valid
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @Nullable String validate(
      final boolean oidcClient,
      final boolean resourceServer,
      final @Nullable List<String> redirectUris,
      final @Nullable Set<String> functions,
      final @Nullable String jwksUri,
      final @Nullable String jwksString) {

    if (!oidcClient && !resourceServer) {
      return "a client must be an OIDC client, a resource server, or both";
    }

    final String functionError = this.validateFunctions(functions);
    if (functionError != null) {
      return functionError;
    }

    if (!oidcClient) {
      // Redirect URIs and client keys belong to the OIDC client role only
      return null;
    }

    if (redirectUris == null || redirectUris.isEmpty()) {
      return "at least one redirectUri is required";
    }
    for (final String uri : redirectUris) {
      if (uri.isBlank()) {
        return "redirectUris must not contain blank entries";
      }
      if (uri.contains("*")) {
        return "wildcards are not allowed in redirectUris — enter the exact redirect URI";
      }
      if (!isAbsoluteUri(uri)) {
        return "redirectUri is not an absolute URI: " + uri;
      }
    }

    if ((jwksUri == null) == (jwksString == null)) {
      return "exactly one of jwksUri and jwksString is required";
    }
    if (jwksUri != null) {
      if (!isAbsoluteUri(jwksUri) || !jwksUri.startsWith("https://")) {
        return "jwksUri must be an absolute https URI";
      }
    }
    else {
      try {
        JWKSet.parse(jwksString);
      }
      catch (final ParseException e) {
        return "jwksString is not a valid JWK Set: " + e.getMessage();
      }
    }
    return null;
  }

  /**
   * Validates the declared functions. The list may be empty — a client with no functions receives
   * no artifacts — but every declared function must exist.
   *
   * @param functions the functions
   * @return an error message, or {@code null} if valid
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @Nullable String validateFunctions(final @Nullable Set<String> functions) {
    if (functions == null || functions.isEmpty()) {
      // A client may be registered before it is known which functions it handles. It simply holds
      // no scopes, policies or permissions until functions are assigned to it.
      return null;
    }
    final Set<String> known = this.keycloakAdminClient.fetchAllFunctions().stream()
        .map(FunctionInfo::id)
        .collect(Collectors.toSet());
    for (final String function : functions) {
      if (!known.contains(function)) {
        return "unknown function: " + function;
      }
    }
    return null;
  }

  /**
   * Validates a client_id.
   *
   * @param clientId the client_id
   * @return an error message, or {@code null} if valid
   */
  private static @Nullable String validateClientId(final @NonNull String clientId) {
    return clientId.isBlank() || clientId.chars().anyMatch(Character::isWhitespace)
        ? "clientId must not be blank or contain whitespace" : null;
  }

  /**
   * Tells whether the string is an absolute URI.
   *
   * @param value the value to check
   * @return {@code true} if the value parses as an absolute URI
   */
  private static boolean isAbsoluteUri(final @NonNull String value) {
    try {
      return new URI(value).isAbsolute();
    }
    catch (final URISyntaxException e) {
      return false;
    }
  }

  /**
   * Tells whether the session belongs to an authenticated superuser.
   *
   * @param request the HTTP servlet request
   * @return {@code true} if the caller is a superuser
   */
  private static boolean isSuperuser(final @NonNull HttpServletRequest request) {
    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    return data != null && data.currentUserIsSuperuser();
  }

  /**
   * Converts a managed client to its API representation.
   *
   * @param client the managed client
   * @return the response body
   */
  private static @NonNull ManagedClientResponse toResponse(final @NonNull ManagedClientInfo client) {
    return new ManagedClientResponse(
        client.uuid(), client.oidcClient(), client.resourceServer(), client.clientId(),
        client.name(), client.functions(), client.redirectUris(),
        client.jwksUri(), client.jwksString(), client.serviceAccount(),
        client.orgRightsIdToken(), client.orgRightsAccessToken(), client.enabled());
  }

  /**
   * Converts a reconciliation report to its API representation.
   *
   * @param report the report
   * @return the response body
   */
  private static @NonNull ReconciliationReportResponse toResponse(final @NonNull ReconciliationReport report) {
    return new ReconciliationReportResponse(
        report.clients(), report.created(), report.removed(), report.errors());
  }
}
