import { useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { AccountRole, ImportResult } from '../../shared/api/contracts'
import { api, download, saveBlob } from '../../shared/api/http'
import { formatSize, validateFile } from '../../shared/files'
import { ErrorNotice, PageHeading } from '../../shared/ui'
import { importErrorsCsv } from './csv'

function importFile(action: 'preview' | 'execute', file: File, role: AccountRole) {
  const body = new FormData()
  body.append('file', file)
  body.append('role', role)
  return api<ImportResult>(`/admin/imports/${action}`, { method: 'POST', body })
}

export function ImportsPage() {
  const client = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [role, setRole] = useState<AccountRole>('STUDENT')
  const [file, setFile] = useState<File | null>(null)
  const [validation, setValidation] = useState<string | null>(null)
  const preview = useMutation({
    mutationFn: ({ file, role }: { file: File; role: AccountRole }) =>
      importFile('preview', file, role),
  })
  const execute = useMutation({
    mutationFn: ({ file, role }: { file: File; role: AccountRole }) =>
      importFile('execute', file, role),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['users'] })
      void client.invalidateQueries({ queryKey: ['dashboard'] })
      void client.invalidateQueries({ queryKey: ['teacher-options'] })
    },
  })
  const template = useMutation({
    mutationFn: () =>
      download(
        `/admin/imports/template?role=${role}`,
        `${role === 'STUDENT' ? '学生' : '教师'}导入模板.xlsx`,
      ),
  })
  const busy = preview.isPending || execute.isPending
  const result = execute.data ?? preview.data
  function choose(next: File | null) {
    preview.reset()
    execute.reset()
    const error = next ? validateFile(next, 'xlsx') : null
    setValidation(error)
    setFile(error ? null : next)
  }
  return (
    <>
      <PageHeading
        title="批量导入账号"
        description="下载模板填写后，先预检，再确认导入有效账号。"
      />
      <section className="panel form-stack">
        <fieldset className="role-choice">
          <legend>导入账号类型</legend>
          {(['STUDENT', 'TEACHER'] as const).map((value) => (
            <label key={value}>
              <input
                type="radio"
                name="import-role"
                value={value}
                checked={role === value}
                disabled={busy || template.isPending}
                onChange={() => {
                  setRole(value)
                  choose(null)
                  if (input.current) input.current.value = ''
                }}
              />
              {value === 'STUDENT' ? '学生' : '教师'}
            </label>
          ))}
        </fieldset>
        <div>
          <button
            className="button secondary"
            onClick={() => template.mutate()}
            disabled={template.isPending}
          >
            {template.isPending ? '正在下载…' : `下载${role === 'STUDENT' ? '学生' : '教师'}模板`}
          </button>
          <p className="help">
            {role === 'STUDENT' ? '列：学号、姓名、专业、指导教师工号。' : '列：工号、姓名。'}
            账号列请设为文本，保留前导零。
          </p>
          <ErrorNotice error={template.error} />
        </div>
        <label>
          选择 Excel 文件
          <input
            ref={input}
            type="file"
            accept=".xlsx"
            disabled={busy}
            onChange={(event) => choose(event.target.files?.[0] ?? null)}
          />
        </label>
        <p className="help">
          仅支持 .xlsx，最大 5 MB、2000 行。已存在的账号会跳过；有错误的行不会导入。初始密码为
          123456。
        </p>
        {file && (
          <p className="selected-file">
            {file.name} · {formatSize(file.size)}
          </p>
        )}
        <ErrorNotice error={validation ?? preview.error} />
        <div>
          <button
            className="button primary"
            disabled={!file || busy}
            onClick={() => {
              execute.reset()
              if (file) preview.mutate({ file, role })
            }}
          >
            {preview.isPending ? '正在预检…' : '预检文件'}
          </button>
        </div>
      </section>
      {result && (
        <section className="panel">
          <h2>{execute.data ? '导入完成' : '预检结果'}</h2>
          <dl className="import-summary">
            <div>
              <dt>文件行数</dt>
              <dd>{result.totalRows}</dd>
            </div>
            <div>
              <dt>{execute.data ? '导入成功' : '可导入'}</dt>
              <dd>{execute.data ? result.successCount : result.validRows}</dd>
            </div>
            <div>
              <dt>跳过</dt>
              <dd>{result.skippedCount}</dd>
            </div>
            <div>
              <dt>错误</dt>
              <dd>{result.failedCount}</dd>
            </div>
          </dl>
          {!execute.data && (
            <>
              <p className="muted">
                确认后仅导入有效账号，错误行和已存在账号会跳过。导入时会再次检查最新账号信息。
              </p>
              <button
                className="button primary"
                disabled={!file || busy || result.validRows === 0}
                onClick={() => {
                  if (file) execute.mutate({ file, role })
                }}
              >
                {execute.isPending ? '正在导入…' : '确认导入有效账号'}
              </button>
            </>
          )}
          {execute.data && (
            <p className="notice success" role="status">
              本次导入已完成。新账号初始密码为 123456，首次登录需重新设置密码。
            </p>
          )}
          <ErrorNotice error={execute.error} />
          {result.errors.length > 0 && (
            <>
              <div className="section-heading">
                <h3>需处理的行</h3>
                <button
                  className="button secondary"
                  onClick={() =>
                    saveBlob(
                      new Blob([importErrorsCsv(result.errors)], {
                        type: 'text/csv;charset=utf-8',
                      }),
                      '账号导入错误.csv',
                    )
                  }
                >
                  下载错误清单
                </button>
              </div>
              <div className="table-scroll">
                <table>
                  <caption className="sr-only">账号导入错误行</caption>
                  <thead>
                    <tr>
                      <th>行号</th>
                      <th>账号</th>
                      <th>错误原因</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.errors.map((error, index) => (
                      <tr key={`${error.row}-${index}`}>
                        <td>{error.row}</td>
                        <td>{error.username || '—'}</td>
                        <td>{error.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </section>
      )}
    </>
  )
}
