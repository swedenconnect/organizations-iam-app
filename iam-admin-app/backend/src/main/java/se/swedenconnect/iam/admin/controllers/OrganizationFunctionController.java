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

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.audit.AuditData;
import se.swedenconnect.iam.admin.audit.AuditOperations;
import se.swedenconnect.iam.admin.audit.IamAuditEvent;
import se.swedenconnect.iam.admin.audit.IamErrorAuditEvent;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.RightHolder;
import se.swedenconnect.iam.admin.service.OrganizationService;

import java.util.List;

/**
 * REST controller for attaching functions to organizations.
 *
 * <p>Only superusers may perform this operation. Non-superusers receive HTTP 403.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class OrganizationFunctionController {

  private final KeycloakAdminClient keycloakAdminClient;

  private final OrganizationService organizationService;

  private final ApplicationEventPublisher eventPublisher;

  /**
   * Attaches a function to an organization.
   *
   * @param orgIdentifier the organization identifier (10-digit org number)
   * @param functionId    the function identifier (group name under {@code /functions})
   * @param request       the HTTP servlet request
   * @return 204 No Content on success; 403 if not superuser; 404 if org or function not found;
   *         409 if function already attached; 500 on Keycloak API error
   */
  @PostMapping("/organizations/{orgIdentifier}/functions/{functionId}")
  public ResponseEntity<?> attachFunction(
      @PathVariable final String orgIdentifier,
      @PathVariable final String functionId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("POST /api/organizations/{}/functions/{} — rejected: caller is not a superuser",
          orgIdentifier, functionId);
      return this.denied(AuditOperations.ATTACH_FUNCTION, data, orgIdentifier, functionId);
    }

    if (!this.keycloakAdminClient.organizationExists(orgIdentifier)) {
      log.info("POST /api/organizations/{}/functions/{} — rejected: organization not found",
          orgIdentifier, functionId);
      return ResponseEntity.status(404).body("Organization not found: " + orgIdentifier);
    }

    if (!this.keycloakAdminClient.functionExists(functionId)) {
      log.info("POST /api/organizations/{}/functions/{} — rejected: function not found",
          orgIdentifier, functionId);
      return ResponseEntity.status(404).body("Function not found: " + functionId);
    }

    if (this.keycloakAdminClient.isFunctionAttachedToOrg(orgIdentifier, functionId)) {
      log.info("POST /api/organizations/{}/functions/{} — rejected: function already attached",
          orgIdentifier, functionId);
      return ResponseEntity.status(409).body("Function already attached to organization");
    }

    try {
      this.keycloakAdminClient.attachFunctionToOrg(orgIdentifier, functionId);
    }
    catch (final KeycloakAdminException e) {
      log.error("POST /api/organizations/{}/functions/{} — Keycloak error: {}",
          orgIdentifier, functionId, e.getMessage(), e);
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.ATTACH_FUNCTION, null),
          AuditData.Target.none().organization(orgIdentifier).function(functionId), e));
      return ResponseEntity.status(500).body(e.getMessage());
    }
    this.eventPublisher.publishEvent(IamAuditEvent.functionAttached(orgIdentifier, functionId, null));

    // The attached function list lives on the cached organization, so the entry has to be re-read.
    this.organizationService.refresh(orgIdentifier);

    log.info("POST /api/organizations/{}/functions/{} — function attached successfully",
        orgIdentifier, functionId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Detaches a function from an organization.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @param request       the HTTP servlet request
   * @return 204 No Content on success; 403 if not superuser; 404 if org or function not
   *         attached; 500 on Keycloak API error
   */
  @DeleteMapping("/organizations/{orgIdentifier}/functions/{functionId}")
  public ResponseEntity<?> detachFunction(
      @PathVariable final String orgIdentifier,
      @PathVariable final String functionId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("DELETE /api/organizations/{}/functions/{} — rejected: caller is not a superuser",
          orgIdentifier, functionId);
      return this.denied(AuditOperations.DETACH_FUNCTION, data, orgIdentifier, functionId);
    }

    if (!this.keycloakAdminClient.isFunctionAttachedToOrg(orgIdentifier, functionId)) {
      log.info("DELETE /api/organizations/{}/functions/{} — not attached",
          orgIdentifier, functionId);
      return ResponseEntity.notFound().build();
    }

    // The rights disappear with the function's groups, so they are read before the detach.
    final List<RightHolder> removedRights;
    try {
      removedRights = this.keycloakAdminClient.fetchRightHolders(orgIdentifier, functionId);
      this.keycloakAdminClient.detachFunctionFromOrg(orgIdentifier, functionId);
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/organizations/{}/functions/{} — Keycloak error: {}",
          orgIdentifier, functionId, e.getMessage(), e);
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.DETACH_FUNCTION, null),
          AuditData.Target.none().organization(orgIdentifier).function(functionId), e));
      return ResponseEntity.status(500).body(e.getMessage());
    }
    this.eventPublisher.publishEvent(IamAuditEvent.functionDetached(orgIdentifier, functionId, removedRights));

    // The attached function list lives on the cached organization, so the entry has to be re-read.
    this.organizationService.refresh(orgIdentifier);

    log.info("DELETE /api/organizations/{}/functions/{} — function detached successfully",
        orgIdentifier, functionId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Records an {@code access_denied} audit event and returns the 403 response.
   *
   * @param operation     the operation refused
   * @param data          the caller's session data, or {@code null}
   * @param orgIdentifier the organization
   * @param functionId    the function
   * @return a 403 response
   */
  private ResponseEntity<?> denied(final @NonNull String operation, final @Nullable AdminSessionData data,
      final @NonNull String orgIdentifier, final @NonNull String functionId) {
    final String reason = data == null ? AuditOperations.NO_ADMIN_SESSION : AuditOperations.NOT_SUPERUSER;
    this.eventPublisher.publishEvent(IamAuditEvent.accessDenied(new AuditData.Operation(operation, reason),
        AuditData.Target.none().organization(orgIdentifier).function(functionId)));
    return ResponseEntity.status(403).build();
  }

}
