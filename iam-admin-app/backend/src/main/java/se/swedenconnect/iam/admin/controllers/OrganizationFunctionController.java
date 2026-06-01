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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;

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
      return ResponseEntity.status(403).build();
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
      return ResponseEntity.status(500).body(e.getMessage());
    }

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
      return ResponseEntity.status(403).build();
    }

    if (!this.keycloakAdminClient.isFunctionAttachedToOrg(orgIdentifier, functionId)) {
      log.info("DELETE /api/organizations/{}/functions/{} — not attached",
          orgIdentifier, functionId);
      return ResponseEntity.notFound().build();
    }

    try {
      this.keycloakAdminClient.detachFunctionFromOrg(orgIdentifier, functionId);
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/organizations/{}/functions/{} — Keycloak error: {}",
          orgIdentifier, functionId, e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }

    log.info("DELETE /api/organizations/{}/functions/{} — function detached successfully",
        orgIdentifier, functionId);
    return ResponseEntity.noContent().build();
  }

}
