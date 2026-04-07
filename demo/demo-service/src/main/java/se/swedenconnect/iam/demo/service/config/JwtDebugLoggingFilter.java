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
package se.swedenconnect.iam.demo.service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;

/**
 * Debug filter that logs the decoded JWT payload from the Authorization header
 * <strong>before</strong> Spring Security validates it. Useful for diagnosing
 * audience, scope, and claim issues.
 *
 * <p>Remove this filter once debugging is complete.</p>
 *
 * @author Martin Lindström
 */
@Slf4j
public class JwtDebugLoggingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain) throws ServletException, IOException {

    if (log.isDebugEnabled()) {
      final String authHeader = request.getHeader("Authorization");
      if (authHeader != null && authHeader.startsWith("Bearer ")) {
        final String token = authHeader.substring(7);
        final String[] parts = token.split("\\.");
        if (parts.length == 3) {
          try {
            final String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            try {
              final ObjectMapper mapper = new ObjectMapper();
              final Object json = mapper.readValue(payload, Object.class);
              final String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
              log.debug("Incoming JWT payload:\n{}", pretty);
            }
            catch (final JacksonException e) {
              log.debug("Incoming JWT payload: {}", payload);
            }
          }
          catch (final IllegalArgumentException e) {
            log.debug("Could not decode JWT payload: {}", e.getMessage());
          }
        }
      }
    }

    filterChain.doFilter(request, response);
  }

}
