<script setup lang="ts">
const allow = defineModel<string>('allow', { required: true })
const deny = defineModel<string>('deny', { required: true })
</script>

<template>
  <p class="form-hint">支持 IPv4、IPv6 和 CIDR，每行一条。拒绝规则优先，允许名单为空时不限制来源。</p>
  <el-form-item label="允许名单">
    <el-input v-model="allow" type="textarea" :rows="3" placeholder="例如 192.0.2.0/24" />
  </el-form-item>
  <el-form-item label="拒绝名单">
    <el-input v-model="deny" type="textarea" :rows="3" placeholder="例如 192.0.2.10" />
  </el-form-item>
</template>
