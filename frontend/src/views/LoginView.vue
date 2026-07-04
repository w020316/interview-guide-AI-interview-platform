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
              <el-input v-model="regForm.username" placeholder="用户名" prefix-icon="User" />
            </el-form-item>
            <el-form-item>
              <el-input v-model="regForm.password" type="password"
                placeholder="密码" prefix-icon="Lock" show-password />
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
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
const router = useRouter()
const activeTab = ref('login')
const loading = ref(false)
const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', email: '' })
const API = import.meta.env.VITE_API_BASE_URL || ''

async function handleLogin() {
  if (!loginForm.value.username || !loginForm.value.password)
    return ElMessage.warning('请填写用户名和密码')
  loading.value = true
  try {
    const { data } = await axios.post(`${API}/api/auth/login`, loginForm.value)
    if (data.code === 200) { localStorage.setItem('token', data.data); ElMessage.success('登录成功'); router.push('/') }
    else ElMessage.error(data.message)
  } catch (e: any) { ElMessage.error(e.response?.data?.message || '登录失败') }
  finally { loading.value = false }
}
async function handleRegister() {
  loading.value = true
  try {
    const { data } = await axios.post(`${API}/api/auth/register`, regForm.value)
    if (data.code === 200) { localStorage.setItem('token', data.data); ElMessage.success('注册成功'); router.push('/') }
    else ElMessage.error(data.message)
  } catch (e: any) { ElMessage.error(e.response?.data?.message || '注册失败') }
  finally { loading.value = false }
}
</script>
<style scoped>
.login-page { display:flex; justify-content:center; align-items:center; min-height:80vh; }
.login-card { width:400px; max-width:95vw; }
h2 { text-align:center; margin-bottom:20px; color:#409eff; }
</style>
