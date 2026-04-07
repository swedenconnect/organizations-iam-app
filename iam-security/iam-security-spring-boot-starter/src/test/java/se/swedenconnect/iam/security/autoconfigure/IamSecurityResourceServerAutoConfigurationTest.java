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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import se.swedenconnect.iam.security.server.OrgRightsScopeConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IamSecurityResourceServerAutoConfiguration}.
 *
 * @author Martin Lindström
 */
class IamSecurityResourceServerAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(IamSecurityResourceServerAutoConfiguration.class));

  @Test
  void withResourceServerOnClasspath_registersScopeConverter() {
    this.contextRunner.run(context ->
        assertThat(context).hasSingleBean(OrgRightsScopeConverter.class));
  }

  @Test
  void customScopeConverter_suppressesDefault() {
    this.contextRunner
        .withBean("customConverter", OrgRightsScopeConverter.class, OrgRightsScopeConverter::new)
        .run(context -> {
          assertThat(context).hasSingleBean(OrgRightsScopeConverter.class);
          assertThat(context).getBean("customConverter").isNotNull();
        });
  }

}
