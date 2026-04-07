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

import com.nimbusds.jose.jwk.JWK;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link IamSecurityAutoConfiguration}.
 *
 * @author Martin Lindström
 */
class IamSecurityAutoConfigurationTest {

  private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(IamSecurityAutoConfiguration.class))
      .withBean(JWK.class, () -> mock(JWK.class));

  @Test
  void defaultConfiguration_registersClaimParser() {
    this.contextRunner.run(context ->
        assertThat(context).hasSingleBean(OrgRightsClaimParser.class));
  }

  @Test
  void customClaimParser_suppressesDefault() {
    this.contextRunner
        .withBean("customParser", OrgRightsClaimParser.class, OrgRightsClaimParser::new)
        .run(context -> {
          assertThat(context).hasSingleBean(OrgRightsClaimParser.class);
          assertThat(context).getBean("customParser").isNotNull();
        });
  }

}
