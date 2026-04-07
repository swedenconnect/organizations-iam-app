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
package se.swedenconnect.iam.security.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import se.swedenconnect.security.credential.config.properties.PkiCredentialConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for the IAM security library.
 *
 * <p>All properties are under the {@code iam.security} prefix.</p>
 *
 * @author Martin Lindström
 */
@ConfigurationProperties("iam.security")
public class IamSecurityProperties {

  /**
   * The function identifier this application is scoped to (e.g. {@code walletreg}).
   * When set, the library operates in function-scoped mode: the {@code org_rights} claim
   * is filtered to entries relevant to this function (exact match or org-wide {@code *}),
   * and authorities are produced as {@link se.swedenconnect.iam.security.claims.FunctionScopedAuthority}
   * instances of the form {@code {orgId}:{right}} rather than the full three-part
   * {@link se.swedenconnect.iam.security.claims.OrganizationalAuthority} form.
   *
   * <p>When not set, the full authority model is used.</p>
   */
  @Getter
  @Setter
  private @Nullable String function;

  /**
   * When {@code true}, enables trace-level logging of all OAuth2/OIDC token endpoint
   * requests and responses, including ID token payload decoding. Sensitive fields
   * (access_token, refresh_token, id_token, client_secret) are redacted.
   *
   * <p>Never enable in production.</p>
   */
  @Getter
  @Setter
  private boolean debug = false;

  private final Client client = new Client();

  /**
   * Returns the client authentication properties.
   *
   * @return the client properties; never null
   */
  public @NonNull Client getClient() {
    return this.client;
  }

  /**
   * Properties for OIDC and OAuth2 clients that authenticate to Keycloak using
   * {@code private_key_jwt}.
   */
  public static class Client {

    /**
     * The OIDC client credential used for {@code private_key_jwt} client authentication.
     *
     * <p>Supports three configuration styles:</p>
     * <ul>
     *   <li>{@code bundle} — reference to a named credential in the credentials-support bundle registry</li>
     *   <li>{@code jks} — inline JKS keystore configuration</li>
     *   <li>{@code pem} — inline PEM key/certificate configuration</li>
     * </ul>
     */
    @Getter
    @Setter
    @NestedConfigurationProperty
    private @Nullable PkiCredentialConfigurationProperties credential;

    /**
     * Per-registration properties keyed by Spring Security registration ID.
     *
     * <p>Example:</p>
     * <pre>{@code
     * iam:
     *   security:
     *     client:
     *       registrations:
     *         my-service-read:
     *           resource: https://my-service.example.com
     * }</pre>
     */
    @Getter
    private final Map<String, RegistrationProperties> registrations = new LinkedHashMap<>();

    /**
     * Per-registration properties for an OAuth2 client registration.
     */
    public static class RegistrationProperties {

      /**
       * The resource server URI sent as the OAuth2 {@code resource} parameter (RFC 8707).
       * Binds the access token to a specific resource server, setting the {@code aud} claim
       * in the token.
       */
      @Getter
      @Setter
      private @Nullable String resource;

    }

  }

}
