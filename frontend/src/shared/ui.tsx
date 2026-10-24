import { useEffect, type ReactNode } from 'react'
import { Alert, Button, Space, Spin, Typography } from 'antd'
import { ApiError } from './api/http'

const SITE_TITLE = '论文审核平台'

export function useDocumentTitle(title: string) {
  useEffect(() => {
    const previous = document.title
    document.title = `${title} - ${SITE_TITLE}`
    return () => {
      document.title = previous
    }
  }, [title])
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function ApiAlert({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null
  const fieldErrors = error instanceof ApiError ? Object.entries(error.fieldErrors) : []
  const requestId = error instanceof ApiError ? error.requestId : undefined
  return (
    <div>
      <Alert
        type="error"
        showIcon
        title={errorText(error)}
        description={
          fieldErrors.length > 0 || requestId ? (
            <Space direction="vertical" size={4}>
              {fieldErrors.length > 0 && (
                <ul style={{ margin: 0, paddingInlineStart: 20 }}>
                  {fieldErrors.map(([field, message]) => (
                    <li key={field}>{message}</li>
                  ))}
                </ul>
              )}
              {requestId && <span>联系管理员时可提供编号：{requestId}</span>}
            </Space>
          ) : undefined
        }
        action={
          retry ? (
            <Button size="small" onClick={retry}>
              重试
            </Button>
          ) : undefined
        }
      />
    </div>
  )
}

export function CenterSpin({ tip = '正在加载…' }: { tip?: ReactNode }) {
  return (
    <div style={{ padding: 48, textAlign: 'center' }}>
      <Spin description={tip} />
    </div>
  )
}

export function PageHeader({
  title,
  description,
  extra,
}: {
  title: string
  description?: string
  extra?: ReactNode
}) {
  return (
    <Space direction="vertical" size={4} style={{ marginBottom: 16, width: '100%' }}>
      <div
        style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}
      >
        <Typography.Title level={4} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
        {extra}
      </div>
      {description && <Typography.Text type="secondary">{description}</Typography.Text>}
    </Space>
  )
}
