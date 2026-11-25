<script setup>
import { computed, onMounted, ref } from 'vue'
import Icon from './Icon.vue'
import { formatDateTime } from '../labels'

const props = defineProps({
  api: { type: Function, required: true },
  tenant: { type: String, default: '' },
})
const emit = defineEmits(['notify'])

const directory = ref(null)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const roleFilter = ref('ALL')
const changing = ref('')

const users = computed(() => directory.value?.users ?? [])
const counts = computed(() => directory.value?.counts ?? {})
const roles = computed(() => directory.value?.roles ?? [])
const available = computed(() => directory.value?.directory?.available !== false)
const directoryMessage = computed(
  () => directory.value?.directory?.message || '身份服务暂不可用，用户目录无法读取。',
)

const ROLE_META = {
  ADMIN: { label: '管理员', tone: 'accent', hint: '可管理文档、评测、监控与用户权限', icon: 'shield' },
  EMPLOYEE: { label: '成员', tone: 'neutral', hint: '可检索知识库并核对引用原文', icon: 'user' },
}

const filters = computed(() => {
  const items = [{ value: 'ALL', label: '全部', count: users.value.length }]
  for (const role of roles.value) {
    items.push({
      value: role,
      label: ROLE_META[role]?.label ?? role,
      count: users.value.filter((user) => user.roles?.includes(role)).length,
    })
  }
  return items
})

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return users.value.filter((user) => {
    const matchesRole = roleFilter.value === 'ALL' || user.roles?.includes(roleFilter.value)
    if (!matchesRole) return false
    if (!text) return true
    return [user.username, user.email, user.tenantId].some((value) =>
      String(value ?? '').toLowerCase().includes(text),
    )
  })
})

const tenantCount = computed(() => (counts.value.tenants ?? []).length)

function roleLabel(role) {
  return ROLE_META[role]?.label ?? role
}

function roleTone(role) {
  return ROLE_META[role]?.tone ?? 'neutral'
}

function initial(username) {
  return String(username || '?').slice(0, 1).toUpperCase()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    directory.value = await props.api('/access')
  } catch (failure) {
    error.value = failure.message
    directory.value = null
  } finally {
    loading.value = false
  }
}

async function refresh() {
  await load()
  if (!error.value) {
    if (available.value) emit('notify', { tone: 'info', title: '已刷新', message: '用户目录已与 Keycloak 同步。' })
    else emit('notify', { tone: 'danger', title: '目录不可用', message: directoryMessage.value })
  }
}

