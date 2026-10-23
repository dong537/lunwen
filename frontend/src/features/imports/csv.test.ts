import { expect, it } from 'vitest'
import { importErrorsCsv } from './csv'

it('导入错误 CSV 保留前导零并阻断表格公式注入', () => {
  const csv = importErrorsCsv([
    { row: 2, username: '00123', message: '=HYPERLINK("evil")' },
    { row: 3, username: '+cmd', message: '姓名,为空\n请补齐' },
    { row: 4, username: '\t@SUM(1)', message: '无效' },
  ])
  expect(csv.startsWith('\ufeff')).toBe(true)
  expect(csv).toContain('"\'00123"')
  expect(csv).toContain('"\'=HYPERLINK(""evil"")"')
  expect(csv).toContain('"\'+cmd"')
  expect(csv).toContain('"姓名,为空\n请补齐"')
  expect(csv).toContain('"\'\t@SUM(1)"')
})
