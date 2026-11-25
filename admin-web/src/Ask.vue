<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useSession } from './useSession.js'
import AnswerCard from './components/AnswerCard.vue'
import AuthPanel from './components/AuthPanel.vue'
import BrandMark from './components/BrandMark.vue'
import Icon from './components/Icon.vue'
import SlideOver from './components/SlideOver.vue'
import SourceDrawer from './components/SourceDrawer.vue'

const { booting, loggingIn, identity, accessError, initialize, api, signIn, signOut } = useSession(
  window.location.pathname.startsWith('/ask') ? '/ask/' : '/admin/',
)

// 会话上文只保留最近 3 轮：既能让「那差旅呢」「它适用谁」这类追问被理解，又不会让提示词被历史淹没。
const HISTORY_TURNS = 3
const HISTORY_ANSWER_LIMIT = 1200

const question = ref('')
const thread = ref([])
const endMarker = ref(null)
const heroInput = ref(null)
const dockInput = ref(null)
const sourceOpen = ref(false)
const activeCitation = ref(null)
let entrySeq = 0

const suggestions = [
  '入职满一年能休几天年假？',
  '出差住宿标准是多少？',
  '数据分为哪些安全等级？',
]

const pending = computed(() => thread.value.some((entry) => entry.loading))
const chatting = computed(() => thread.value.length > 0)
const canAdmin = computed(() => Array.isArray(identity.value?.roles) && identity.value.roles.includes('ADMIN'))
const sourceTitle = computed(() => activeCitation.value?.title ?? '引用原文')
const placeholder = computed(() =>
  chatting.value ? '继续追问，例如：它从什么时候开始执行？' : '例如：入职满一年能休几天年假？',
)

function activeInput() {
  return chatting.value ? dockInput.value : heroInput.value
}

function scrollToEnd() {
  nextTick(() => endMarker.value?.scrollIntoView({ behavior: 'smooth', block: 'end' }))
}

function autoGrow() {
  const element = activeInput()
  if (!element) return
  element.style.height = 'auto'
  element.style.height = Math.min(element.scrollHeight, chatting.value ? 140 : 168) + 'px'
}

function resetComposer() {
  const element = activeInput()
  if (!element) return
  element.style.height = 'auto'
}

function focusComposer() {
  const element = activeInput()
  if (!element) return
  // 用户正在输入时不抢焦点
  const active = document.activeElement
  if (active && active !== document.body && active !== element) return
  element.focus()
}

function useSuggestion(text) {
  question.value = text
  nextTick(() => {
    activeInput()?.focus()
    autoGrow()
  })
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

/** 只把已经答完的轮次递给后端，用于指代消解；答案超长时截断。 */
function historyPayload() {
  return thread.value
    .filter((entry) => !entry.loading && !entry.error && entry.answer
      && (entry.status === 'ANSWERED' || entry.status === 'INSUFFICIENT_EVIDENCE'))
    .slice(-HISTORY_TURNS)
    .map((entry) => ({ question: entry.question, answer: entry.answer.slice(0, HISTORY_ANSWER_LIMIT) }))
}

async function ask(entry, history) {
  entry.loading = true
  entry.error = ''
  scrollToEnd()
  try {
    const result = await api('/questions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: entry.question, history }),
    })
    entry.answer = result.answer ?? ''
    entry.citations = result.citations ?? []
    entry.status = result.status ?? ''
    entry.traceId = result.traceId ?? ''
  } catch (error) {
    entry.error = error.message || '问答请求未完成，请稍后重试。'
    entry.traceId = error.traceId || entry.traceId
  } finally {
    entry.loading = false
    scrollToEnd()
    nextTick(focusComposer)
  }
}

function submit() {
  const text = question.value.trim()
  if (!text || pending.value) return
  const history = historyPayload()
  // reactive() keeps mutations in ask() observable through the thread ref.
  const entry = reactive({
    id: ++entrySeq,
    question: text,
    answer: '',
    citations: [],
    status: '',
    traceId: '',
    loading: true,
    error: '',
  })
  thread.value.push(entry)
  question.value = ''
  nextTick(() => {
    autoGrow()
    scrollToEnd()
  })
  void ask(entry, history)
}

