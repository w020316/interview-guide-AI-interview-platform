<template>
  <div class="login-container">
    <el-card class="login-card" shadow="always">
      <div class="login-header">
        <el-icon :size="40" color="#409EFF"><ChatDotRound /></el-icon>
        <h2>AI 智能面试辅助平台</h2>
        <p class="subtitle">Spring Boot 3.3 + Spring AI 1.0 + Java 21</p>
      </div>

      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form :model="loginForm" :rules="rules" ref="loginFormRef" label-position="top">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="loginForm.username" placeholder="请输入用户名" :prefix-icon="User" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码" :prefix-icon="Lock" @keyup.enter="handleLogin" />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleLogin">登 录</el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form :model="registerForm" :rules="rules" ref="registerFormRef" label-position="top">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="registerForm.username" placeholder="请输入用户名" :prefix-icon="User" />
            </el-form-item>
            <el-form-item label="邮箱（选填）" prop="email">
              <el-input v-model="registerForm.email" placeholder="请输入邮箱" :prefix-icon="Message" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" show-password placeholder="请输入密码" :prefix-icon="Lock" />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit-btn" @click="handleRegister">注 册</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { User, Lock, Message, ChatDotRound } from '@element-plus/icons-vue'
import api from '../api'

const router = useRouter()
const activeTab = ref<'login' | 'register'>('login')
const loading = ref(false)
const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', email: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!loginFormRef.value) return
  await loginFormRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const token = await api.post('/auth/login', loginForm)
      localStorage.setItem('token', token)
      localStorage.setItem('username', loginForm.username)
      ElMessage.success('登录成功')
      router.push('/resume')
    } catch (e: any) {
      ElMessage.error(e.message || '登录失败')
    } finally {
      loading.value = false
    }
  })
}

const handleRegister = async () => {
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const token = await api.post('/auth/register', registerForm)
      localStorage.setItem('token', token)
      localStorage.setItem('username', registerForm.username)
      ElMessage.success('注册成功，已自动登录')
      router.push('/resume')
    } catch (e: any) {
      ElMessage.error(e.message || '注册失败')
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 420px;
  padding: 20px 10px;
  border-radius: 12px;
}
.login-header {
  text-align: center;
  margin-bottom: 24px;
}
.login-header h2 {
  margin: 12px 0 4px;
  color: #303133;
}
.subtitle {
  color: #909399;
  font-size: 12px;
  margin: 0;
}
.submit-btn {
  width: 100%;
  margin-top: 8px;
}
</style>
