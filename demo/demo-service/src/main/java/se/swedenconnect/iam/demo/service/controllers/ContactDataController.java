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
package se.swedenconnect.iam.demo.service.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.demo.service.model.ContactData;
import se.swedenconnect.iam.demo.service.storage.ContactDataStore;

/**
 * REST controller exposing contact data for organizations.
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class ContactDataController {

  private final ContactDataStore store;

  public ContactDataController(final ContactDataStore store) {
    this.store = store;
  }

  @GetMapping("/{orgId}/contact")
  @PreAuthorize("hasRole('SUPERUSER') " +
      "or hasAuthority(#orgId + ':demo:read') " +
      "or hasAuthority(#orgId + ':demo:write') " +
      "or hasAuthority(#orgId + ':demo:admin')")
  public ResponseEntity<ContactData> getContact(
      @PathVariable final String orgId,
      final JwtAuthenticationToken token) {

    if (!this.orgIdMatchesToken(orgId, token)) {
      log.info("Token organization_identifier does not match requested orgId '{}' — returning 403", orgId);
      return ResponseEntity.status(403).build();
    }
    return ResponseEntity.ok(this.store.get(orgId));
  }

  @PutMapping("/{orgId}/contact")
  @PreAuthorize("hasRole('SUPERUSER') " +
      "or hasAuthority(#orgId + ':demo:write') " +
      "or hasAuthority(#orgId + ':demo:admin')")
  public ResponseEntity<ContactData> putContact(
      @PathVariable final String orgId,
      @RequestBody final ContactData data,
      final JwtAuthenticationToken token) {

    if (!this.orgIdMatchesToken(orgId, token)) {
      log.info("Token organization_identifier does not match requested orgId '{}' — returning 403", orgId);
      return ResponseEntity.status(403).build();
    }
    this.store.put(orgId, data);
    return ResponseEntity.ok(data);
  }

  private boolean orgIdMatchesToken(
      final String orgId,
      final JwtAuthenticationToken token) {
    final boolean isSuperuser = token.getAuthorities().stream()
        .anyMatch(a -> "ROLE_SUPERUSER".equals(a.getAuthority()));
    if (isSuperuser) {
      return true;
    }
    final String claimedOrg = token.getToken().getClaimAsString("organization_identifier");
    return orgId.equals(claimedOrg);
  }

}
