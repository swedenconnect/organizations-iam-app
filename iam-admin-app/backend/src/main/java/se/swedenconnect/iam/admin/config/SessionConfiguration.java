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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.session.autoconfigure.SessionTimeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * How the application keeps its HTTP sessions.
 *
 * <p>Spring Session is always in use, so a session behaves the same way whichever store is behind
 * it: the same filter, the same cookie, the same save mode. {@code iam.admin.session.store} chooses
 * the store. {@code memory} keeps the sessions in this instance's heap and loses them on a restart,
 * {@code redis} hands them to Spring Boot's Redis configuration, which stands down for as long as a
 * {@link org.springframework.session.SessionRepository} bean is already present.</p>
 *
 * <p>The in-memory store is enough for a single instance and is what local development runs on.
 * More than one instance, or sessions that have to outlive a deployment, need Redis.</p>
 *
 * <p>The in-memory store does not serialize what is put in the session; it holds the attributes as
 * live objects. An attribute that is not {@link java.io.Serializable} therefore fails only once
 * Redis is switched on, which is what {@code SessionAttributeSerializationTest} is there to
 * catch.</p>
 *
 * <p>The session cookie is not configured here. Spring Boot builds Spring Session's cookie
 * serializer from {@code server.servlet.session.cookie.*} for either store.</p>
 *
 * @author Per Fredrik Plars
 */
@Configuration
public class SessionConfiguration {

  /**
   * Holds the sessions in this instance's heap, which is what the application does unless it is
   * told to use Redis.
   *
   * @author Per Fredrik Plars
   */
  @Configuration
  @EnableSpringHttpSession
  @ConditionalOnProperty(name = "iam.admin.session.store", havingValue = "memory",
      matchIfMissing = true)
  static class InMemorySessionConfiguration {

    /**
     * The in-memory session repository. The timeout is taken from the same bean Spring Boot hands
     * the Redis repository — {@code spring.session.timeout} first, then
     * {@code server.servlet.session.timeout} — so changing store does not quietly change how long a
     * session lives. Spring Boot declares that bean only for an embedded server, which a test
     * without one does not have, and Spring Session's own default then applies.
     *
     * @param sessionTimeout how long an idle session lives
     * @return the session repository
     */
    @Bean
    MapSessionRepository sessionRepository(final ObjectProvider<SessionTimeout> sessionTimeout) {
      final MapSessionRepository repository = new MapSessionRepository(new ConcurrentHashMap<>());
      final SessionTimeout timeout = sessionTimeout.getIfAvailable();
      if (timeout != null && timeout.getTimeout() != null) {
        repository.setDefaultMaxInactiveInterval(timeout.getTimeout());
      }
      return repository;
    }
  }

}
