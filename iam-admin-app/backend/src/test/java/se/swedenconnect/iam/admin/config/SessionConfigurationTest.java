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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is behind the HTTP session, and what {@code iam.admin.session.store} does to it.
 *
 * <p>No Redis is needed to tell the stores apart: the repository is built from the connection
 * factory without connecting, so the wiring is settled before anything is read or written.</p>
 *
 * @author Per Fredrik Plars
 */
class SessionConfigurationTest {

  private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
          SessionAutoConfiguration.class, SessionDataRedisAutoConfiguration.class))
      .withUserConfiguration(SessionConfiguration.class);

  @Test
  void sessionsAreKeptInMemoryUnlessRedisIsAskedFor() {
    this.runner.run(context -> {
      assertThat(context).hasSingleBean(SessionRepository.class);
      assertThat(context).getBean(SessionRepository.class)
          .isInstanceOf(MapSessionRepository.class);
      assertThat(context).hasSingleBean(SessionRepositoryFilter.class);
    });
  }

  @Test
  void sessionsAreKeptInRedisWhenTheStoreIsRedis() {
    this.runner.withPropertyValues("iam.admin.session.store=redis").run(context -> {
      assertThat(context).hasSingleBean(SessionRepository.class);
      assertThat(context).getBean(SessionRepository.class)
          .isInstanceOf(RedisSessionRepository.class);
      assertThat(context).doesNotHaveBean(MapSessionRepository.class);
    });
  }

  @Test
  void theSessionTimeoutIsTheConfiguredOneForEitherStore() {
    this.runner.withPropertyValues("spring.session.timeout=45m").run(context -> {
      assertThat(context).getBean(SessionRepository.class)
          .extracting("defaultMaxInactiveInterval")
          .isEqualTo(Duration.ofMinutes(45));
    });
  }

  @Test
  void aStoreThatIsNeitherMemoryNorRedisIsRefused() {
    final IamAdminProperties.Session session = new IamAdminProperties.Session();
    session.setStore("memcached");

    assertThatThrownBy(session::afterPropertiesSet)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("iam.admin.session.store");
  }

}
