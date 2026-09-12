/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': '/src',
      },
    },
    server: {
      port: 5173,
      // 开发环境代理，避免跨域
      proxy: {
        '/api': {
          target: env.VITE_API_BASE_URL || 'http://localhost:8080',
          changeOrigin: true,
        }
      }
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 1000,
      rollupOptions: {
        output: {
          // v1.32.0：vite 8 / rolldown 仅支持函数式 manualChunks（对象形式已废弃）
          manualChunks(id: string) {
            if (id.includes('node_modules')) {
              if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
              if (id.includes('markdown-it') || id.includes('linkify-it') || id.includes('uc.micro')) return 'markdown'
              if (id.includes('vue') || id.includes('@vue') || id.includes('vue-router') || id.includes('pinia')) return 'vue-vendor'
            }
          }
        }
      }
    },
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['src/**/*.{test,spec}.ts'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        include: ['src/utils/**', 'src/api/**', 'src/auth.ts'],
        exclude: ['src/**/*.test.ts', 'src/**/*.spec.ts', 'src/main.ts'],
        thresholds: {
          statements: 80,
          branches: 80,
          functions: 80,
          lines: 80,
        }
      }
    }
  }
})
