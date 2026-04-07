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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;

/**
 * Top-level auto-configuration for the IAM security library.
 *
 * <p>Registers {@link IamSecurityProperties} and {@link OrgRightsClaimParser} as beans.
 * These are always useful regardless of whether the application is an OIDC client or a
 * resource server.</p>
 *
 * @author Martin Lindström
 */
@AutoConfiguration
@EnableConfigurationProperties(IamSecurityProperties.class)
@Import({ IamSecurityClientAutoConfiguration.class, IamSecurityResourceServerAutoConfiguration.class })
public class IamSecurityAutoConfiguration {

  /**
   * Provides a default {@link OrgRightsClaimParser} bean.
   *
   * <p>Applications may define their own {@link OrgRightsClaimParser} bean to override this default.</p>
   *
   * @return a new {@link OrgRightsClaimParser} instance; never null
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  OrgRightsClaimParser orgRightsClaimParser() {
    return new OrgRightsClaimParser();
  }

}
