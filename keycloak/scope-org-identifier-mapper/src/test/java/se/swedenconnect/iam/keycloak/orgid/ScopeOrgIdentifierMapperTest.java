/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.keycloak.orgid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.IDToken;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScopeOrgIdentifierMapper}.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
class ScopeOrgIdentifierMapperTest {

  private ScopeOrgIdentifierMapper mapper;

  @Mock
  private KeycloakSession keycloakSession;

  @Mock
  private UserSessionModel userSession;

  @Mock
  private ProtocolMapperModel mappingModel;

  @Mock
  private ClientSessionContext clientSessionCtx;

  @BeforeEach
  void setUp() {
    mapper = new ScopeOrgIdentifierMapper();
  }

  @Test
  void setClaim_matchingScope_setsOrgIdentifier() {
    when(clientSessionCtx.getScopeString(false)).thenReturn("5590026042:walletreg:write");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertEquals("5590026042", token.getOtherClaims().get(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }

  @Test
  void setClaim_multipleScopes_firstMatchWins() {
    when(clientSessionCtx.getScopeString(false)).thenReturn("openid 5590026042:walletreg:write");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertEquals("5590026042", token.getOtherClaims().get(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }

  @Test
  void setClaim_multipleOrgScopes_firstOrgWins() {
    when(clientSessionCtx.getScopeString(false)).thenReturn("5590026042:walletreg:read 5561234567:demo:write");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertEquals("5590026042", token.getOtherClaims().get(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }

  @Test
  void setClaim_noMatchingScope_claimAbsent() {
    when(clientSessionCtx.getScopeString(false)).thenReturn("openid profile");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertFalse(token.getOtherClaims().containsKey(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }

  @Test
  void setClaim_nullScope_claimAbsent() {
    when(clientSessionCtx.getScopeString(false)).thenReturn(null);

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertFalse(token.getOtherClaims().containsKey(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }

  @Test
  void setClaim_blankScope_claimAbsent() {
    when(clientSessionCtx.getScopeString(false)).thenReturn("");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertFalse(token.getOtherClaims().containsKey(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }

  @Test
  void setClaim_nonTenDigitOrg_noMatch() {
    when(clientSessionCtx.getScopeString(false)).thenReturn("ABC:walletreg:write");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertFalse(token.getOtherClaims().containsKey(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }

  @Test
  void setClaim_shortOrg_noMatch() {
    when(clientSessionCtx.getScopeString(false)).thenReturn("12345:walletreg:write");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertFalse(token.getOtherClaims().containsKey(ScopeOrgIdentifierMapper.CLAIM_NAME));
  }
}
