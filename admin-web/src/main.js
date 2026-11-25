import { createApp } from 'vue'
import './style.css'

// 三个页面必须是独立的 import() 调用点：Vite 会为每个页面生成各自的
// __vitePreload 依赖映射。若合并成一个三元表达式，三个页面会共用 App 的
// 预加载列表，导致 /ask/ 与 /register/ 的页面 CSS 永远不会被预加载（未样式化页面）。
let page
if (window.location.pathname === '/ask/') {
  page = import('./Ask.vue')
} else if (window.location.pathname === '/register/') {
  page = import('./Register.vue')
} else {
  page = import('./App.vue')
}
page.then(({ default: root }) => createApp(root).mount('#app'))
