/*
 * Copyright (c) 2025-2026. PTS
 */
package se.swedenconnect.iam.security.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * Session-backed {@link OAuth2AuthorizedClientRepository} whose cache key is
 * {@code (registrationId, currentOrg)} rather than just {@code registrationId}.
 *
 * <p>The default {@link org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository}
 * keys authorized clients per {@code (registrationId, principal)}, which loses
 * the org dimension. That means a second org's authorization for the same
 * client registration silently overwrites the first, and refreshing the first
 * one later fails because Keycloak has terminated the previous client session.
 * This repository holds one cache slot per {@code (registrationId, org)} pair,
 * so switching org keeps the earlier token available and independently
 * refreshable.
 *
 * @author Stefan Santesson
 */
public class OrgKeyedAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

  private static final String SESSION_ATTR =
      OrgKeyedAuthorizedClientRepository.class.getName() + ".AUTHORIZED_CLIENTS";

  private final @NonNull OAuthClientContext oAuthClientContext;

  public OrgKeyedAuthorizedClientRepository(final @NonNull OAuthClientContext oAuthClientContext) {
    this.oAuthClientContext = oAuthClientContext;
  }

  private @NonNull String key(final @NonNull String registrationId) {
    final String org = this.oAuthClientContext.getOrg();
    return org == null || org.isBlank() ? registrationId : registrationId + "::" + org;
  }

  @SuppressWarnings("unchecked")
  private @Nullable Map<String, OAuth2AuthorizedClient> sessionMap(
      final @NonNull HttpServletRequest request, final boolean create) {
    final HttpSession session = request.getSession(create);
    if (session == null) {
      return null;
    }
    Map<String, OAuth2AuthorizedClient> map =
        (Map<String, OAuth2AuthorizedClient>) session.getAttribute(SESSION_ATTR);
    if (map == null && create) {
      map = new HashMap<>();
      session.setAttribute(SESSION_ATTR, map);
    }
    return map;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
      final @NonNull String clientRegistrationId,
      final @Nullable Authentication principal,
      final @NonNull HttpServletRequest request) {
    final Map<String, OAuth2AuthorizedClient> map = this.sessionMap(request, false);
    return map == null ? null : (T) map.get(this.key(clientRegistrationId));
  }

  @Override
  public void saveAuthorizedClient(
      final @NonNull OAuth2AuthorizedClient authorizedClient,
      final @NonNull Authentication principal,
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response) {
    final Map<String, OAuth2AuthorizedClient> map = this.sessionMap(request, true);
    if (map == null) {
      return;
    }
    map.put(this.key(authorizedClient.getClientRegistration().getRegistrationId()), authorizedClient);
    // Re-store the AuthroizedClient map in the session so it can be picked up by the applied session framework.
    request.getSession().setAttribute(SESSION_ATTR, map);
  }

  @Override
  public void removeAuthorizedClient(
      final @NonNull String clientRegistrationId,
      final @Nullable Authentication principal,
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response) {
    final Map<String, OAuth2AuthorizedClient> map = this.sessionMap(request, false);
    if (map == null) {
      return;
    }
    map.remove(this.key(clientRegistrationId));
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return;
    }
    if (map.isEmpty()) {
      session.removeAttribute(SESSION_ATTR);
    }
    else {
      session.setAttribute(SESSION_ATTR, map);
    }
  }
}
