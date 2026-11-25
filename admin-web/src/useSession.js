import { ref } from 'vue'
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

export function useSession(callback) {
  const booting = ref(true)
  const loggingIn = ref(false)
  const identity = ref(null)
  const accessError = ref('')
  const redirect = window.location.origin + callback
  let manager
  let user
  let refresh

  async function api(path, options = {}) {
    const { raw = false, ...request } = options
    if (user?.expires_in != null && user.expires_in <= 10) {
      refresh ??= manager.signinSilent().then((renewed) => {
        user = renewed
      }).catch(async () => {
        await manager.removeUser()
        user = null
      }).finally(() => {
        refresh = null
      })
      await refresh
    }
    const headers = new Headers(request.headers)
    if (user) headers.set('Authorization', 'Bearer ' + user.access_token)
    const response = await fetch('/api/v1' + path, { ...request, headers })
    if (response.status === 401) {
      await manager.removeUser()
      user = null
      identity.value = null
      accessError.value = '登录已过期，请重新登录。'
    }
    if (!response.ok) {
      const data = await response.json().catch(() => null)
      const error = new Error(data?.message ?? '请求失败（HTTP ' + response.status + '）')
      error.status = response.status
      error.traceId = data?.traceId
      throw error
    }
    if (raw) return response
    return response.json().catch(() => null)
  }

  async function initialize() {
    const query = new URLSearchParams(window.location.search)
    try {
      const response = await fetch('/admin/config.json')
      if (!response.ok) throw new Error('无法加载登录配置。')
      const configuration = await response.json()
      const store = new WebStorageStateStore({ store: window.sessionStorage })
      manager = new UserManager({
        authority: configuration.issuer,
        client_id: configuration.clientId,
        redirect_uri: redirect,
        post_logout_redirect_uri: redirect,
        scope: 'openid',
        response_type: 'code',
        stateStore: store,
        userStore: store,
        automaticSilentRenew: false,
        loadUserInfo: false,
      })
      if (query.has('code') || query.has('error')) user = await manager.signinRedirectCallback()
      else user = await manager.getUser()
      if (user) identity.value = await api('/identity')
    } catch (error) {
      if (!accessError.value) accessError.value = query.has('code') ? '登录失败，请重新登录。' : error.message
    } finally {
      if (window.location.search) window.history.replaceState({}, '', redirect)
      booting.value = false
    }
  }

  async function signIn() {
    if (!manager) return window.location.reload()
    loggingIn.value = true
    try {
      await manager.signinRedirect(identity.value ? { prompt: 'login' } : {})
    } catch {
      accessError.value = '暂时无法打开登录页面，请稍后重试。'
      loggingIn.value = false
    }
  }

  async function signOut() {
    try {
      await manager.signoutRedirect()
    } catch {
      await manager.removeUser()
      user = null
      identity.value = null
      accessError.value = '本地会话已退出。'
    }
  }

  return { booting, loggingIn, identity, accessError, initialize, api, signIn, signOut }
}
