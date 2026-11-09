/**
 * 前端样式管线配置：Tailwind 生成原子类，Autoprefixer 补齐浏览器前缀。
 * Next.js 构建时会自动读取本文件；插件清单保持最小化。
 */
const stylePipeline = {
  plugins: {
    // 原子类与主题令牌
    tailwindcss: {},
    // 兼容旧版浏览器的前缀
    autoprefixer: {},
  },
}

export default stylePipeline