async function changeRole(user, role) {
  if (changing.value) return
  const action = role === 'ADMIN' ? 'promote' : 'demote'
  const label = role === 'ADMIN' ? '擢升为管理员' : '调整为普通成员'
  const ok = window.confirm(`确定将 ${user.username} ${label}？权限变更将在该用户重新登录后生效。`)
  if (!ok) return
  changing.value = user.username
  try {
    await props.api(`/users/${encodeURIComponent(user.username)}/${action}`, { method: 'POST' })
    emit('notify', { tone: 'success', title: '角色已更新', message: `${user.username} 已${label}。` })
    await load()
  } catch (failure) {
    emit('notify', { tone: 'danger', title: '操作失败', message: failure.message })
  } finally {
    changing.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="access">
    <section class="panel access-summary">
      <header class="access-summary__head">
        <div>
          <p class="panel__eyebrow">权限目录</p>
          <h2 class="panel__title">账号与角色 <span class="count-chip">{{ counts.total ?? 0 }}</span></h2>
        </div>
        <button class="btn btn--ghost btn--sm" type="button" :disabled="loading" @click="refresh">
          <Icon name="refresh" :size="15" :class="{ spinning: loading }" />
          同步目录
        </button>
      </header>

      <div class="access-stats">
        <article class="access-stat">
          <span class="access-stat__label">账号总数</span>
          <strong class="access-stat__value">{{ counts.total ?? 0 }}</strong>
          <span class="access-stat__hint">已绑定租户</span>
        </article>
        <article class="access-stat">
          <span class="access-stat__label">管理员</span>
          <strong class="access-stat__value">{{ counts.admins ?? 0 }}</strong>
          <span class="access-stat__hint">ADMIN 角色</span>
        </article>
        <article class="access-stat">
          <span class="access-stat__label">普通成员</span>
          <strong class="access-stat__value">{{ counts.employees ?? 0 }}</strong>
          <span class="access-stat__hint">EMPLOYEE 角色</span>
        </article>
        <article class="access-stat">
          <span class="access-stat__label">租户数</span>
          <strong class="access-stat__value">{{ tenantCount }}</strong>
          <span class="access-stat__hint">{{ (counts.tenants ?? []).join(' · ') || '—' }}</span>
        </article>
      </div>

      <p v-if="!available && !loading" class="alert alert--warning" role="alert">
        <Icon name="alert-triangle" :size="16" />
        <span>{{ directoryMessage }}</span>
        <button class="btn btn--sm btn--outline" type="button" @click="load">重试</button>
      </p>
      <p v-else-if="directory?.directory?.available" class="access-sync">
        <Icon name="check-circle" :size="15" />
        目录来自 Keycloak realm <code>{{ directory.realm }}</code>，角色变更后 30 秒内自动同步。
      </p>
    </section>

    <section class="access-roles">
      <div v-for="role in roles" :key="role" class="role-card" :class="'role-card--' + roleTone(role)">
        <span class="role-card__icon"><Icon :name="ROLE_META[role]?.icon ?? 'user'" :size="18" /></span>
        <div class="role-card__body">
          <div class="role-card__title">
            <strong>{{ ROLE_META[role]?.label ?? role }}</strong>
            <code class="role-card__code">{{ role }}</code>
            <span class="role-card__count">{{ counts[role === 'ADMIN' ? 'admins' : 'employees'] ?? 0 }} 个账号</span>
          </div>
          <p class="role-card__hint">{{ ROLE_META[role]?.hint ?? '平台角色' }}</p>
          <ul class="role-card__caps">
            <template v-if="role === 'ADMIN'">
              <li>文档上传 / 版本发布</li>
              <li>RAGAS 评测与监控</li>
              <li>用量与权限管理</li>
            </template>
            <template v-else>
              <li>知识库问答</li>
              <li>引用原文核对</li>
              <li>个人会话追溯</li>
            </template>
          </ul>
        </div>
        <button class="btn btn--ghost btn--sm role-card__link" type="button" @click="roleFilter = role">
          查看账号
          <Icon name="arrow-right" :size="14" />
        </button>
      </div>
    </section>

    <section class="panel access-table-panel">
      <header class="panel__head">
        <div>
          <p class="panel__eyebrow">账号列表</p>
          <h2 class="panel__title">租户成员</h2>
        </div>
        <label class="access-search">
          <Icon name="search" :size="15" />
          <input v-model="keyword" type="search" placeholder="搜索账号、邮箱或租户…" aria-label="搜索账号" />
        </label>
      </header>

      <div class="access-filters" role="group" aria-label="按角色筛选">
        <button
          v-for="filter in filters"
          :key="filter.value"
          class="filter-chip"
          :class="{ 'filter-chip--active': roleFilter === filter.value }"
          type="button"
          @click="roleFilter = filter.value"
        >
          {{ filter.label }}
          <span class="filter-chip__count">{{ filter.count }}</span>
        </button>
      </div>

      <div v-if="error" class="alert alert--danger" role="alert">
        <Icon name="alert-circle" :size="16" />
        <span>{{ error }}</span>
        <button class="btn btn--sm btn--outline" type="button" @click="load">重试</button>
      </div>

      <div v-else-if="loading && !users.length" class="table-skeleton" aria-hidden="true">
        <span v-for="row in 5" :key="row" class="skeleton skeleton--row" />
      </div>

      <div v-else-if="!filtered.length" class="access-empty">
        <span class="access-empty__icon"><Icon name="users" :size="22" /></span>
        <p>{{ users.length ? '没有符合筛选条件的账号。' : '目录中还没有绑定租户的账号。' }}</p>
        <button v-if="users.length" class="btn btn--outline btn--sm" type="button" @click="keyword = ''; roleFilter = 'ALL'">
          清除筛选
        </button>
      </div>

      <div v-else class="access-table-wrap">
        <table class="access-table">
          <thead>
            <tr>
              <th scope="col">账号</th>
              <th scope="col">租户</th>
              <th scope="col">角色</th>
              <th scope="col">状态</th>
              <th scope="col">创建时间</th>
              <th v-if="tenant" scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in filtered" :key="user.username">
              <td>
                <div class="account">
                  <span class="account__avatar">{{ initial(user.username) }}</span>
                  <div class="account__text">
                    <strong>{{ user.username }}</strong>
                    <span>{{ user.email || '未设置邮箱' }}</span>
                  </div>
                </div>
              </td>
              <td><span class="chip chip--mono">{{ user.tenantId }}</span></td>
              <td>
                <div class="account__roles">
                  <span
                    v-for="role in user.roles"
                    :key="role"
                    class="tag tag--mono"
                    :class="'tag--' + roleTone(role)"
                    :title="roleLabel(role)"
                  >
                    {{ role }}
                  </span>
                  <span v-if="!user.roles?.length" class="muted">无平台角色</span>
                </div>
              </td>
              <td>
                <span class="tag" :class="user.enabled ? 'tag--success' : 'tag--warning'">
                  {{ user.enabled ? '已启用' : '已停用' }}
                </span>
              </td>
              <td class="muted num">{{ formatDateTime(user.createdAt) }}</td>
              <td v-if="tenant" class="access-ops">
                <template v-if="user.tenantId === tenant">
                  <button
                    v-if="!user.roles?.includes('ADMIN')"
                    class="btn btn--outline btn--sm"
                    type="button"
                    :disabled="changing === user.username"
                    @click="changeRole(user, 'ADMIN')"
                  >
                    <Icon name="crown" :size="14" />
                    设为管理员
                  </button>
                  <button
                    v-else
                    class="btn btn--ghost btn--sm"
                    type="button"
                    :disabled="changing === user.username"
                    @click="changeRole(user, 'EMPLOYEE')"
                  >
                    <Icon name="user" :size="14" />
                    设为成员
                  </button>
                </template>
                <span v-else class="muted">其他租户</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="access-table__foot">
        <span class="muted">显示 {{ filtered.length }} / {{ users.length }} 个账号</span>
        <span class="muted">权限变更将在该用户重新登录后生效</span>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.access { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px; min-width: 0; }
.access > * { min-width: 0; }
.panel__eyebrow { margin: 0 0 4px; font-size: 11px; font-weight: 650; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink-3); }
.panel__title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 17px; font-weight: 650; letter-spacing: -0.015em; }
.count-chip {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--paper-2);
  font-size: 11px;
  color: var(--ink-2);
}
.access-summary { display: grid; gap: 16px; padding: 22px 24px; }
.access-summary__head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; }
.access-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--line); border-radius: 14px; overflow: hidden; }
.access-stat { display: grid; gap: 3px; padding: 14px 17px; border-right: 1px solid var(--line); background: var(--paper); }
.access-stat:last-child { border-right: 0; }
.access-stat__label { font-size: 11.5px; color: var(--ink-3); }
.access-stat__value { font-size: 23px; font-weight: 680; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.access-stat__hint { font-size: 10.5px; color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.access-sync { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 11.5px; color: var(--ink-2); }
.access-sync code { font-family: var(--font-mono); font-size: 11px; color: var(--accent-ink); }
.access-roles { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.role-card {
  position: relative;
  display: flex;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}
.role-card--accent { border-color: color-mix(in srgb, var(--accent) 26%, var(--line)); background: linear-gradient(140deg, var(--accent-soft), var(--surface) 58%); }
.role-card__icon { display: grid; place-items: center; width: 38px; height: 38px; flex: none; border-radius: 11px; background: var(--paper-2); color: var(--ink-2); }
.role-card--accent .role-card__icon { background: var(--accent); color: #fff; }
.role-card__body { display: grid; gap: 5px; min-width: 0; }
.role-card__title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.role-card__title strong { font-size: 14.5px; font-weight: 650; }
.role-card__code { padding: 1px 6px; border-radius: 5px; background: rgba(21, 23, 28, 0.06); font-family: var(--font-mono); font-size: 10px; color: var(--ink-2); }
.role-card__count { font-size: 11px; color: var(--ink-3); }
.role-card__hint { margin: 0; font-size: 12px; color: var(--ink-2); }
.role-card__caps { display: flex; gap: 12px; margin: 3px 0 0; padding: 0; list-style: none; flex-wrap: wrap; }
.role-card__caps li { position: relative; padding-left: 12px; font-size: 11px; color: var(--ink-3); }
.role-card__caps li::before { content: ''; position: absolute; left: 0; top: 50%; width: 4px; height: 4px; border-radius: 50%; background: var(--accent); transform: translateY(-50%); opacity: 0.6; }
.role-card__link { position: absolute; right: 14px; bottom: 12px; }
.access-table-panel { padding: 22px 24px 16px; }
.panel__head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-bottom: 12px; flex-wrap: wrap; }
.access-search {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-3);
}
.access-search:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.access-search input { width: 208px; border: 0; outline: 0; background: transparent; font: inherit; font-size: 12.5px; color: var(--ink); }
.access-filters { display: flex; gap: 7px; margin-bottom: 12px; flex-wrap: wrap; }
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--surface);
  font: inherit;
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color 140ms ease, background 140ms ease, color 140ms ease;
}
.filter-chip:hover { border-color: var(--line-strong); }
.filter-chip--active { border-color: var(--accent); background: var(--accent-soft); color: var(--accent-ink); font-weight: 600; }
.filter-chip__count { font-size: 10.5px; color: var(--ink-3); }
.filter-chip--active .filter-chip__count { color: var(--accent-ink); }
.access-table-wrap { overflow-x: auto; }
.access-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.access-table th {
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-align: left;
  color: var(--ink-3);
  white-space: nowrap;
}
.access-table td { padding: 11px 12px; border-bottom: 1px solid var(--line); vertical-align: middle; }
.access-table tbody tr:last-child td { border-bottom: 0; }
.access-table tbody tr { transition: background 140ms ease; }
.access-table tbody tr:hover { background: var(--paper); }
.account { display: flex; align-items: center; gap: 10px; min-width: 0; }
.account__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 650;
  color: var(--accent-ink);
}
.account__text { display: grid; gap: 1px; min-width: 0; }
.account__text strong { font-size: 13px; font-weight: 620; }
.account__text span { font-size: 11px; color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account__roles { display: flex; gap: 5px; flex-wrap: wrap; }
.access-table__foot { display: flex; justify-content: space-between; gap: 12px; padding-top: 12px; font-size: 11.5px; flex-wrap: wrap; }
.access-empty { display: grid; justify-items: center; gap: 10px; padding: 38px 0; color: var(--ink-2); }
.access-empty__icon { display: grid; place-items: center; width: 46px; height: 46px; border-radius: 14px; background: var(--paper-2); color: var(--accent); }
.num { font-variant-numeric: tabular-nums; }
@media (max-width: 1024px) {
  .access-roles { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 860px) {
  .access-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .access-stat:nth-child(2) { border-right: 0; }
  .access-stat:nth-child(-n + 2) { border-bottom: 1px solid var(--line); }
}
@media (max-width: 640px) {
  .access-summary, .access-table-panel { padding: 18px 16px; }
  .access-search input { width: 130px; }
  .access-table th:nth-child(5), .access-table td:nth-child(5),
  .access-table th:nth-child(6), .access-table td:nth-child(6) { display: none; }
  .role-card__link { position: static; align-self: flex-end; }
}
.access-ops { white-space: nowrap; }
.access-ops .btn + .btn { margin-left: 6px; }
</style>
