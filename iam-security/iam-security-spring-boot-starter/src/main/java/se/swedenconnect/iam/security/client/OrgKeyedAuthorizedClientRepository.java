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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.util.WebUtils;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-backed {@link OAuth2AuthorizedClientRepository} that keeps one authorized client per
 * user, client registration, organization and function.
 *
 * <p>A client registration whose scope template is {@code {org}:{function}:<right>} yields a
 * different access token for each organization and function the user acts for. This repository
 * stores each such token separately, so that a token obtained for one organization is never
 * returned when another organization is current.</p>
 *
 * <p>The organization and function are part of a token's identity only if the registration's
 * scope template contains the {@code {org}} or {@code {function}} placeholder. A registration
 * without placeholders, such as a login registration, therefore has one token per user.</p>
 *
 * <p>When a token is loaded or removed, the organization and function are taken from
 * {@link OAuthClientContext}. The function falls back to the configured application function
 * ({@code iam.security.function}) when the context has none, in the same way as
 * {@link OrgScopedAuthorizationRequestResolver}.</p>
 *
 * <p>When a token is saved, the organization and function are taken from the token's granted
 * scope, matched against the registration's scope template. If no granted scope matches the
 * template unambiguously, the values from the current context are used.</p>
 *
 * <p>Tokens are only stored and returned for authenticated, non-anonymous users. They are kept
 * in the HTTP session and are discarded when the session ends.</p>
 *
 * @author Stefan Santesson
 * @author Martin Lindström
 */
