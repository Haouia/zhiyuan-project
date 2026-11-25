<script setup>
import BrandMark from './BrandMark.vue'
import Icon from './Icon.vue'

defineProps({
  booting: { type: Boolean, default: false },
  identity: { type: Object, default: null },
  accessError: { type: String, default: '' },
  loggingIn: { type: Boolean, default: false },
  mode: { type: String, default: 'admin' },
  signIn: { type: Function, required: true },
  signOut: { type: Function, required: true },
})
</script>

<template>
  <div class="auth-shell">
    <div class="auth-shell__glow" aria-hidden="true" />
    <header class="auth-top">
      <a class="auth-brand" href="/admin/">
        <BrandMark :size="34" />
        <span class="auth-brand__name">知源</span>
        <span class="auth-brand__meta">Enterprise RAG</span>
      </a>
      <span class="auth-top__hint">
        <Icon name="lock" :size="13" />
        单点登录
      </span>
    </header>

    <main class="auth-main">
      <section v-if="booting" class="auth-card auth-card--boot" role="status" aria-live="polite">
        <BrandMark :size="46" />
        <span class="spinner" aria-hidden="true" />
        <h1>正在连接工作台</h1>
        <p>正在校验企业登录状态，请稍候…</p>
      </section>

      <section v-else-if="!identity" class="auth-card">
        <div class="auth-card__mark">
          <BrandMark :size="42" />
        </div>
        <p class="eyebrow">{{ mode === 'ask' ? '知识问答' : '管理控制台' }}</p>
        <h1>{{ mode === 'ask' ? '让企业知识触手可及' : '管理企业知识资产' }}</h1>
        <p class="auth-card__lead">
          {{ mode === 'ask'
            ? '基于本租户已发布的资料回答问题，并附上可核对的原文引用。'
            : '上传、解析并发布文档，掌握每个版本的处理与生效状态。' }}
        </p>
        <p v-if="accessError" class="alert alert--danger" role="alert">{{ accessError }}</p>
        <button class="btn btn--primary btn--lg auth-card__cta" type="button" :disabled="loggingIn" @click="signIn">
          <span>{{ loggingIn ? '正在打开登录页面…' : '使用企业单点登录' }}</span>
          <Icon v-if="!loggingIn" name="arrow-right" :size="17" />
        </button>
        <p class="auth-card__hint">
          <Icon name="shield" :size="14" />
          由 Keycloak 提供统一身份认证
        </p>
      </section>

      <section v-else class="auth-card">
        <div class="auth-card__badge">
          <Icon name="shield" :size="26" />
        </div>
        <p class="eyebrow">访问受限</p>
        <h1>需要知识管理员权限</h1>
        <p class="auth-card__lead">
          当前账户已登录<template v-if="identity.tenantId">（{{ identity.tenantId }}）</template>，但此工作台仅向
          <strong>ADMIN</strong> 角色开放。你仍然可以使用知识问答。
        </p>
        <p v-if="accessError" class="alert alert--danger" role="alert">{{ accessError }}</p>
        <div class="auth-card__actions">
          <a class="btn btn--primary" href="/ask/">
            <span>前往知识问答</span>
            <Icon name="arrow-right" :size="16" />
          </a>
          <button class="btn btn--outline" type="button" @click="signIn">切换账户</button>
        </div>
        <button class="btn btn--quiet auth-card__signout" type="button" @click="signOut">退出登录</button>
      </section>
    </main>

    <footer class="auth-foot">知源 · 企业知识平台</footer>
  </div>
</template>

<style scoped>
.auth-shell {
  position: relative;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--paper);
}
.auth-shell__glow {
  position: absolute;
  inset: -20% -10% auto;
  height: 70vh;
  background:
    radial-gradient(58% 60% at 22% 0%, rgba(76, 91, 234, 0.16), transparent 70%),
    radial-gradient(46% 55% at 88% 8%, rgba(14, 115, 85, 0.1), transparent 72%);
  pointer-events: none;
}
.auth-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px clamp(20px, 4vw, 44px);
}
.auth-brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: var(--ink);
  text-decoration: none;
}
.auth-brand__name { font-size: 16px; font-weight: 680; letter-spacing: -0.01em; }
.auth-brand__meta {
  margin-left: 2px;
  padding-left: 12px;
  border-left: 1px solid var(--line-strong);
  font-size: 11px;
  font-weight: 550;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.auth-top__hint {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-3);
}
.auth-main {
  position: relative;
  z-index: 1;
  flex: 1;
  display: grid;
  place-items: center;
  padding: 32px clamp(20px, 4vw, 44px) 56px;
}
.auth-card {
  width: min(460px, 100%);
  padding: 40px 40px 34px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 24px;
  box-shadow: 0 40px 90px -60px rgba(16, 18, 23, 0.45);
  animation: auth-rise 500ms cubic-bezier(0.2, 0.75, 0.25, 1) both;
}
.auth-card--boot {
  display: grid;
  justify-items: center;
  gap: 14px;
  text-align: center;
  padding-block: 48px;
}
.auth-card__mark { margin-bottom: 22px; }
.auth-card__badge {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  margin-bottom: 22px;
  border-radius: 16px;
  background: var(--accent-soft);
  color: var(--accent-ink);
}
.auth-card h1 {
  margin: 6px 0 0;
  font-size: clamp(24px, 3vw, 30px);
  line-height: 1.25;
  letter-spacing: -0.025em;
  font-weight: 680;
}
.auth-card--boot h1 { margin-top: 4px; font-size: 21px; }
.auth-card__lead {
  margin: 12px 0 0;
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.75;
}
.auth-card--boot p { color: var(--ink-3); font-size: 13px; }
.auth-card .alert { margin: 18px 0 0; }
.auth-card__cta { width: 100%; margin-top: 26px; }
.auth-card__actions {
  display: grid;
  gap: 10px;
  margin-top: 26px;
}
.auth-card__signout {
  margin-top: 12px;
  width: 100%;
  color: var(--ink-3);
}
.auth-card__hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 18px 0 0;
  font-size: 12px;
  color: var(--ink-3);
}
.auth-foot {
  position: relative;
  z-index: 1;
  padding: 0 24px 26px;
  text-align: center;
  font-size: 11.5px;
  letter-spacing: 0.02em;
  color: var(--ink-3);
}
.spinner {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 2.5px solid var(--line);
  border-top-color: var(--accent);
  animation: spin 800ms linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes auth-rise {
  from { opacity: 0; transform: translateY(14px); }
  to { opacity: 1; transform: translateY(0); }
}
@media (max-width: 520px) {
  .auth-top { padding: 18px 18px; }
  .auth-brand__meta { display: none; }
  .auth-main { padding: 20px 16px 40px; }
  .auth-card { padding: 30px 22px 26px; border-radius: 20px; }
}
</style>
