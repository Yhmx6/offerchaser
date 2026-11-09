/**
 * 前端运行时参数（替代 .env）。仅包含端口/主机名/后端地址等非敏感项。
 * 修改后需重启 start-dev / start-prod 才生效。
 */

// 后端 Spring Boot 服务的监听地址，需与 application.yaml 的 server.port 一致
const backend = {
  baseUrl: 'http://localhost:9527',
}

// 应用元信息：构建期由 next.config.ts 注入为 NEXT_PUBLIC 风格的公开变量
const appMeta = {
  name: 'OfferChaser',
  version: '1.0.0',
}

// 开发服务器：Turbopack + 自动开浏览器；绑定 IPv4 规避 Windows 上 ::1 的权限差异
const devServer = {
  turbo: true,
  open: true,
  hostname: '127.0.0.1',
}

// 生产环境静态服务器：对外监听全部网卡，端口与管理台默认端口一致
const prodServer = {
  port: 6866,
  hostname: '0.0.0.0',
}

module.exports = {
  // 未区分环境时的兜底端口
  port: 6866,
  development: devServer,
  production: prodServer,
  api: backend,
  app: appMeta,
}
