<#--
  Sweden Connect login page.

  Differs from the keycloak.v2 login.ftl in one respect: the identity providers are listed first, as
  buttons, and the username/password form sits behind a last button that unfolds it. The unfold is a
  native <details> element, so it needs no JavaScript and is operable from the keyboard.

  The form is open from the start when there is no identity provider to choose, and after a failed
  login, so that the error message is not hidden behind a closed button.
-->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "passkeys.ftl" as passkeys>

<#macro passwordForm autofocus>
    <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
        <#if !usernameHidden??>
            <#assign label>
                <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
            </#assign>
            <@field.input name="username" label=label error=messagesPerField.getFirstError('username','password')
                autofocus=autofocus autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" value=login.username!'' />
            <@field.password name="password" label=msg("password") error="" forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                <#if realm.rememberMe && !usernameHidden??>
                    <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                </#if>
            </@field.password>
        <#else>
            <@field.password name="password" label=msg("password") forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                <#if realm.rememberMe && !usernameHidden??>
                    <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                </#if>
            </@field.password>
        </#if>

        <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
        <@buttons.loginButton />
    </form>
</#macro>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
<!-- template: login.ftl (swedenconnect) -->

    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <#assign hasProviders = social.providers?? && social.providers?has_content>
        <#assign hasLoginError = messagesPerField.existsError('username','password')>

        <#if hasProviders>
            <ul id="kc-social-providers" class="sc-idp-list">
                <#list social.providers as p>
                    <li>
                        <a data-once-link data-disabled-class="${properties.kcFormSocialAccountListButtonDisabledClass!}"
                           id="social-${p.alias}" class="sc-btn sc-btn-filled" href="${p.loginUrl}">${p.displayName!}</a>
                    </li>
                </#list>
            </ul>
        </#if>

        <#if realm.password>
            <div id="kc-form">
                <div id="kc-form-wrapper">
                    <#if hasProviders>
                        <details id="sc-password-login" class="sc-password-login"<#if hasLoginError> open</#if>>
                            <summary class="sc-btn sc-btn-outline">${msg("scUsernamePassword")}</summary>
                            <div class="sc-password-login-body">
                                <@passwordForm autofocus=hasLoginError />
                            </div>
                        </details>
                    <#else>
                        <@passwordForm autofocus=true />
                    </#if>
                </div>
            </div>
            <@passkeys.conditionalUIData />
        </#if>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration">
                    <span>${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></span>
                </div>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
