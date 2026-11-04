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
package se.swedenconnect.iam.admin.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;

import java.io.IOException;

/**
 * Installs the session's correlation ID in {@link CorrelationIDHolder} for the duration of each
 * request, and clears it afterwards so that it never carries over to unrelated work on the same
 * thread.
 *
 * <p>A request to the OAuth2 authorization endpoint starts a standard login, and gives the session a
 * new correlation ID unless it already has one. The SSO endpoint assigns its own, see
 * {@link SessionCorrelationId#assign}. Requests to the IAM service API ({@code /iam-api/}) carry no
 * correlation ID.</p>
 *
 * <p>The filter must run before the Spring Security filter chain, so that the ID is in place for the
 * login and logout handling.</p>
 *
 * @author Martin Lindström
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Path prefix of the endpoint that starts an OAuth2 login. */
  private static final String AUTHORIZATION_PATH = "/oauth2/authorization/";

  /** Path prefix of the IAM service API. */
  private static final String IAM_API_PATH = "/iam-api/";

  /** {@inheritDoc} */
  @Override
  protected void doFilterInternal(final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response, final @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    CorrelationIDHolder.clear();
    try {
      final String path = request.getServletPath();
      if (path.startsWith(AUTHORIZATION_PATH)) {
        SessionCorrelationId.assign(request.getSession(true), null);
      }
      else if (!path.startsWith(IAM_API_PATH)) {
        final CorrelationID id = SessionCorrelationId.get(request.getSession(false));
        if (id != null) {
          CorrelationIDHolder.set(id);
        }
      }
      filterChain.doFilter(request, response);
    }
    finally {
      CorrelationIDHolder.clear();
    }
  }

}
