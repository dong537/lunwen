import { describe, expect, it } from 'vitest'
import { outcomeText, needsPolling } from './submission-state'
import { validateFile } from '../../shared/files'

describe('审核状态与上传', () => {
  it('只有成功且明确零问题才显示全部通过', () => {
    expect(outcomeText('SUCCESS', 0)).toBe('全部通过')
    expect(outcomeText('SUCCESS', 7)).toBe('7 个问题')
    expect(outcomeText('PENDING', null)).toBe('等待审核')
    expect(outcomeText('PROCESSING', null)).toBe('正在审核')
    expect(outcomeText('FAILED', null)).toBe('审核未完成')
    expect(outcomeText('SUCCESS', null)).not.toBe('全部通过')
  })
  it('仅排队与审核中的任务轮询', () => {
    expect(needsPolling('PENDING')).toBe(true)
    expect(needsPolling('PROCESSING')).toBe(true)
    expect(needsPolling('SUCCESS')).toBe(false)
    expect(needsPolling('FAILED')).toBe(false)
  })
  it('拒绝空文件、旧 Word 格式和超限文件，接受大小边界', () => {
    expect(validateFile(new File([], '论文.docx'), 'docx')).toContain('空')
    expect(validateFile(new File(['x'], '论文.doc'), 'docx')).toContain('.docx')
    const file = new File(['x'], '论文.DOCX')
    Object.defineProperty(file, 'size', { value: 50 * 1024 * 1024 })
    expect(validateFile(file, 'docx')).toBeNull()
    const tooBig = new File(['x'], '论文.docx')
    Object.defineProperty(tooBig, 'size', { value: 50 * 1024 * 1024 + 1 })
    expect(validateFile(tooBig, 'docx')).toContain('50')
  })
})
