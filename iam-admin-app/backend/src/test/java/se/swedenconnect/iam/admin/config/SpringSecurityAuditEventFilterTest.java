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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration;
import org.springframework.boot.actuate.security.AuthenticationAuditListener;
import org.springframework.boot.actuate.security.AuthorizationAuditListener;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.switchuser.AuthenticationSwitchUserEvent;
import se.swedenconnect.iam.admin.audit.AuditData;
import se.swedenconnect.iam.admin.audit.IamAuditEvent;
import se.swedenconnect.spring.audit.appevents.SystemAlertEvent;
import se.swedenconnect.spring.audit.autoconfigure.AuditRepositoryAutoConfiguration;
import se.swedenconnect.spring.audit.autoconfigure.AuditSupportAutoConfiguration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the security audit events Spring Boot Actuator creates by itself are kept out of the audit log, while the
 * application's own events and the library's {@code system_*} events are written. The application's real
 * configuration files are loaded, for the default, local and compose profiles.
 *
 * @author Martin Lindström
 */
class SpringSecurityAuditEventFilterTest {

  /** The event types created by Spring Boot's security audit listeners. */
  private static final List<String> SPRING_SECURITY_TYPES = List.of(
      AuthenticationAuditListener.AUTHENTICATION_SUCCESS,
      AuthenticationAuditListener.AUTHENTICATION_FAILURE,
      AuthenticationAuditListener.AUTHENTICATION_SWITCH,
      AuthenticationAuditListener.LOGOUT_SUCCESS,
      AuthorizationAuditListener.AUTHORIZATION_FAILURE);

  /** The types the application and the library are expected to write for the events published by the tests. */
  private static final List<String> WRITTEN_TYPES = List.of("system_started", "data_exported", "system_alert");

  /** The types expected in an audit file, which also gets the system_shutdown event when the context closes. */
  private static final List<String> WRITTEN_TYPES_IN_FILE =
      List.of("system_started", "data_exported", "system_alert", "system_shutdown");

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withConfiguration(AutoConfigurations.of(AuditSupportAutoConfiguration.class,
          AuditRepositoryAutoConfiguration.class, AuditAutoConfiguration.class))
      .withUserConfiguration(AuditConfiguration.class);

  /** In the default configuration, the Spring Boot security events are not kept in memory. */
  @Test
  void defaultProfile_springSecurityEventsAreFilteredOut() {
    this.runner.run(context -> {
      assertThat(context).hasSingleBean(AuthenticationAuditListener.class);
      assertThat(context).hasSingleBean(AuthorizationAuditListener.class);

      publishEvents(context);

      assertThat(storedTypes(context)).containsExactlyInAnyOrderElementsOf(WRITTEN_TYPES);
    });
  }

  /** In the local profile, the Spring Boot security events are written neither to the file nor to memory. */
  @Test
  void localProfile_springSecurityEventsAreFilteredOut(@TempDir final Path dir) throws Exception {
    final Path file = dir.resolve("audit.log");
    this.runner
        .withPropertyValues("spring.profiles.active=local", "audit.repository.file.log-file=" + file)
        .run(context -> {
          publishEvents(context);
          assertThat(storedTypes(context)).containsExactlyInAnyOrderElementsOf(WRITTEN_TYPES);
        });

    assertThat(fileTypes(file)).containsExactlyElementsOf(WRITTEN_TYPES_IN_FILE);
  }

  /**
   * In the compose profile, loaded from the compose configuration directory, the Spring Boot security events are not
   * written to the file either.
   */
  @Test
  void composeProfile_springSecurityEventsAreFilteredOut(@TempDir final Path dir) throws Exception {
    Files.createDirectories(dir.resolve("audit"));
    this.runner
        .withPropertyValues("spring.profiles.active=compose",
            "spring.config.additional-location=file:../../compose/config/iam-admin-app/",
            "APP_CONFIG_DIR=" + dir)
        .run(context -> {
          assertThat(context.getEnvironment().getProperty("audit.repository.file.log-file"))
              .isEqualTo(dir + "/audit/iam-admin-audit.log");
          publishEvents(context);
        });

    assertThat(fileTypes(dir.resolve("audit/iam-admin-audit.log"))).containsExactlyElementsOf(WRITTEN_TYPES_IN_FILE);
  }

  /**
   * The filter matches exact type names only, so an event whose type merely resembles a filtered one is still
   * written.
   */
  @Test
  void similarTypeName_isWritten() {
    this.runner.run(context -> {
      context.publishEvent(new AuditApplicationEvent("system", "authentication_success"));
      context.publishEvent(new AuditApplicationEvent("system", "AUTHENTICATION_SUCCESS_X"));

      assertThat(storedTypes(context)).containsExactlyInAnyOrder("authentication_success", "AUTHENTICATION_SUCCESS_X");
    });
  }

  /**
   * A deployment assigning its own exclude-events replaces the default list, and the Spring Boot security events are
   * then written. This also shows that the published events do produce each of the filtered types.
   */
  @Test
  void ownExcludeEvents_replacesTheDefaultList() {
    this.runner
        .withPropertyValues("audit.repository.exclude-events=data_exported")
        .run(context -> {
          publishEvents(context);

          assertThat(storedTypes(context))
              .containsAll(SPRING_SECURITY_TYPES)
              .doesNotContain("data_exported")
              .contains("system_started", "system_alert");
        });
  }

  /**
   * Publishes one event of each Spring Boot security type, through the Spring Security events the actuator's
   * listeners turn into audit events, together with a {@code system_started} event, one of the application's own
   * events and a {@code system_alert} event.
   */
  private static void publishEvents(final AssertableApplicationContext context) {
    final Authentication authentication = authentication();

    context.publishEvent(new ApplicationReadyEvent(
        new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO));
    context.publishEvent(new AuthenticationSuccessEvent(authentication));
    context.publishEvent(new AuthenticationFailureBadCredentialsEvent(
        UsernamePasswordAuthenticationToken.unauthenticated("martin", "wrong"), new BadCredentialsException("bad")));
    context.publishEvent(new AuthenticationSwitchUserEvent(authentication,
        User.withUsername("other").password("secret").authorities("ROLE_USER").build()));
    context.publishEvent(new AuthorizationDeniedEvent<>(() -> authentication, "GET /api/clients",
        new AuthorizationDecision(false)));
    context.publishEvent(IamAuditEvent.dataExported(new AuditData.Export(1, 2, 3)));
    context.publishEvent(new LogoutSuccessEvent(authentication));
    context.publishEvent(new SystemAlertEvent("Something failed"));
  }

  private static Authentication authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        "martin", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
  }

  private static List<String> storedTypes(final AssertableApplicationContext context) {
    return context.getBean(AuditEventRepository.class).find(null, null, null).stream()
        .map(AuditEvent::getType)
        .toList();
  }

  private static List<String> fileTypes(final Path file) throws Exception {
    final JsonMapper mapper = JsonMapper.builder().build();
    return Files.readAllLines(file).stream()
        .filter(l -> !l.isBlank())
        .map(l -> mapper.readTree(l).get("type").asString())
        .toList();
  }

}
