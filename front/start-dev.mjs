import { spawn } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

// 以本文件所在目录为基准定位 server.config.js
const projectDir = dirname(fileURLToPath(import.meta.url))
const configFile = join(projectDir, 'server.config.js')

const require = createRequire(import.meta.url)
// 清缓存，保证每次启动都读取最新的 server.config.js
delete require.cache[require.resolve(configFile)]
const runtimeConfig = require(configFile)

const listenPort = runtimeConfig.port || 3000
const listenHost = runtimeConfig.development?.hostname || 'localhost'

// 开发模式：next dev（server.config 里的 turbo 开关由 Next 默认 Turbopack 承接）
const devProcess = spawn(
  'npx',
  ['next', 'dev', '-p', String(listenPort), '-H', listenHost],
  { stdio: 'inherit', shell: true }
)

devProcess.on('error', (spawnError) => {
  console.error('启动失败:', spawnError)
  process.exit(1)
})

devProcess.on('close', (exitCode) => {
  process.exit(exitCode ?? 0)
})