function retry(entry) {
  if (pending.value) return
  // 重试时沿用该轮之前的历史，保持上下文一致
  const index = thread.value.indexOf(entry)
  const history = thread.value
    .slice(0, Math.max(0, index))
    .filter((item) => !item.error && item.answer && (item.status === 'ANSWERED' || item.status === 'INSUFFICIENT_EVIDENCE'))
    .slice(-HISTORY_TURNS)
    .map((item) => ({ question: item.question, answer: item.answer.slice(0, HISTORY_ANSWER_LIMIT) }))
  void ask(entry, history)
}

function openSource(citation) {
  activeCitation.value = citation
  sourceOpen.value = true
}

function newConversation() {
  thread.value = []
  sourceOpen.value = false
  activeCitation.value = null
  question.value = ''
  nextTick(() => {
    resetComposer()
    heroInput.value?.focus()
  })
}

watch(identity, (account) => {
  if (!account) {
    thread.value = []
    sourceOpen.value = false
    activeCitation.value = null
  }
})

onMounted(() => {
  document.title = '知识问答 · 知源'
  void initialize()
})
</script>

<template>
  <a class="skip-link" href="#ask-main">跳到主要内容</a>

  <AuthPanel
    v-if="booting || !identity"
    :booting="booting"
    :identity="identity"
    :access-error="accessError"
    :logging-in="loggingIn"
    mode="ask"
    :sign-in="signIn"
    :sign-out="signOut"
  />

  <div v-else class="ask-page" :class="{ 'ask-page--chat': chatting }">
    <header class="ask-nav">
      <a class="ask-nav__brand" href="/ask/">
        <BrandMark :size="34" />
        <span class="ask-nav__name">知源</span>
        <span class="ask-nav__meta">知识问答</span>
      </a>
      <div class="ask-nav__actions">
        <span class="tenant-chip" :title="identity.tenantId">
          <Icon name="database" :size="13" />
          {{ identity.tenantId }}
        </span>
        <button v-if="chatting" class="btn btn--ghost btn--sm" type="button" @click="newConversation">
          <Icon name="plus" :size="15" />
          新对话
        </button>
        <a v-if="canAdmin" class="btn btn--outline btn--sm" href="/admin/">
          <Icon name="book" :size="15" />
          文档管理
        </a>
        <button class="btn btn--quiet btn--sm" type="button" @click="signOut">
          <Icon name="logout" :size="15" />
          退出
        </button>
      </div>
    </header>

    <main id="ask-main" class="ask-main">
      <section class="ask-hero" :class="{ 'ask-hero--compact': chatting }">
        <p class="eyebrow">知识问答 · RAG</p>
        <h1>向企业知识库提问</h1>
        <p class="ask-hero__lead">
          答案只来自本租户已发布的资料，并附上可以核对的原文引用，让每个结论都有出处。追问会自动带上会话上文。
        </p>
      </section>

      <form v-if="!chatting" class="composer" aria-label="提问" @submit.prevent="submit">
        <label class="visually-hidden" for="question">你的问题</label>
        <textarea
          id="question"
          ref="heroInput"
          v-model="question"
          class="composer__input"
          rows="2"
          maxlength="1000"
          :placeholder="placeholder"
          @input="autoGrow"
          @keydown="onKeydown"
        />
        <div class="composer__bar">
          <span class="composer__hint">
            <kbd>Enter</kbd> 提问 · <kbd>Shift</kbd><span class="composer__plus">+</span><kbd>Enter</kbd> 换行
          </span>
          <button class="btn btn--primary" type="submit" :disabled="!question.trim() || pending">
            <span v-if="pending" class="btn-spinner" aria-hidden="true" />
            <Icon v-else name="send" :size="16" />
            {{ pending ? '正在生成…' : '提问' }}
          </button>
        </div>
      </form>

      <div v-if="!chatting" class="ask-hints">
        <div class="suggestion-block">
          <p class="suggestion-block__label">试试这些问题</p>
          <div class="suggestion-list">
            <button
              v-for="item in suggestions"
              :key="item"
              class="suggestion-chip"
              type="button"
              @click="useSuggestion(item)"
            >
              <Icon name="sparkles" :size="14" />
              {{ item }}
            </button>
          </div>
        </div>
        <ul class="trust-row">
          <li><Icon name="lock" :size="14" /> 仅检索本租户资料</li>
          <li><Icon name="check-circle" :size="14" /> 每个结论附带引用</li>
          <li><Icon name="external" :size="14" /> 可回溯原文核对</li>
        </ul>
      </div>

      <section v-else class="thread" aria-label="对话记录" aria-live="polite">
        <AnswerCard
          v-for="entry in thread"
          :key="entry.id"
          :entry="entry"
          @cite="openSource"
          @retry="retry"
        />
        <div ref="endMarker" class="thread__end" />
        <p class="thread__note">
          以上 {{ thread.length }} 轮问答会作为会话上文用于理解追问，答案依据仍然只来自知识库原文。
        </p>
      </section>
    </main>

    <div v-if="chatting" class="ask-dock">
      <div class="ask-dock__inner">
        <form class="composer composer--dock" aria-label="继续提问" @submit.prevent="submit">
          <label class="visually-hidden" for="question-dock">继续追问</label>
          <textarea
            id="question-dock"
            ref="dockInput"
            v-model="question"
            class="composer__input"
            rows="1"
            maxlength="1000"
            :placeholder="placeholder"
            @input="autoGrow"
            @keydown="onKeydown"
          />
          <div class="composer__bar">
            <span class="composer__hint">
              <kbd>Enter</kbd> 发送 · <kbd>Shift</kbd><span class="composer__plus">+</span><kbd>Enter</kbd> 换行
            </span>
            <button class="btn btn--primary" type="submit" :disabled="!question.trim() || pending">
              <span v-if="pending" class="btn-spinner" aria-hidden="true" />
              <Icon v-else name="send" :size="16" />
              {{ pending ? '正在生成…' : '发送' }}
            </button>
          </div>
        </form>
        <div class="ask-dock__meta">
          <span>{{ identity.tenantId }} · 已进行 {{ thread.length }} 轮 · 追问自动携带上文</span>
          <button class="link-btn" type="button" @click="newConversation">
            <Icon name="plus" :size="13" /> 开始新对话
          </button>
        </div>
      </div>
    </div>

    <footer v-else class="ask-foot">
      <span>知源 · 答案由模型生成，请以引用原文为准</span>
    </footer>

    <SlideOver :open="sourceOpen" :title="sourceTitle" eyebrow="引用原文" wide @close="sourceOpen = false">
      <SourceDrawer :citation="activeCitation" :api="api" />
    </SlideOver>
  </div>
