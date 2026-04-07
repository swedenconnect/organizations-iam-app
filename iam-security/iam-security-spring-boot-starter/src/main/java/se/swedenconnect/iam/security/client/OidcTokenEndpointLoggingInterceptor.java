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
package se.swedenconnect.iam.security.client;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * {@link ClientHttpRequestInterceptor} that logs OAuth2/OIDC token endpoint requests
 * and responses at TRACE level.
 *
 * <p>For OIDC flows: sensitive token fields ({@code access_token}, {@code refresh_token},
 * {@code id_token}, {@code client_secret}) are redacted in log output, and the ID token
 * payload is decoded and logged at DEBUG level when present.</p>
 *
 * <p>For pure OAuth2 flows (no {@code id_token} in the response): the access token
 * payload is decoded and logged at DEBUG level, since the access token is the primary
 * artifact of interest. The raw access token value itself remains redacted.</p>
 *
 * <p>This interceptor is registered automatically when
 * {@code iam.security.debug=true}. Never enable in production.</p>
 *
 * @author Martin Lindström
 */
public class OidcTokenEndpointLoggingInterceptor implements ClientHttpRequestInterceptor {

  private static final Logger log = LoggerFactory.getLogger("OIDC");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ObjectWriter jsonWriter = this.objectMapper.writerWithDefaultPrettyPrinter();

  /**
   * Intercepts a token endpoint request, logs request/response at TRACE level, and
   * decodes the ID token or access token payload at DEBUG level.
   *
   * @param request the outgoing request; must not be null
   * @param body the request body; must not be null
   * @param execution the execution chain; must not be null
   * @return the (buffered) response; never null
   * @throws IOException if the request or response cannot be read
   */
  @Override
  public @NonNull ClientHttpResponse intercept(
      final @NonNull HttpRequest request,
      final byte @NonNull [] body,
      final @NonNull ClientHttpRequestExecution execution) throws IOException {

    log.trace("OIDC -> {} {}", request.getMethod(), request.getURI());
    request.getHeaders().forEach((k, v) -> {
      if (!k.equalsIgnoreCase(HttpHeaders.AUTHORIZATION)) {
        log.trace("OIDC -> H {}: {}", k, v);
      }
    });

    if (this.isJson(request.getHeaders())) {
      this.logJson("OIDC -> BODY", body, true);
    }

    final ClientHttpResponse response = execution.execute(request, body);
    final byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());

    log.trace("OIDC <- {} {}", response.getStatusCode(), request.getURI());
    response.getHeaders().forEach((k, v) -> log.trace("OIDC <- H {}: {}", k, v));

    if (this.isJson(response.getHeaders())) {
      this.logJson("OIDC <- BODY", responseBody, true);
      this.logTokenPayloadIfPresent(responseBody);
    }

    return new BufferedClientHttpResponse(response, responseBody);
  }

  private boolean isJson(final @NonNull HttpHeaders headers) {
    final MediaType contentType = headers.getContentType();
    return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
  }

  private void logJson(final @NonNull String prefix, final byte @NonNull [] body, final boolean redactTokens) {
    try {
      final JsonNode root = this.objectMapper.readTree(body);
      final JsonNode toLog = redactTokens ? this.redactTokenFields(root) : root;
      log.trace("{}:\n{}", prefix, this.jsonWriter.writeValueAsString(toLog));
    }
    catch (final Exception e) {
      log.trace("{} (unparseable): {}", prefix, new String(body, StandardCharsets.UTF_8));
    }
  }

  private void logTokenPayloadIfPresent(final byte @NonNull [] responseBody) {
    try {
      final JsonNode root = this.objectMapper.readTree(responseBody);

      final JsonNode idTokenNode = root.get("id_token");
      if (idTokenNode != null && idTokenNode.isString()) {
        final JsonNode payload = this.decodeJwtPayloadToJson(idTokenNode.asString());
        if (payload != null) {
          log.debug("OIDC <- id_token payload:\n{}", this.jsonWriter.writeValueAsString(payload));
        }
        else {
          log.debug("OIDC <- id_token payload: (could not decode)");
        }
        return;
      }

      final JsonNode accessTokenNode = root.get("access_token");
      if (accessTokenNode != null && accessTokenNode.isString()) {
        final JsonNode payload = this.decodeJwtPayloadToJson(accessTokenNode.asString());
        if (payload != null) {
          log.debug("OIDC <- access_token payload:\n{}", this.jsonWriter.writeValueAsString(payload));
        }
        else {
          log.debug("OIDC <- access_token payload: (could not decode)");
        }
      }
    }
    catch (final Exception e) {
      log.debug("OIDC <- token payload decode failed", e);
    }
  }

  private @Nullable JsonNode decodeJwtPayloadToJson(final @NonNull String jwt) {
    try {
      final String[] parts = jwt.split("\\.");
      if (parts.length < 2) {
        return null;
      }
      final byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      return this.objectMapper.readTree(decoded);
    }
    catch (final Exception e) {
      return null;
    }
  }

  private @NonNull JsonNode redactTokenFields(final @NonNull JsonNode root) {
    final JsonNode copy = root.deepCopy();
    if (copy.isObject()) {
      this.redactIfPresent(copy, "access_token");
      this.redactIfPresent(copy, "refresh_token");
      this.redactIfPresent(copy, "id_token");
      this.redactIfPresent(copy, "client_secret");
    }
    return copy;
  }

  private void redactIfPresent(final @NonNull JsonNode node, final @NonNull String field) {
    if (node.isObject() && node.has(field)) {
      ((ObjectNode) node).put(field, "<redacted>");
    }
  }

  private static final class BufferedClientHttpResponse implements ClientHttpResponse {

    private final @NonNull ClientHttpResponse delegate;
    private final byte @NonNull [] body;

    BufferedClientHttpResponse(final @NonNull ClientHttpResponse delegate, final byte @NonNull [] body) {
      this.delegate = delegate;
      this.body = body;
    }

    @Override
    public @NonNull HttpStatusCode getStatusCode() throws IOException {
      return this.delegate.getStatusCode();
    }

    @Override
    public @NonNull String getStatusText() throws IOException {
      return this.delegate.getStatusText();
    }

    @Override
    public @NonNull HttpHeaders getHeaders() {
      return this.delegate.getHeaders();
    }

    @Override
    public @NonNull InputStream getBody() {
      return new ByteArrayInputStream(this.body);
    }

    @Override
    public void close() {
      this.delegate.close();
    }
  }

}
