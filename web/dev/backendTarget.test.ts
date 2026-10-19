import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { backendTarget } from './backendTarget'

function buildDir(files: Record<string, string>): string {
  const dir = mkdtempSync(join(tmpdir(), 'talkcal-build-'))
  for (const [name, content] of Object.entries(files)) writeFileSync(join(dir, name), content)
  return dir
}

const alive = () => true
const dead = () => false

describe('backendTarget', () => {
  it('照 bootRun 寫的埠轉過去', () => {
    const dir = buildDir({ 'application.port': '53127\n', 'application.pid': '4242' })
    expect(backendTarget(dir, {}, alive)).toEqual({ url: 'http://127.0.0.1:53127' })
  })

  it('沒有埠檔（這個資料夾沒 bootRun）就說找不到，不退回 8090', () => {
    // 退回 8090 的話，會接到剛好佔著 8090 的、別的 worktree 的後端
    const result = backendTarget(buildDir({}), {}, alive)
    expect(result).toEqual({ error: expect.stringContaining('找不到後端') })
  })

  it('埠檔還在、但寫它的程序已經不在（被硬砍）就說找不到', () => {
    const dir = buildDir({ 'application.port': '8090', 'application.pid': '4242' })
    const result = backendTarget(dir, {}, dead)
    expect(result).toEqual({ error: expect.stringContaining('已經結束') })
  })

  it('沒有 pid 檔也當作找不到：無法確定那個埠是不是自己的', () => {
    const dir = buildDir({ 'application.port': '8090' })
    expect(backendTarget(dir, {}, alive)).toEqual({ error: expect.stringContaining('找不到後端') })
  })

  it('埠檔內容壞掉（寫到一半）就當作沒有', () => {
    const dir = buildDir({ 'application.port': '', 'application.pid': '4242' })
    expect(backendTarget(dir, {}, alive)).toEqual({ error: expect.stringContaining('找不到後端') })
  })

  it('TALKCAL_API 有設就用它，不看檔案', () => {
    expect(backendTarget(buildDir({}), { TALKCAL_API: 'http://localhost:18090' }, dead)).toEqual({ url: 'http://localhost:18090' })
  })

  it('用真的 pid 檢查：自己這個程序算活著', () => {
    const dir = buildDir({ 'application.port': '8090', 'application.pid': String(process.pid) })
    expect(backendTarget(dir, {})).toEqual({ url: 'http://127.0.0.1:8090' })
  })
})
