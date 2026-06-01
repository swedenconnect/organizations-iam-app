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
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.AdminSessionResponse;
import se.swedenconnect.iam.admin.controllers.dto.FunctionResponse;
import se.swedenconnect.iam.admin.controllers.dto.FunctionRightResponse;
import se.swedenconnect.iam.admin.controllers.dto.OrgRightResponse;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;

import java.util.List;

/**
 * Endpoint exposing the bootstrapped admin session data as JSON.
 *
 * <p>Returns HTTP 200 with an {@link AdminSessionResponse} if session data is present, or
 * HTTP 204 if the session does not contain bootstrapped data (e.g. bootstrap failed at
 * login time).</p>
 *
 * <p>Security: no additional configuration is needed — the existing {@code anyRequest().authenticated()}
 * rule already protects this endpoint.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class SessionController {

  private final IamAdminProperties properties;

  @GetMapping(value = "/session", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminSessionResponse> session(final HttpServletRequest request) {
    final HttpSession existing = request.getSession(false);
    if (existing == null) {
      log.debug("GET /api/session — no session, returning 204");
      return ResponseEntity.noContent().build();
    }

    final Object attr = existing.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR);
    if (!(attr instanceof final AdminSessionData data)) {
      log.debug("GET /api/session — session exists but contains no admin data, returning 204");
      return ResponseEntity.noContent().build();
    }

    log.debug("GET /api/session — returning session data (superuser={}, functions={})",
        data.currentUserIsSuperuser(), data.functions().size());

    return ResponseEntity.ok(toResponse(data));
  }

  // ---------------------------------------------------------------------------
  // Mapping helpers
  // ---------------------------------------------------------------------------

  private AdminSessionResponse toResponse(final AdminSessionData data) {
    return new AdminSessionResponse(
        data.currentUserIsSuperuser(),
        data.functionConstraint(),
        data.orgConstraint(),
        this.properties.isAllowFunctionRemoval(),
        this.properties.isAllowOrgRights(),
        data.functions().stream().map(SessionController::toFunctionResponse).toList(),
        toOrgRights(data.claim()),
        data.adminOrgIdentifiers()
    );
  }

  private static List<OrgRightResponse> toOrgRights(final OrgRightsClaim claim) {
    if (claim.superuser()) {
      return List.of();
    }
    return claim.orgEntries().stream()
        .map(e -> new OrgRightResponse(
            e.orgIdentifier().toString(),
            e.functions().stream()
                .map(f -> new FunctionRightResponse(f.function(), f.right()))
                .toList()))
        .toList();
  }

  private static FunctionResponse toFunctionResponse(final FunctionInfo f) {
    final String nameSv = f.name().get("sv");
    final String nameEn = f.name().get("en");
    final var desc = f.description();
    return new FunctionResponse(
        f.id(),
        nameSv != null ? nameSv : f.id(),
        nameEn != null ? nameEn : f.id(),
        desc != null ? desc.get("sv") : null,
        desc != null ? desc.get("en") : null
    );
  }

}