</template>

<style scoped>
.ask-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(52% 34% at 50% -8%, rgba(76, 91, 234, 0.1), transparent 68%),
    var(--paper);
}
.ask-nav {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 66px;
  padding: 10px clamp(18px, 4vw, 40px);
  border-bottom: 1px solid rgba(230, 227, 219, 0.8);
  background: rgba(246, 245, 241, 0.84);
  backdrop-filter: blur(12px);
}
.ask-nav__brand { display: inline-flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; }
.ask-nav__name { font-size: 15px; font-weight: 680; letter-spacing: -0.01em; }
.ask-nav__meta {
  padding-left: 11px;
  border-left: 1px solid var(--line-strong);
  font-size: 12px;
  color: var(--ink-3);
}
.ask-nav__actions { display: flex; align-items: center; gap: 8px; }
.tenant-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  max-width: 200px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--surface);
  font-size: 11.5px;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ask-main {
  flex: 1;
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
  padding: clamp(30px, 5vw, 56px) clamp(18px, 4vw, 32px) 56px;
  display: grid;
  align-content: start;
  gap: 20px;
}
/* 聊天态：为底部固定输入区留出空间，页面滚动时输入框始终可见 */
.ask-page--chat .ask-main { padding-bottom: 210px; }
.ask-hero { text-align: center; padding-top: 6px; transition: opacity 200ms ease; }
.ask-hero h1 {
  margin: 8px 0 0;
  font-size: clamp(30px, 4.6vw, 44px);
  font-weight: 690;
  letter-spacing: -0.035em;
  line-height: 1.08;
}
.ask-hero__lead {
  margin: 12px auto 0;
  max-width: 54ch;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink-2);
  text-wrap: balance;
}
.ask-hero--compact { padding-top: 0; }
.ask-hero--compact h1 { font-size: clamp(24px, 3.4vw, 30px); }
.ask-hero--compact .ask-hero__lead { display: none; }
.composer {
  padding: 16px 18px 13px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--surface);
  box-shadow: 0 24px 60px -50px rgba(16, 18, 23, 0.5);
  transition: border-color 180ms ease, box-shadow 180ms ease;
}
.composer:focus-within { border-color: #C3C8F4; box-shadow: 0 0 0 4px var(--accent-soft), 0 24px 60px -50px rgba(16, 18, 23, 0.5); }
.composer__input {
  display: block;
  width: 100%;
  min-height: 54px;
  max-height: 168px;
  padding: 2px 2px 10px;
  border: 0;
  outline: 0;
  resize: none;
  background: transparent;
  font: inherit;
  font-size: 15px;
  line-height: 1.65;
  color: var(--ink);
}
.composer__input::placeholder { color: var(--ink-3); }
.composer__bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.composer__hint { font-size: 11px; color: var(--ink-3); }
.composer__hint kbd {
  display: inline-block;
  padding: 1px 4px;
  border: 1px solid var(--line);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--paper);
  font-family: inherit;
  font-size: 10px;
  color: var(--ink-2);
}
.composer__plus { margin: 0 1px; }
.ask-hints { display: grid; gap: 22px; }
.suggestion-block { display: grid; gap: 9px; justify-items: center; }
.suggestion-block__label {
  margin: 0;
  font-size: 10.5px;
  font-weight: 650;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.suggestion-list { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; }
.suggestion-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 38px;
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink-2);
  font: inherit;
  font-size: 12.5px;
  transition: border-color 150ms ease, color 150ms ease, transform 150ms ease, box-shadow 150ms ease;
}
.suggestion-chip:hover {
  border-color: #CBD0F2;
  color: var(--accent-ink);
  transform: translateY(-1px);
  box-shadow: 0 10px 24px -18px rgba(57, 72, 212, 0.55);
}
.trust-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px 26px;
  margin: 0;
  padding: 16px 0 0;
  border-top: 1px solid var(--line);
  list-style: none;
  font-size: 11.5px;
  color: var(--ink-3);
}
.trust-row li { display: inline-flex; align-items: center; gap: 6px; }
.thread { display: grid; gap: 26px; }
.thread__end { height: 1px; }
.thread__note {
  margin: 0;
  padding-top: 4px;
  text-align: center;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-3);
}
/* 底部固定输入区：回答之后仍然随手可继续提问 */
.ask-dock {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  padding: 12px clamp(18px, 4vw, 40px) 14px;
  border-top: 1px solid rgba(230, 227, 219, 0.9);
  background: linear-gradient(to top, var(--paper) 62%, rgba(246, 245, 241, 0.92));
  backdrop-filter: blur(12px);
}
.ask-dock__inner {
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
  display: grid;
  gap: 8px;
}
.composer--dock {
  padding: 12px 16px 10px;
  border-radius: 16px;
  box-shadow: 0 20px 48px -34px rgba(16, 18, 23, 0.55);
}
.composer--dock .composer__input { min-height: 34px; padding-bottom: 8px; font-size: 14.5px; }
.ask-dock__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 10.5px;
  color: var(--ink-3);
}
.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-2);
  font: inherit;
  font-size: 10.5px;
  cursor: pointer;
}
.link-btn:hover { color: var(--accent-ink); text-decoration: underline; }
.ask-foot {
  padding: 0 24px 26px;
  text-align: center;
  font-size: 11px;
  letter-spacing: 0.02em;
  color: var(--ink-3);
}
@media (max-width: 640px) {
  .ask-nav__meta { display: none; }
  .tenant-chip { display: none; }
  .ask-main { padding-top: 24px; gap: 16px; }
  .ask-page--chat .ask-main { padding-bottom: 196px; }
  .composer__hint { display: none; }
  .composer__bar { justify-content: flex-end; }
  .ask-dock { padding: 10px 14px 12px; }
  .composer--dock { padding: 10px 14px 9px; }
  .ask-dock__meta { justify-content: flex-end; }
  .ask-dock__meta span { display: none; }
}
</style>