@Slf4j
public class OrgKeyedAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

  /** The session attribute holding the authorized clients. */
  static final String SESSION_ATTRIBUTE =
      OrgKeyedAuthorizedClientRepository.class.getName() + ".AUTHORIZED_CLIENTS";

  /** Scope placeholder for the organization. */
  private static final String ORG_PLACEHOLDER = "{org}";

  /** Scope placeholder for the function. */
  private static final String FUNCTION_PLACEHOLDER = "{function}";

  private final @NonNull ClientRegistrationRepository clientRegistrationRepository;
  private final @NonNull OAuthClientContext oAuthClientContext;
  private final @Nullable String defaultFunction;
  private final @NonNull AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

  /**
   * Creates a new repository.
   *
   * @param clientRegistrationRepository the client registrations, used to read scope templates;
   *     must not be null
   * @param oAuthClientContext the session-scoped OAuth2 client context; must not be null
   * @param defaultFunction the function used when the context has none ({@code iam.security.function});
   *     may be null
   */
  public OrgKeyedAuthorizedClientRepository(
      final @NonNull ClientRegistrationRepository clientRegistrationRepository,
      final @NonNull OAuthClientContext oAuthClientContext,
      final @Nullable String defaultFunction) {
    this.clientRegistrationRepository = clientRegistrationRepository;
    this.oAuthClientContext = oAuthClientContext;
    this.defaultFunction = defaultFunction;
  }

  /** {@inheritDoc} */
  @Override
  @SuppressWarnings("unchecked")
  public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
      final @NonNull String clientRegistrationId,
      final @Nullable Authentication principal,
      final @NonNull HttpServletRequest request) {

    if (!this.isAuthenticated(principal)) {
      return null;
    }
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return null;
    }
    final Map<Key, OAuth2AuthorizedClient> clients = getClients(session);
    if (clients == null) {
      return null;
    }
    return (T) clients.get(this.contextKey(clientRegistrationId, principal.getName()));
  }

  /** {@inheritDoc} */
  @Override
  public void saveAuthorizedClient(
      final @NonNull OAuth2AuthorizedClient authorizedClient,
      final @Nullable Authentication principal,
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response) {

    if (!this.isAuthenticated(principal)) {
      log.debug("Authorized client for registration '{}' not saved, no authenticated user",
          authorizedClient.getClientRegistration().getRegistrationId());
      return;
    }
    final Key key = this.grantedKey(authorizedClient, principal.getName());
    final HttpSession session = request.getSession();
    Map<Key, OAuth2AuthorizedClient> clients;
    synchronized (WebUtils.getSessionMutex(session)) {
      clients = getClients(session);
      if (clients == null) {
        clients = new ConcurrentHashMap<>();
        session.setAttribute(SESSION_ATTRIBUTE, clients);
      }
    }
    clients.put(key, authorizedClient);
    // Set the attribute again so that session implementations that persist on write see the change
    session.setAttribute(SESSION_ATTRIBUTE, clients);
    log.debug("Saved authorized client for {}", key);
  }

  /** {@inheritDoc} */
  @Override
  public void removeAuthorizedClient(
      final @NonNull String clientRegistrationId,
      final @Nullable Authentication principal,
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response) {

    if (!this.isAuthenticated(principal)) {
      return;
    }
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return;
    }
    final Map<Key, OAuth2AuthorizedClient> clients = getClients(session);
    if (clients == null) {
      return;
    }
    final Key key = this.contextKey(clientRegistrationId, principal.getName());
    if (clients.remove(key) != null) {
      session.setAttribute(SESSION_ATTRIBUTE, clients);
      log.debug("Removed authorized client for {}", key);
    }
  }

  /**
   * Tells whether the principal is an authenticated, non-anonymous user.
   *
   * @param principal the principal; may be null
   * @return {@code true} if the principal identifies a user
   */
  private boolean isAuthenticated(final @Nullable Authentication principal) {
    return principal != null && principal.isAuthenticated() && !this.trustResolver.isAnonymous(principal);
  }

  /**
   * Builds the key for a registration from the current {@link OAuthClientContext}.
   *
   * @param registrationId the client registration ID; must not be null
   * @param user the user name; must not be null
   * @return the key; never null
   */
  private @NonNull Key contextKey(final @NonNull String registrationId, final @NonNull String user) {
    final ScopeTemplate template = this.scopeTemplate(registrationId);
    return new Key(user, registrationId,
        template.hasOrg() ? this.oAuthClientContext.getOrg() : null,
        template.hasFunction() ? this.contextFunction() : null);
  }

  /**
   * Builds the key for an authorized client being saved. The organization and function are read from
   * the granted scope when it matches the scope template, otherwise from the current context.
   *
   * @param authorizedClient the authorized client; must not be null
   * @param user the user name; must not be null
   * @return the key; never null
   */
  private @NonNull Key grantedKey(final @NonNull OAuth2AuthorizedClient authorizedClient, final @NonNull String user) {
    final String registrationId = authorizedClient.getClientRegistration().getRegistrationId();
    final ScopeTemplate template = ScopeTemplate.of(authorizedClient.getClientRegistration().getScopes());
    if (!template.hasOrg() && !template.hasFunction()) {
      return new Key(user, registrationId, null, null);
    }
    final Map<String, String> values = template.match(authorizedClient.getAccessToken().getScopes());
    if (values == null) {
      log.debug("Granted scope {} for registration '{}' does not match {} unambiguously, using current context",
          authorizedClient.getAccessToken().getScopes(), registrationId, template.scopes());
      return this.contextKey(registrationId, user);
    }
    return new Key(user, registrationId,
        template.hasOrg() ? values.get(ORG_PLACEHOLDER) : null,
        template.hasFunction() ? values.get(FUNCTION_PLACEHOLDER) : null);
  }

  /**
   * Returns the current function from the context, falling back to the configured application function.
   *
   * @return the function, or null
   */
  private @Nullable String contextFunction() {
    return Optional.ofNullable(this.oAuthClientContext.getFunction()).orElse(this.defaultFunction);
  }

  /**
   * Returns the scope template of a registration. An unknown registration gives an empty template.
   *
   * @param registrationId the client registration ID; must not be null
   * @return the scope template; never null
   */
  private @NonNull ScopeTemplate scopeTemplate(final @NonNull String registrationId) {
    final ClientRegistration registration = this.clientRegistrationRepository.findByRegistrationId(registrationId);
    return ScopeTemplate.of(registration != null ? registration.getScopes() : Set.of());
  }

  /**
   * Returns the map of authorized clients stored in the session.
   *
   * @param session the session; must not be null
   * @return the map, or null if none is stored
   */
  @SuppressWarnings("unchecked")
  private static @Nullable Map<Key, OAuth2AuthorizedClient> getClients(final @NonNull HttpSession session) {
    return (Map<Key, OAuth2AuthorizedClient>) session.getAttribute(SESSION_ATTRIBUTE);
  }

  /**
   * Identifies a stored authorized client.
   *
   * @param user the user name
   * @param registrationId the client registration ID
   * @param org the organization, or null if not part of the identity
   * @param function the function, or null if not part of the identity
   */
  record Key(@NonNull String user, @NonNull String registrationId, @Nullable String org, @Nullable String function)
      implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  /**
   * The scope template of a client registration, used to read the organization and function from a granted scope.
   *
   * @param scopes the template scopes that contain at least one placeholder
   * @param hasOrg whether any scope contains {@code {org}}
   * @param hasFunction whether any scope contains {@code {function}}
   */
  record ScopeTemplate(@NonNull Set<String> scopes, boolean hasOrg, boolean hasFunction) {

    /**
     * Creates a template from the scopes of a client registration.
     *
     * @param registrationScopes the registration scopes; may be null
     * @return the template; never null
     */
    static @NonNull ScopeTemplate of(final @Nullable Set<String> registrationScopes) {
      final Set<String> scopes = new HashSet<>();
      boolean hasOrg = false;
      boolean hasFunction = false;
      for (final String scope : Optional.ofNullable(registrationScopes).orElse(Set.of())) {
        final boolean org = scope.contains(ORG_PLACEHOLDER);
        final boolean function = scope.contains(FUNCTION_PLACEHOLDER);
        if (org || function) {
          scopes.add(scope);
        }
        hasOrg |= org;
        hasFunction |= function;
      }
      return new ScopeTemplate(Set.copyOf(scopes), hasOrg, hasFunction);
    }

    /**
     * Matches granted scopes against the template. Every way in which a granted scope can match a
     * template scope is considered. The match succeeds only if all of them give the same
     * placeholder values.
     *
     * @param grantedScopes the granted scopes; may be null
     * @return the placeholder values, or null if there is no match or more than one possible match
     */
    @Nullable Map<String, String> match(final @Nullable Set<String> grantedScopes) {
      final Set<Map<String, String>> candidates = new HashSet<>();
      for (final String template : this.scopes) {
        final List<String> tokens = tokenize(template);
        for (final String granted : Optional.ofNullable(grantedScopes).orElse(Set.of())) {
          matchTokens(tokens, 0, granted, 0, new HashMap<>(), candidates);
          if (candidates.size() > 1) {
            return null;
          }
        }
      }
      return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /**
     * Splits a template scope into literal text and placeholders.
     *
     * @param template the template scope; must not be null
     * @return the tokens; never null
     */
    private static @NonNull List<String> tokenize(final @NonNull String template) {
      final List<String> tokens = new ArrayList<>();
      int pos = 0;
      while (pos < template.length()) {
        final int org = template.indexOf(ORG_PLACEHOLDER, pos);
        final int function = template.indexOf(FUNCTION_PLACEHOLDER, pos);
        final int next = org < 0 ? function : function < 0 ? org : Math.min(org, function);
        if (next < 0) {
          tokens.add(template.substring(pos));
          break;
        }
        if (next > pos) {
          tokens.add(template.substring(pos, next));
        }
        final String placeholder = next == org ? ORG_PLACEHOLDER : FUNCTION_PLACEHOLDER;
        tokens.add(placeholder);
        pos = next + placeholder.length();
      }
      return tokens;
    }

    /**
     * Collects every assignment of non-empty placeholder values that makes the tokens equal to the
     * granted scope. Stops early once more than one distinct assignment is found.
     *
     * @param tokens the template tokens; must not be null
     * @param tokenIndex the current token
     * @param granted the granted scope; must not be null
     * @param pos the current position in the granted scope
     * @param values the values assigned so far; must not be null
     * @param result receives the complete assignments; must not be null
     */
    private static void matchTokens(final @NonNull List<String> tokens, final int tokenIndex,
        final @NonNull String granted, final int pos, final @NonNull Map<String, String> values,
        final @NonNull Set<Map<String, String>> result) {

      if (result.size() > 1) {
        return;
      }
      if (tokenIndex == tokens.size()) {
        if (pos == granted.length()) {
          result.add(Map.copyOf(values));
        }
        return;
      }
      final String token = tokens.get(tokenIndex);
      if (!ORG_PLACEHOLDER.equals(token) && !FUNCTION_PLACEHOLDER.equals(token)) {
        if (granted.startsWith(token, pos)) {
          matchTokens(tokens, tokenIndex + 1, granted, pos + token.length(), values, result);
        }
        return;
      }
      final String assigned = values.get(token);
      if (assigned != null) {
        if (granted.startsWith(assigned, pos)) {
          matchTokens(tokens, tokenIndex + 1, granted, pos + assigned.length(), values, result);
        }
        return;
      }
      for (int end = pos + 1; end <= granted.length(); end++) {
        values.put(token, granted.substring(pos, end));
        matchTokens(tokens, tokenIndex + 1, granted, end, values, result);
        values.remove(token);
      }
    }
  }

}
