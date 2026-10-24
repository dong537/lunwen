import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Button, Card, Radio, Space, Statistic, Table, Typography, Upload, message } from 'antd'
import { DownloadOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { UploadProps } from 'antd'
import type { AccountRole, ImportError, ImportResult } from '../../shared/api/contracts'
import { api, download, saveBlob } from '../../shared/api/http'
import { formatSize, validateFile } from '../../shared/files'
import { ApiAlert, PageHeader } from '../../shared/ui'
import { importErrorsCsv } from './csv'

function importFile(action: 'preview' | 'execute', file: File, role: AccountRole) {
  const body = new FormData()
  body.append('file', file)
  body.append('role', role)
  return api<ImportResult>(`/admin/imports/${action}`, { method: 'POST', body })
}

const errorColumns: ColumnsType<ImportError> = [
  { title: '行号', dataIndex: 'row', key: 'row', width: 100 },
  {
    title: '账号',
    dataIndex: 'username',
    key: 'username',
    width: 160,
    render: (value: string) => value || '—',
  },
  { title: '错误原因', dataIndex: 'message', key: 'message' },
]

export function ImportsPage() {
  const client = useQueryClient()
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
      void message.success('导入完成')
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
  const beforeUpload: UploadProps['beforeUpload'] = (next) => {
    preview.reset()
    execute.reset()
    const error = validateFile(next, 'xlsx')
    setValidation(error)
    setFile(error ? null : next)
    return false
  }
  return (
    <>
      <PageHeader title="批量导入账号" description="下载模板填写后，先预检，再确认导入有效账号。" />
      <Card style={{ marginBottom: 16 }}>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Radio.Group
            value={role}
            disabled={busy || template.isPending}
            onChange={(event) => {
              setRole(event.target.value)
              preview.reset()
              execute.reset()
              setFile(null)
              setValidation(null)
            }}
            options={[
              { value: 'STUDENT', label: '学生' },
              { value: 'TEACHER', label: '教师' },
            ]}
          />
          <div>
            <Button
              icon={<DownloadOutlined />}
              loading={template.isPending}
              onClick={() => template.mutate()}
            >
              下载{role === 'STUDENT' ? '学生' : '教师'}模板
            </Button>
            <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
              {role === 'STUDENT' ? '列：学号、姓名、专业、指导教师工号。' : '列：工号、姓名。'}
              账号列请设为文本，保留前导零。
            </Typography.Paragraph>
            <ApiAlert error={template.error} />
          </div>
          <Upload.Dragger
            accept=".xlsx"
            multiple={false}
            maxCount={1}
            fileList={
              file ? [{ uid: file.name, name: file.name, size: file.size, status: 'done' }] : []
            }
            beforeUpload={beforeUpload}
            onRemove={() => {
              if (busy) return false
              setFile(null)
              return true
            }}
            disabled={busy}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击选择或将 Excel 文件拖放到此处</p>
            <p className="ant-upload-hint">
              仅支持 .xlsx，最大 5 MB、2000 行。已存在的账号会跳过；有错误的行不会导入。初始密码为
              123456。
            </p>
          </Upload.Dragger>
          {file && (
            <Typography.Text type="secondary">
              {file.name} · {formatSize(file.size)}
            </Typography.Text>
          )}
          <ApiAlert error={validation ?? preview.error} />
          <div>
            <Button
              type="primary"
              icon={<UploadOutlined />}
              loading={preview.isPending}
              disabled={!file || busy}
              onClick={() => {
                execute.reset()
                if (file) preview.mutate({ file, role })
              }}
            >
              预检文件
            </Button>
          </div>
        </Space>
      </Card>
      {result && (
        <Card title={execute.data ? '导入完成' : '预检结果'}>
          <Space size={48} wrap style={{ marginBottom: 16 }}>
            <Statistic title="文件行数" value={result.totalRows} />
            <Statistic
              title={execute.data ? '导入成功' : '可导入'}
              value={execute.data ? result.successCount : result.validRows}
            />
            <Statistic title="跳过" value={result.skippedCount} />
            <Statistic title="错误" value={result.failedCount} />
          </Space>
          {!execute.data && (
            <>
              <Typography.Paragraph type="secondary">
                确认后仅导入有效账号，错误行和已存在账号会跳过。导入时会再次检查最新账号信息。
              </Typography.Paragraph>
              <Button
                type="primary"
                loading={execute.isPending}
                disabled={!file || busy || result.validRows === 0}
                onClick={() => {
                  if (file) execute.mutate({ file, role })
                }}
              >
                确认导入有效账号
              </Button>
            </>
          )}
          {execute.data && (
            <Typography.Paragraph type="success">
              本次导入已完成。新账号初始密码为 123456，首次登录需重新设置密码。
            </Typography.Paragraph>
          )}
          <ApiAlert error={execute.error} />
          {result.errors.length > 0 && (
            <>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  margin: '16px 0 8px',
                }}
              >
                <Typography.Title level={5} style={{ margin: 0 }}>
                  需处理的行
                </Typography.Title>
                <Button
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
                </Button>
              </div>
              <Table<ImportError>
                rowKey={(_, index) => `${index}`}
                columns={errorColumns}
                dataSource={result.errors}
                pagination={false}
              />
            </>
          )}
        </Card>
      )}
    </>
  )
}
