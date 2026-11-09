import { spawn } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

/**
 * 生产模式入口。
 *
 * next.config.ts 使用 output: 'export'，没有 `next start` 服务端；
 * 这里用 `serve` 托管构建产物（构建后先 copy-dist 到后端 resources/dist）。
 */
const projectDir = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)

const configFile = join(projectDir, 'server.config.js')
delete require.cache[require.resolve(configFile)]
const runtimeConfig = require(configFile)

const listenPort = runtimeConfig.production?.port || runtimeConfig.port || 8080
const listenHost = runtimeConfig.production?.hostname || '0.0.0.0'

const staticServer = spawn('npx', ['serve', '-l', String(listenPort), 'dist'], {
  stdio: 'inherit',
  shell: true,
  cwd: projectDir,
  env: { ...process.env, HOSTNAME: listenHost },
})

staticServer.on('error', (spawnError) => {
  console.error('启动静态服务失败:', spawnError)
  process.exit(1)
})

staticServer.on('close', (exitCode) => {
  console.log(`静态服务退出，代码: ${exitCode ?? 0}`)
  process.exit(exitCode ?? 0)
})
