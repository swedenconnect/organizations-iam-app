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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.demo.app.config.DemoAppProperties;
import se.swedenconnect.iam.security.client.OAuthClientContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

/**
 * Controller that proxies data calls to demo-service, using OAuth2 access tokens obtained transparently by
 * {@code OAuth2ClientHttpRequestInterceptor}.
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api/demo")
@Slf4j
public class OrgDataController {

  private final RestClient resourceServerRestClient;
  private final OAuthClientContext oAuthClientContext;
  private final DemoAppProperties demoAppProperties;
  private final String demoServiceBaseUrl;

  public OrgDataController(
      @Qualifier("resourceServerRestClient") final @NonNull RestClient resourceServerRestClient,
      final @NonNull OAuthClientContext oAuthClientContext,
      final @NonNull DemoAppProperties demoAppProperties,
      @Value("${demo.service.base-url:https://local.dev.swedenconnect.se:16995}") final @NonNull String demoServiceBaseUrl) {
    this.resourceServerRestClient = resourceServerRestClient;
    this.oAuthClientContext = oAuthClientContext;
    this.demoAppProperties = demoAppProperties;
    this.demoServiceBaseUrl = demoServiceBaseUrl;
  }

  @GetMapping("/{orgId}/contact")
  public ResponseEntity<String> getContact(@PathVariable final @NonNull String orgId) {
    this.oAuthClientContext.setOrg(orgId);
    try {
      return this.resourceServerRestClient.get()
          .uri(this.demoServiceBaseUrl + "/api/{orgId}/contact", orgId)
          .attributes(clientRegistrationId("demo-service-read"))
          .exchange((req, resp) -> {
            final byte[] body = resp.getBody().readAllBytes();
            return ResponseEntity.status(resp.getStatusCode())
                .body(new String(body, StandardCharsets.UTF_8));
          });
    }
    catch (final ClientAuthorizationRequiredException e) {
      log.debug("No access token for demo-service-read — returning authorization URL");
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"authorizationUrl\":\"/oauth2/authorization/demo-service-read\"}");
    }
  }

  @PutMapping(value = "/{orgId}/contact", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> putContact(
      @PathVariable final @NonNull String orgId,
      @RequestBody final @NonNull String requestBody) {

    this.oAuthClientContext.setOrg(orgId);
    try {
      return this.resourceServerRestClient.put()
          .uri(this.demoServiceBaseUrl + "/api/{orgId}/contact", orgId)
          .attributes(clientRegistrationId("demo-service-write"))
          .contentType(MediaType.APPLICATION_JSON)
          .body(requestBody)
          .exchange((req, resp) -> {
            final byte[] body = resp.getBody().readAllBytes();
            return ResponseEntity.status(resp.getStatusCode())
                .body(new String(body, StandardCharsets.UTF_8));
          });
    }
    catch (final ClientAuthorizationRequiredException e) {
      log.debug("No access token for demo-service-write — returning authorization URL");
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"authorizationUrl\":\"/oauth2/authorization/demo-service-write\"}");
    }
  }

  @GetMapping("/{orgId}/admin-url")
  public ResponseEntity<Map<String, String>> adminUrl(@PathVariable final @NonNull String orgId) {
    final String url = this.demoAppProperties.getBaseUrl()
        + this.demoAppProperties.getSsoLoginPath()
        + "?org=" + orgId + "&func=demo";
    return ResponseEntity.ok(Map.of("url", url));
  }

}
