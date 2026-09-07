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
package se.swedenconnect.iam.admin.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for the IAM Admin application.
 *
 * @author Martin Lindström
 * @author Felix Hellman
 */
@ConfigurationProperties("iam.admin")
public class IamAdminProperties {

  /**
   * The request path that initiates an SSO login (without forced re-authentication). External applications redirect
   * users to this path, optionally with {@code org} and {@code func} query parameters.
   */
  @Getter
  @Setter
  private String ssoLoginPath = "/sso/login";

  /**
   * KeyCloak realm name. Used to construct the Admin REST API base URL.
   */
  @Getter
  @Setter
  private String realm = "orgiam";

  /**
   * Base URL of the Keycloak Admin REST API for the configured realm,
   * e.g. {@code https://keycloak.example.com/admin/realms/myrealm}.
   */
  @Getter
  @Setter
  private String adminApiBase;

  /**
   * UI theme / white-label profile. Controls which CSS variables and logo assets are served
   * under {@code /theme/}. Default: {@code digg}.
   */
  @Getter
  @Setter
  private String theme = "digg";

  /**
   * Optional filesystem path to an external theme directory. When set, static theme assets
   * and {@code footer.json} are served from this directory instead of the classpath, enabling
   * theme changes without rebuilding the JAR.
   *
   * <p>Example: {@code /opt/iam-admin/themes/mytheme}</p>
   */
  @Getter
  @Setter
  private String themeDir;

  /**
   * Optional fallback list of client IDs to include as managed clients, in addition to
   * any clients discovered dynamically via the {@code iam_admin_managed} Keycloak client
   * attribute. Use this as a safety net for clients that have not yet had the attribute
   * set. The final managed set is the union of both sources.
   */
  @Getter
  @Setter
  private List<String> authzClientIds;

  /**
   * When {@code true}, the personal identity number (12 digits) is used as the Keycloak
   * {@code username} for newly created users instead of a random UUID. Useful during local
   * development to allow username/password login. Default: {@code false}.
   */
  @Getter
  @Setter
  private boolean pnrUserids = false;

  /**
   * When {@code true}, superusers are permitted to permanently delete a function definition
   * and all its Keycloak artifacts (group, org sub-groups, client scopes, authz policies and
   * permissions). Default: {@code false}.
   */
  @Getter
  @Setter
  private boolean allowFunctionRemoval = false;

  /**
   * Settings for the scheduled reconciliation of managed clients. Disabled by default —
   * reconciliation also runs whenever a client or a function attachment changes, so the schedule
   * exists only to repair drift.
   */
  @Getter
  @Setter
  private ClientReconciliation clientReconciliation = new ClientReconciliation();

  /**
   * When {@code true} (the default), users may be assigned rights at the organization level,
   * implicitly covering all functions. When {@code false}, only function-level assignments
   * are permitted via this application. Existing org-level Keycloak memberships remain
   * visible and can still be removed, but no new ones can be created.
   */
  @Getter
  @Setter
  private boolean allowOrgRights = true;

  /**
   * When {@code false} (the default), a user who is not a superuser cannot grant, remove or
   * downgrade the {@code admin} right, whether at the organization level or at the
   * organization/function level. Only {@code read} and {@code write} are available to such a
   * caller. When {@code true}, an admin may manage the {@code admin} right within the scope they
   * administer. Superusers are never affected by this setting.
   */
  @Getter
  @Setter
  private boolean allowAdminAssigningAdmin = false;

  /**
   * Settings for the scheduled reconciliation of managed clients.
   */
  public static class ClientReconciliation {

    /**
     * Whether managed clients are reconciled on a schedule. Default: {@code false}.
     */
    @Getter
    @Setter
    private boolean enabled = false;

    /**
     * The cron expression controlling how often reconciliation runs. Default: every 15 minutes.
     */
    @Getter
    @Setter
    private String cron = "0 */15 * * * *";
  }

}
