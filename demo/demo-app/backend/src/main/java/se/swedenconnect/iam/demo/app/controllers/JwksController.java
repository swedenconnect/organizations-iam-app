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
package se.swedenconnect.iam.demo.app.controllers;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the application's public JWKS at {@code /jwks} so that Keycloak can verify
 * {@code private_key_jwt} client assertions signed by this application.
 *
 * @author Martin Lindström
 */
@RestController
public class JwksController {

  private final String jwksJson;

  public JwksController(final JWK oidcClientJwk) {
    this.jwksJson = new JWKSet(oidcClientJwk.toPublicJWK()).toString();
  }

  @GetMapping("/jwks")
  public ResponseEntity<String> jwks() {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.jwksJson);
  }

}
