import { createApp } from 'vue'
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import './style.css'

import Login from './views/Login.vue'
import Dashboard from './views/Dashboard.vue'
import Groups from './views/Groups.vue'
import IpManagement from './views/IpManagement.vue'
import Accounts from './views/Accounts.vue'
import Models from './views/Models.vue'
import Logs from './views/Logs.vue'
import Keys from './views/Keys.vue'
import { getToken } from './api/client'

const routes: RouteRecordRaw[] = [
  { path: '/login', component: Login, meta: { public: true } },
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', component: Dashboard },
  { path: '/channels', redirect: '/accounts' },
  { path: '/groups', component: Groups },
  { path: '/ips', component: IpManagement },
  { path: '/accounts', component: Accounts },
  { path: '/models', component: Models },
  { path: '/logs', component: Logs },
  { path: '/keys', component: Keys },
  { path: '/playground', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  if (!to.meta.public && !getToken()) {
    return '/login'
  }
  return true
})

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
