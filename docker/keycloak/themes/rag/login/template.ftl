<#macro registrationLayout displayMessage=false displayRequiredFields=false displayInfo=false displayWide=false showAnotherWayIfPresent=true>
<!DOCTYPE html>
<html lang="zh-CN" xmlns="http://www.w3.org/1999/xhtml">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="robots" content="noindex, nofollow">
    <title><#nested "title"></title>
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
</head>
<body>
    <div class="login-shell">
        <div class="login-card">
            <header class="login-head">
                <span class="brand-mark" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
                    </svg>
                </span>
                <span class="login-head__text">
                    <span class="login-head__title">知源</span>
                    <span class="login-head__sub">企业级 RAG 知识平台 · 单点登录</span>
                </span>
            </header>
            <#if displayMessage && message?has_content && message.summary?has_content>
                <div class="alert alert--${message.type}">
                    <span>${kcSanitize(message.summary)?no_esc}</span>
                </div>
            </#if>
            <main class="login-main">
                <#nested "form">
            </main>
            <#if displayInfo>
                <#nested "info">
            </#if>
            <footer class="login-foot">
                <span>登录即表示你同意遵守公司信息安全与数据保密规范</span>
            </footer>
        </div>
        <p class="login-shell__meta">知源 · ${realm.displayName!""}</p>
    </div>
</body>
</html>
</#macro>
