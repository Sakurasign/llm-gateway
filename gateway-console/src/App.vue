<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataBoard, Document, Key, Menu, Moon, SetUp, Grid, Sunny, SwitchButton, User } from '@element-plus/icons-vue'
import { clearToken } from './api/client'
import { useTheme } from './composables/useTheme'

const route = useRoute()
const router = useRouter()
const { isDark, toggle } = useTheme()
const groups = [
  { label: '工作台', links: [{ path: '/dashboard', label: '运行概览', icon: DataBoard }] },
  { label: '网关配置', links: [
    { path: '/accounts', label: '账号管理', icon: User },
    { path: '/groups', label: '分组管理', icon: Grid },
    { path: '/ips', label: 'IP 管理', icon: SetUp }
  ] },
  { label: '使用', links: [
    { path: '/keys', label: '我的密钥', icon: Key },
    { path: '/logs', label: '使用记录', icon: Document }
  ] }
]
const isLogin = computed(() => route.path === '/login')
const section = computed(() => groups.find(group => group.links.some(link => link.path === route.path)))
const active = computed(() => section.value?.links.find(link => link.path === route.path))
function logout() { clearToken(); void router.push('/login') }
</script>

<template>
  <div v-if="isLogin" class="login-wrap"><router-view /></div>
  <div v-else class="layout">
    <aside class="sidebar">
      <router-link to="/dashboard" class="brand" aria-label="Sakura Gateway 概览">
        <span class="brand-mark"><el-icon><Menu /></el-icon></span>
        <span>Sakura Gateway<small>管理工作台</small></span>
      </router-link>
      <nav aria-label="主导航" class="sidebar-nav">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <div class="nav-label">{{ group.label }}</div>
          <router-link v-for="link in group.links" :key="link.path" :to="link.path" class="nav-item" :title="link.label">
            <el-icon><component :is="link.icon" /></el-icon><span>{{ link.label }}</span>
          </router-link>
        </div>
      </nav>
      <div class="sidebar-foot">
        <button class="nav-item" :title="isDark ? '浅色模式' : '深色模式'" @click="toggle">
          <el-icon><component :is="isDark ? Sunny : Moon" /></el-icon><span>{{ isDark ? '浅色模式' : '深色模式' }}</span>
        </button>
        <button class="nav-item" title="退出登录" @click="logout"><el-icon><SwitchButton /></el-icon><span>退出登录</span></button>
      </div>
    </aside>
    <div class="workbench">
      <header class="topbar"><span>{{ section?.label }}</span><span class="crumb">/</span><strong>{{ active?.label }}</strong></header>
      <main class="main"><router-view /></main>
    </div>
  </div>
</template>
