<script setup>
import { computed, ref } from 'vue'

const inviteCode = ref('')
const username = ref('')
const password = ref('')
const confirm = ref('')
const submitting = ref(false)
const fieldError = ref('')
const result = ref(null)

const codeValue = computed(() => inviteCode.value.trim().toUpperCase())

function validate() {
  fieldError.value = ''
  if (!/^[A-Z0-9]{8}$/.test(codeValue.value)) {
    fieldError.value = '邀请码需为 8 位大写字母或数字，可向管理员获取。'
    return false
  }
  if (!/^[A-Za-z0-9_.-]{3,32}$/.test(username.value.trim())) {
    fieldError.value = '用户名需为 3–32 位字母、数字、点、下划线或连字符。'
    return false
  }
  if (!password.value || password.value.length < 8 || password.value.length > 128) {
    fieldError.value = '密码长度需在 8–128 位之间。'
    return false
  }
  if (password.value !== confirm.value) {
    fieldError.value = '两次输入的密码不一致。'
    return false
  }
  return true
}

async function submit() {
  if (submitting.value || result.value) return
  if (!validate()) return
  submitting.value = true
  fieldError.value = ''
  try {
    const response = await fetch('/api/v1/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username.value.trim(),
        password: password.value,
        inviteCode: codeValue.value,
      }),
    })
    const body = await response.json().catch(() => null)
    if (response.ok) {
      result.value = body ?? { username: username.value.trim() }
    } else {
      fieldError.value = body?.message || '注册失败，请稍后重试。'
    }
  } catch {
    fieldError.value = '网络异常，无法连接注册服务，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="register-shell">
    <main class="register-card">
      <header class="register-head">
        <span class="register-brand" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
          </svg>
        </span>
        <span class="register-head__text">
          <span class="register-head__title">注册新账号</span>
          <span class="register-head__sub">知源 · 凭邀请码加入企业知识平台</span>
        </span>
      </header>

      <div v-if="result" class="register-done" role="status">
        <span class="register-done__icon">
          <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9" />
            <path d="m8.2 12.2 2.6 2.6 5-5.4" />
          </svg>
        </span>
        <h1>注册成功</h1>
        <p>
          账号 <strong>{{ result.username }}</strong>
          <template v-if="result.tenantId"> 已加入租户 <code>{{ result.tenantId }}</code></template>
          ，角色为普通成员。现在可以登录并使用知识问答。
        </p>
        <div class="register-done__actions">
          <a class="btn btn--primary" href="/admin/">去登录</a>
          <a class="btn btn--outline" href="/ask/">返回知识问答</a>
        </div>
      </div>

      <form v-else class="register-form" novalidate @submit.prevent="submit">
        <div v-if="fieldError" class="alert alert--danger" role="alert">
          <span>{{ fieldError }}</span>
        </div>

        <div class="field">
          <label class="field__label" for="invite-code">邀请码</label>
          <input
            id="invite-code"
            v-model="inviteCode"
            class="field__input field__input--mono"
            type="text"
            maxlength="8"
            autocomplete="off"
            autocapitalize="characters"
            spellcheck="false"
            placeholder="例如 K7M2QX9D"
          />
          <p class="field__hint">向管理员获取当前租户邀请码，两小时自动轮换。</p>
        </div>

        <div class="field">
          <label class="field__label" for="reg-username">用户名</label>
          <input
            id="reg-username"
            v-model="username"
            class="field__input"
            type="text"
            maxlength="32"
            autocomplete="username"
            placeholder="3–32 位字母、数字、点、下划线或连字符"
          />
        </div>

        <div class="field">
          <label class="field__label" for="reg-password">密码</label>
          <input
            id="reg-password"
            v-model="password"
            class="field__input"
            type="password"
            minlength="8"
            maxlength="128"
            autocomplete="new-password"
            placeholder="8–128 位"
          />
        </div>

        <div class="field">
          <label class="field__label" for="reg-confirm">确认密码</label>
          <input
            id="reg-confirm"
            v-model="confirm"
            class="field__input"
            type="password"
            minlength="8"
            maxlength="128"
            autocomplete="new-password"
            placeholder="再次输入密码"
          />
        </div>

        <button class="btn btn--primary" type="submit" :disabled="submitting">
          <span v-if="submitting" class="btn-spinner" aria-hidden="true"></span>
          {{ submitting ? '正在创建账号…' : '注册' }}
        </button>

        <div class="register-alt">
          <span>已有账号？</span>
          <a href="/admin/">直接登录</a>
        </div>
      </form>

      <footer class="register-foot">
        <span>注册即表示你同意遵守公司信息安全与数据保密规范</span>
      </footer>
    </main>
    <p class="register-shell__meta">知源 · 企业知识平台</p>
  </div>
</template>

<style scoped>
.register-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 32px 16px;
}

.register-card {
  width: 100%;
  max-width: 440px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 24px;
  box-shadow: 0 18px 50px -22px rgba(21, 23, 28, 0.28), 0 2px 8px rgba(21, 23, 28, 0.04);
  padding: 32px 32px 20px;
}

.register-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.register-brand {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, #3d4cdc, #2a36a8);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}

.register-head__text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.register-head__title { font-size: 17px; font-weight: 680; letter-spacing: -0.01em; line-height: 1.3; }
.register-head__sub { font-size: 12.5px; color: var(--ink-3); }

.register-form { display: flex; flex-direction: column; gap: 14px; }

.field { display: flex; flex-direction: column; gap: 6px; }
.field__label { font-size: 13px; font-weight: 560; color: var(--ink-2); }

.field__input {
  width: 100%;
  min-height: 44px;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  background: var(--paper);
  color: var(--ink);
  padding: 10px 14px;
  font-size: 14px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.field__input::placeholder { color: #9a9da6; }
.field__input:focus {
  border-color: var(--accent);
  background: var(--surface);
  box-shadow: 0 0 0 3px rgba(57, 72, 212, 0.14);
}

.field__input--mono {
  font-family: var(--font-mono);
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.field__hint { margin: 0; font-size: 11.5px; color: var(--ink-3); }

.register-alt {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 13px;
  color: var(--ink-2);
}

.register-alt a {
  color: var(--accent);
  font-weight: 600;
  text-decoration: none;
  min-height: 24px;
  display: inline-flex;
  align-items: center;
}

.register-alt a:hover { text-decoration: underline; }

.register-foot {
  border-top: 1px solid var(--line);
  margin-top: 20px;
  padding-top: 14px;
  font-size: 12px;
  color: var(--ink-3);
  text-align: center;
}

.register-done { display: grid; justify-items: center; gap: 10px; text-align: center; padding: 8px 0 4px; }
.register-done__icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  border-radius: 16px;
  background: var(--success-soft);
  color: var(--success);
}
.register-done h1 { margin: 0; font-size: 19px; font-weight: 680; letter-spacing: -0.01em; }
.register-done p { margin: 0; font-size: 13px; color: var(--ink-2); line-height: 1.7; }
.register-done code { font-family: var(--font-mono); font-size: 12px; color: var(--accent-ink); background: var(--accent-soft); border-radius: 5px; padding: 1px 6px; }
.register-done__actions { display: flex; gap: 10px; margin-top: 10px; }
.register-done__actions .btn { min-width: 132px; }

.register-shell__meta { margin: 0; font-size: 12px; color: var(--ink-3); letter-spacing: 0.02em; }

@media (max-width: 480px) {
  .register-card { padding: 24px 20px 16px; border-radius: 20px; }
  .register-done__actions { flex-direction: column; width: 100%; }
  .register-done__actions .btn { width: 100%; }
}
</style>
