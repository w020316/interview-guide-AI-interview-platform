/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [
      vue(),
      // PWA（第三批 D）：三条硬条件——
      //   ① /api/** 必须 NetworkOnly（绝不缓存 API，离线请求直接失败走各页既有「加载失败 + 重试」）；
      //   ② registerType:'prompt'，新版本只提示不静默刷新（绝不打断作答）；
      //   ③ SPA navigateFallback 不得吞掉 /api（navigateFallbackDenylist 双保险）。
      VitePWA({
        registerType: 'prompt',
        injectRegister: 'auto',
        strategies: 'generateSW',
        includeAssets: [
          'favicon.svg',
          'icon-192.png',
          'icon-512.png',
          'icon-maskable-512.png',
        ],
        manifest: {
          name: 'AI 智能面试辅助平台',
          short_name: '面试工作台',
          description: '简历分析、岗位匹配、模拟面试、智能体问答，助力求职者高效备战面试',
          start_url: '/',
          scope: '/',
          display: 'standalone',
          lang: 'zh-CN',
          // 平台 Web App Manifest 规范不支持 CSS 变量，只能用实色 hex；
          // 本文件不在 designGuards 的 src/ 扫描域内，故无需白名单条目（见设计 §9）。
          background_color: '#ffffff',
          theme_color: '#0f766e', // = --brand-primary 当前实值（deep teal-700）
          icons: [
            // 真实 PNG 图标（192/512 各一条 any，外加 maskable），SVG 保留作矢量回退
            { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
            { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
            { src: '/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
            { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          ],
        },
        workbox: {
          navigateFallback: '/index.html',
          navigateFallbackDenylist: [/^\/api\//],
          globPatterns: ['**/*.{js,css,html,svg,woff2}'],
          // ⭐ /api/** 显式 NetworkOnly：绝不缓存 API 响应
          runtimeCaching: [
            {
              urlPattern: ({ url }) => url.pathname.startsWith('/api/'),
              handler: 'NetworkOnly',
            },
          ],
        },
      }),
    ],
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
