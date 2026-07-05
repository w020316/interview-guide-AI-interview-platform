<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <h2>🎯 AI 面试助手</h2>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="登录" name="login">
          <el-form :model="loginForm" @submit.prevent="handleLogin">
            <el-form-item>
              <el-input v-model="loginForm.username" placeholder="用户名" prefix-icon="User" />
            </el-form-item>
            <el-form-item>
              <el-input v-model="loginForm.password" type="password"
                placeholder="密码" prefix-icon="Lock" show-password />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">登录</el-button>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="注册" name="register">
          <el-form :model="regForm" @submit.prevent="handleRegister">
            <el-form-item>
              <el-input v-model="regForm.username" placeholder="用户名（至少 3 位）" prefix-icon="User" />
            </el-form-item>
            <el-form-item>
              <el-input v-model="regForm.password" type="password"
                placeholder="密码（至少 6 位）" prefix-icon="Lock" show-password />
            </el-form-item>
            <el-form-item>
              <el-input v-model="regForm.email" placeholder="邮箱（可选）" prefix-icon="Message" />
            </el-form-item>
            <el-button type="success" native-type="submit" :loading="loading" style="width:100%">注册</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { setAuth } from '../auth'

const router = useRouter()
const route = useRoute()
const activeTab = ref('login')
const loading = ref(false)
const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', email: '' })

/** 登录/注册成功后跳转：redirect 必须以 / 开头的相对路径，防止开放重定向 */
function redirectAfterAuth() {
  const r = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect
  const safe = typeof r === 'string' && r.startsWith('/') ? r : '/'
  router.push(safe)
}

async function handleLogin() {
  if (!loginForm.value.username || !loginForm.value.password)
    return ElMessage.warning('请填写用户名和密码')
  loading.value = true
  try {
    const token = await api.post('/api/auth/login', loginForm.value) as unknown as string
    setAuth(token, loginForm.value.username)
    ElMessage.success('登录成功')
    redirectAfterAuth()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '登录失败'))
  } finally { loading.value = false }
}

async function handleRegister() {
  if (!regForm.value.username || regForm.value.username.length < 3)
    return ElMessage.warning('用户名至少 3 位')
  if (!regForm.value.password || regForm.value.password.length < 6)
    return ElMessage.warning('密码至少 6 位')
  if (regForm.value.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(regForm.value.email))
    return ElMessage.warning('邮箱格式不正确')
  loading.value = true
  try {
    const token = await api.post('/api/auth/register', regForm.value) as unknown as string
    setAuth(token, regForm.value.username)
    ElMessage.success('注册成功')
    redirectAfterAuth()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '注册失败'))
  } finally { loading.value = false }
}
</script>
<style scoped>
.login-page { display:flex; justify-content:center; align-items:center; min-height:80vh; }
.login-card { width:400px; max-width:95vw; }
h2 { text-align:center; margin-bottom:20px; color:#409eff; }
</style>
