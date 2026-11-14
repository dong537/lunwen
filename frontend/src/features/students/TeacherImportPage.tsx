import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import {
  Alert,
  App as AntdApp,
  Button,
  Card,
  Space,
  Statistic,
  Table,
  Typography,
  Upload,
} from 'antd'
import {
  ArrowLeftOutlined,
  DownloadOutlined,
  InboxOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { UploadProps } from 'antd'
import type { ImportError } from '../../shared/api/contracts'
import { saveBlob } from '../../shared/api/http'
import { formatSize, validateFile } from '../../shared/files'
import { ApiAlert, PageHeader, useDocumentTitle } from '../../shared/ui'
import { importErrorsCsv } from '../imports/csv'
import { useSession } from '../auth/session'
import { teacherApi } from './api'

const errorColumns: ColumnsType<ImportError> = [
  { title: '行号', dataIndex: 'row', key: 'row', width: 90 },
  {
    title: '学号',
    dataIndex: 'username',
    key: 'username',
    width: 170,
    render: (value: string) => value || '—',
  },
  { title: '错误原因', dataIndex: 'message', key: 'message' },
]

export function TeacherImportPage() {
  useDocumentTitle('导入指导名单')
  const { message } = AntdApp.useApp()
  const { user } = useSession()
  const client = useQueryClient()
  const [file, setFile] = useState<File | null>(null)
  const [validation, setValidation] = useState<string | null>(null)
  const template = useMutation({ mutationFn: teacherApi.template })
  const preview = useMutation({ mutationFn: teacherApi.preview })
  const execute = useMutation({
    mutationFn: teacherApi.execute,
    onSuccess: (result) => {
      void client.invalidateQueries({ queryKey: ['students'] })
      void client.invalidateQueries({ queryKey: ['teacher-statistics'] })
      void message.success(
        '导入完成：成功 ' + result.successCount + ' 条，错误 ' + result.failedCount + ' 条。',
      )
    },
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
      <Link
        to="/teacher/students"
        style={{ display: 'inline-flex', alignItems: 'center', gap: 8, height: 32 }}
      >
        <ArrowLeftOutlined aria-hidden />
        返回我的学生
      </Link>
      <PageHeader
        title="导入指导名单"
        description="下载模板填写学号、姓名和专业；先预检，再确认导入。"
      />
      {!user?.collegeCode && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title="学院尚未归属，请联系校级管理员补录学院后再导入学生。"
        />
      )}
      <Card style={{ marginBottom: 16 }}>
        <Space orientation="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <Button
              icon={<DownloadOutlined />}
              loading={template.isPending}
              onClick={() => template.mutate()}
            >
              下载名单模板
            </Button>
            <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
              模板列为学号、姓名、专业。学号列请设为文本以保留前导零；新账号初始密码为 123456。
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
              preview.reset()
              execute.reset()
              return true
            }}
            disabled={busy || !user?.collegeCode}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击选择或将 Excel 文件拖放到此处</p>
            <p className="ant-upload-hint">
              仅支持 .xlsx，最大 5 MB、2000 行；已存在账号跳过，错误行不会导入。
            </p>
          </Upload.Dragger>
          {file && (
            <Typography.Text type="secondary">
              {file.name} · {formatSize(file.size)}
            </Typography.Text>
          )}
          <ApiAlert error={validation ?? preview.error} />
          <Button
            type="primary"
            icon={<UploadOutlined />}
            loading={preview.isPending}
            disabled={!file || !user?.collegeCode || busy}
            onClick={() => {
              execute.reset()
              if (file) preview.mutate(file)
            }}
          >
            预检文件
          </Button>
        </Space>
      </Card>
      {result && (
        <Card title={execute.data ? '导入完成' : '预检结果'}>
          <Space size={40} wrap style={{ marginBottom: 16 }}>
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
                仅导入有效账号；错误行和已存在账号会跳过。确认时会重新校验名单和现有账号。
              </Typography.Paragraph>
              <Button
                type="primary"
                loading={execute.isPending}
                disabled={!file || busy || result.validRows === 0}
                onClick={() => {
                  if (file) execute.mutate(file)
                }}
              >
                确认导入有效账号
              </Button>
            </>
          )}
          {execute.data && (
            <Typography.Paragraph>
              名单导入已完成，学生可使用初始密码直接登录。
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
                  gap: 8,
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
                      '指导名单导入错误.csv',
                    )
                  }
                >
                  下载错误清单
                </Button>
              </div>
              <Table<ImportError>
                rowKey={(error) => JSON.stringify([error.row, error.username, error.message])}
                size="small"
                columns={errorColumns}
                dataSource={result.errors}
                pagination={false}
                scroll={{ x: 'max-content' }}
              />
            </>
          )}
        </Card>
      )}
    </>
  )
}
