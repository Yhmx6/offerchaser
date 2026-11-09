import { defineConfig, globalIgnores } from 'eslint/config'
import nextTypescript from 'eslint-config-next/typescript'
import nextCoreWebVitals from 'eslint-config-next/core-web-vitals'

/**
 * ESLint flat config：Next.js 核心规则 + TypeScript 规则 + 项目自有覆盖。
 *
 * 覆盖项：
 * 1) Node 构建脚本、Next 配置、Tailwind 配置由 Node 以 CommonJS 直接加载，
 *    require() 是唯一可行写法，关闭 no-require-imports；
 * 2) 忽略 Next 构建产物与自动生成的声明文件。
 */
const nodeCjsAllowlist = {
  files: ['scripts/**/*.js', 'next.config.ts', 'tailwind.config.ts'],
  rules: {
    '@typescript-eslint/no-require-imports': 'off',
  },
}

const buildIgnored = globalIgnores(['.next/**', 'out/**', 'build/**', 'next-env.d.ts'])

export default defineConfig([...nextCoreWebVitals, ...nextTypescript, nodeCjsAllowlist, buildIgnored])
