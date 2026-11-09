import { existsSync } from 'node:fs'
import { copyFile, mkdir, readdir, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 把 `next build` 的 out/ 产物同步到 Spring Boot 静态资源目录。
 *
 * 额外处理：Next 16.3.x 静态导出会把嵌套路由的 RSC 预取载荷输出成
 * `<route>/__next.<seg>/__PAGE__.txt` 目录形式，而客户端请求的是
 * `<route>/__next.<seg>.__PAGE__.txt` 点号形式。这里为每个点号路径补一份
 * 同内容别名文件，避免软导航预取 404；根路由本身就是点号形式，不受影响。
 */

const scriptDir = dirname(fileURLToPath(import.meta.url))
const builtAppDir = join(scriptDir, '..', 'out')
const staticResourceDir = join(scriptDir, '..', '..', 'src', 'main', 'resources', 'dist')

async function entriesOf(dirPath) {
  return readdir(dirPath, { withFileTypes: true })
}

/** 深度优先复制目录树（目标已清空时使用） */
async function mirrorTree(sourceDir, targetDir) {
  await mkdir(targetDir, { recursive: true })
  for (const entry of await entriesOf(sourceDir)) {
    const from = join(sourceDir, entry.name)
    const to = join(targetDir, entry.name)
    if (entry.isDirectory()) {
      await mirrorTree(from, to)
    } else {
      await copyFile(from, to)
    }
  }
}

/** 展开单个 `__next.*` 目录：内部文件按点号拼成别名写到上一级 */
async function writeAliasesForSegment(segmentDir, parentDir, namePrefix) {
  let written = 0
  for (const entry of await entriesOf(segmentDir)) {
    const sourcePath = join(segmentDir, entry.name)
    if (entry.isDirectory()) {
      written += await writeAliasesForSegment(
        sourcePath,
        parentDir,
        `${namePrefix}.${entry.name}`
      )
      continue
    }
    const aliasPath = join(parentDir, `${namePrefix}.${entry.name}`)
    if (!existsSync(aliasPath)) {
      await copyFile(sourcePath, aliasPath)
      written += 1
    }
  }
  return written
}

/** 递归寻找所有 `__next.*` 目录并补齐点号别名 */
async function fillPrefetchAliases(rootDir) {
  let created = 0
  for (const entry of await entriesOf(rootDir)) {
    if (!entry.isDirectory()) continue
    const childDir = join(rootDir, entry.name)
    if (entry.name.startsWith('__next.')) {
      created += await writeAliasesForSegment(childDir, rootDir, entry.name)
    } else {
      created += await fillPrefetchAliases(childDir)
    }
  }
  return created
}

async function main() {
  if (!existsSync(builtAppDir)) {
    console.error('错误: out目录不存在，请先运行 pnpm run build')
    process.exit(1)
  }

  if (existsSync(staticResourceDir)) {
    console.log('删除旧的目标目录...')
    await rm(staticResourceDir, { recursive: true, force: true })
  }

  console.log('复制文件...')
  await mirrorTree(builtAppDir, staticResourceDir)

  const aliasTotal = await fillPrefetchAliases(staticResourceDir)
  if (aliasTotal > 0) {
    console.log(`已补齐 ${aliasTotal} 个 RSC 预取别名文件`)
  }

  console.log('✅ 构建文件复制成功!')
  console.log(`文件已复制到: ${staticResourceDir}`)
}

main().catch((copyError) => {
  console.error('❌ 复制失败:', copyError.message)
  process.exit(1)
})
