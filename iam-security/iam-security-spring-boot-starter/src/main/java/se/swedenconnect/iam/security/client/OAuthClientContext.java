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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Session-scoped bean that holds the current OAuth2 client context: the selected organization
 * and, for multi-function applications, the current function identifier.
 *
 * <p>Applications must call {@link #setOrg(String)} when the user selects an organization.
 * For single-function applications ({@code iam.security.function} is set), the function is
 * resolved automatically from configuration and does not need to be set explicitly.</p>
 *
 * <p>On logout, call {@link #clear()} to reset the context.</p>
 *
 * @author Martin Lindström
 */
@Component
@SessionScope
@Slf4j
public class OAuthClientContext {

  private @Nullable String orgId;
  private @Nullable String function;

  /**
   * Sets the current organization identifier.
   *
   * @param orgId the organization identifier; must not be null
   */
  public void setOrg(final @NonNull String orgId) {
    log.debug("OAuthClientContext: setting org to '{}'", orgId);
    this.orgId = orgId;
  }

  /**
   * Sets the current function identifier. Overrides any auto-configured function.
   *
   * @param function the function identifier; must not be null
   */
  public void setFunction(final @NonNull String function) {
    log.debug("OAuthClientContext: setting function to '{}'", function);
    this.function = function;
  }

  /**
   * Returns the current organization identifier, or {@code null} if not set.
   *
   * @return the org identifier, or null
   */
  public @Nullable String getOrg() {
    return this.orgId;
  }

  /**
   * Returns the current function identifier, or {@code null} if not explicitly set.
   * For single-function apps, the function is resolved from {@code iam.security.function}
   * in the {@code contextAttributesMapper} — this method returns only the explicitly set value.
   *
   * @return the function identifier, or null
   */
  public @Nullable String getFunction() {
    return this.function;
  }

  /**
   * Clears both org and function. Call on logout or session invalidation.
   */
  public void clear() {
    log.debug("OAuthClientContext: clearing org and function");
    this.orgId = null;
    this.function = null;
  }

}
