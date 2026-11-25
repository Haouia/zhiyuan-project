<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true displayInfo=true; section>
    <#if section = "title">
        登录 · 知源
    <#elseif section = "form">
        <form id="kc-form-login" class="login-form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
            <div class="field">
                <label for="username" class="field__label">用户名</label>
                <input tabindex="1" id="username" class="field__input" name="username" value="${(login.username!'')}" type="text" autofocus autocomplete="username" placeholder="请输入用户名" aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>" />
            </div>
            <div class="field">
                <label for="password" class="field__label">密码</label>
                <input tabindex="2" id="password" class="field__input" name="password" type="password" autocomplete="current-password" placeholder="请输入密码" aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>" />
            </div>
            <#if realm.rememberMe && !usernameHidden??>
                <label class="field-check">
                    <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>> ${msg("rememberMe")}
                </label>
            </#if>
            <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth?has_content && auth.attemptedCredentialId??>value="${auth.attemptedCredentialId}"</#if>/>
            <button tabindex="4" class="btn btn--primary" name="login" id="kc-login" type="submit">登录</button>
        </form>
        <div class="login-alt">
            <span>还没有账号？</span>
            <a href="http://127.0.0.1:8080/register/" class="login-alt__link">注册新账号</a>
        </div>
    <#elseif section = "info">
        <p class="login-info">凭管理员发放的邀请码即可自助注册，注册后即为普通成员。</p>
    </#if>
</@layout.registrationLayout>
