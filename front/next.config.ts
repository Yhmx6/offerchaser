import type { NextConfig } from 'next'

// server.config.js 是 CommonJS，构建期由 Node 直接加载（ESLint 已对本文件关闭 no-require-imports）
type BuildTimeServerConfig = {
  api: { baseUrl: string }
  app: { name: string; version: string }
}

const serverConfig = require('./server.config.js') as BuildTimeServerConfig

/** 构建期注入给客户端的公开变量（不含任何密钥） */
const publicEnv = {
  API_BASE_URL: serverConfig.api.baseUrl,
  APP_NAME: serverConfig.app.name,
  APP_VERSION: serverConfig.app.version,
}

const nextConfig: NextConfig = {
  env: publicEnv,
  // 管理台以静态导出方式交付，由后端静态资源目录托管
  output: 'export',
  images: {
    // 静态导出没有图片优化服务
    unoptimized: true,
  },
}

export default nextConfig
