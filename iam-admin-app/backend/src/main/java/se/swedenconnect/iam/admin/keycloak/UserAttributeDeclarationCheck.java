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
package se.swedenconnect.iam.admin.keycloak;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import se.swedenconnect.iam.admin.keycloak.model.UserProfileInfo;

import java.util.List;

/**
 * Warns at startup about a user attribute the application writes that the realm discards.
 *
 * <p>Keycloak keeps a user attribute only when the realm's user profile declares it, or when the
 * realm's unmanaged attribute policy allows an attribute it does not declare. An attribute that
 * neither covers is dropped, and the write still answers {@code 204 No Content} — so a value an
 * administrator entered is lost without anything reporting it. That is what left phone numbers
 * unstored, see
 * <a href="https://github.com/swedenconnect/organizations-iam-app/issues/185">issue #185</a>.</p>
 *
 * <p>The attributes are those of {@link KeycloakAdminClient#WRITTEN_USER_ATTRIBUTES}, and
 * {@code bootstrap-realm.sh} is what declares them. The check runs once the application is up,
 * reads nothing but the profile, and never fails the startup: a Keycloak that cannot be reached
 * leaves a warning, not a dead application.</p>
 *
 * @author Per Fredrik Plars
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserAttributeDeclarationCheck {

  private final KeycloakAdminClient keycloakAdminClient;

  /**
   * Reads the realm's user profile and reports the attributes it discards.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void checkWrittenAttributes() {
    final UserProfileInfo profile;
    try {
      profile = this.keycloakAdminClient.fetchUserProfile();
    }
    catch (final RuntimeException e) {
      log.warn("Could not read the realm's user profile, so the attributes the application writes "
          + "were not checked: {}", e.getMessage());
      return;
    }
    this.report(profile);
  }

  /**
   * Logs what the given profile means for the attributes the application writes.
   *
   * @param profile the realm's user profile
   * @return the attributes the realm discards, sorted; empty when it keeps all of them
   */
  @NonNull
  List<String> report(final @NonNull UserProfileInfo profile) {
    final List<String> discarded = profile.discarded(KeycloakAdminClient.WRITTEN_USER_ATTRIBUTES);
    if (discarded.isEmpty()) {
      log.debug("The realm keeps every user attribute the application writes");
      return discarded;
    }
    log.warn("The realm's user profile does not declare {}, so Keycloak discards {} when a user is "
            + "written and the value entered is lost. Run bootstrap-realm.sh against the realm to "
            + "declare the attributes it is missing.",
        discarded, discarded.size() == 1 ? "it" : "them");
    return discarded;
  }

}
