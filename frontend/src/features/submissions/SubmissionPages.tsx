import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Progress,
  Row,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  Upload,
  type UploadProps,
} from 'antd'
import {
  ArrowLeftOutlined,
  DownloadOutlined,
  InboxOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import type { RcFile } from 'antd/es/upload'
import type { ColumnsType } from 'antd/es/table'
import type { Submission } from '../../shared/api/contracts'
import { ApiError, blocksCachedData, download, openDownloadUrl } from '../../shared/api/http'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { formatSize, formatTime, validateFile } from '../../shared/files'
import { submissionApi } from './api'
import { needsPolling, outcomeText } from './submission-state'
import { useSession } from '../auth/session'

function expiresSoon(value: string): boolean {
  return new Date(value).getTime() - Date.now() < 60_000
}

function UploadForm({ compact = false }: { compact?: boolean }) {
  const [file, setFile] = useState<File | null>(null)
  const [validation, setValidation] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [pendingId, setPendingId] = useState<string | null>(null)
  const [uploaded, setUploaded] = useState(false)
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)
  const { user } = useSession()
  const client = useQueryClient()
  const navigate = useNavigate()
  const mode = useQuery({
    queryKey: ['transfer-mode'],
    queryFn: ({ signal }) => submissionApi.transferMode(signal),
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
  const direct = mode.data === 'direct'
  function refreshSubmissionList() {
    void client.invalidateQueries({ queryKey: ['submissions'] })
  }
  function completeSuccess(submitted: Submission) {
    if (!mountedRef.current) return
    abortRef.current = null
    setPendingId(null)
    setUploaded(false)
    setProgress(null)
    client.setQueryData(['submission', submitted.id], submitted)
    refreshSubmissionList()
    navigate(`/submissions/${submitted.id}`)
  }
  const retry = useMutation({
    mutationFn: async (id: string) => {
      const submitted = await submissionApi.completeUpload(id)
      refreshSubmissionList()
      return submitted
    },
    onSuccess: completeSuccess,
    onError: refreshSubmissionList,
  })
  const upload = useMutation({
    mutationFn: async (selected: File) => {
      if (!direct) {
        const submitted = await submissionApi.upload(selected)
        refreshSubmissionList()
        return submitted
      }
      setProgress(0)
      const controller = new AbortController()
      abortRef.current = controller
      let ticket = await submissionApi.createUpload(selected.name, selected.size)
      setPendingId(ticket.submissionId)
      if (expiresSoon(ticket.expiresAt))
        ticket = await submissionApi.refreshUpload(ticket.submissionId)
      try {
        await submissionApi.uploadToCos(ticket, selected, setProgress, controller.signal)
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 403) throw error
        ticket = await submissionApi.refreshUpload(ticket.submissionId)
        await submissionApi.uploadToCos(ticket, selected, setProgress, controller.signal)
      }
      setUploaded(true)
      const submitted = await submissionApi.completeUpload(ticket.submissionId)
      refreshSubmissionList()
      return submitted
    },
    onSuccess: completeSuccess,
    onError: refreshSubmissionList,
  })
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])
  useEffect(() => {
    if (!selectedUserId || !user || user.id === selectedUserId) return
    abortRef.current?.abort()
    abortRef.current = null
    setFile(null)
    setProgress(null)
    setPendingId(null)
    setUploaded(false)
    setSelectedUserId(null)
    setValidation('登录身份已变化，请重新选择文件。')
  }, [selectedUserId, user])
  const busy = upload.isPending || retry.isPending
  const beforeUpload: UploadProps['beforeUpload'] = (next) => {
    if (busy) return Upload.LIST_IGNORE
    upload.reset()
    retry.reset()
    setProgress(null)
    setPendingId(null)
    setUploaded(false)
    setValidation(null)
    const error = validateFile(next as File, 'docx')
    setValidation(error)
    if (error) {
      setFile(null)
      setSelectedUserId(null)
      return Upload.LIST_IGNORE
    }
    setFile(next as File)
    setSelectedUserId(user?.id ?? null)
    return false
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!file || validation) return
    if (selectedUserId && user?.id !== selectedUserId) {
      setFile(null)
      setSelectedUserId(null)
      setValidation('登录身份已变化，请重新选择文件。')
      return
    }
    upload.mutate(file)
  }
  return (
    <Card
      title="提交新版本"
      size="small"
      className={compact ? 'upload-compact' : undefined}
      style={compact ? { height: '100%' } : { marginBottom: 16 }}
    >
      <Typography.Paragraph type="secondary">
        支持 .docx 文件，最大 50 MB。审核完成后下载批注论文。
      </Typography.Paragraph>
      <form onSubmit={submit}>
        <Upload.Dragger
          accept=".docx"
          multiple={false}
          maxCount={1}
          fileList={
            file
              ? [
                  { uid: file.name, name: file.name, size: file.size, status: 'done' } as RcFile & {
                    status: 'done'
                  },
                ]
              : []
          }
          beforeUpload={beforeUpload}
          onRemove={() => {
            if (busy) return false
            setFile(null)
            setSelectedUserId(null)
            return true
          }}
          disabled={busy}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击选择或将论文拖放到此处</p>
          <p className="ant-upload-hint">每次只能提交一篇论文，每次提交都会保留独立的审核记录。</p>
        </Upload.Dragger>
        {progress !== null && upload.isPending && (
          <Progress percent={progress} status="active" style={{ marginTop: 12 }} />
        )}
        <ApiAlert error={validation ?? upload.error ?? retry.error} />
        <Space style={{ marginTop: 16, width: '100%', justifyContent: 'center' }}>
          <Button
            type="primary"
            htmlType="submit"
            loading={busy}
            disabled={!file || busy || mode.isPending}
          >
            {busy
              ? direct && progress !== null
                ? `正在上传 ${progress}%`
                : '正在上传，请稍候…'
              : '提交审核'}
          </Button>
          {pendingId && !busy && upload.isError && (
            <Button
              onClick={() => {
                if (uploaded) {
                  retry.mutate(pendingId)
                } else if (file) {
                  upload.mutate(file)
                }
              }}
            >
              {uploaded ? '重试完成上传' : '重新上传'}
            </Button>
          )}
        </Space>
      </form>
    </Card>
  )
}

export function StatusTag({ submission }: { submission: Submission }) {
  const color =
    submission.status === 'SUCCESS'
      ? 'success'
      : submission.status === 'FAILED'
        ? 'error'
        : submission.status === 'PROCESSING'
          ? 'processing'
          : 'default'
  const text =
    submission.status === 'PENDING' && submission.uploadReserved
      ? '上传中'
      : outcomeText(submission.status, submission.problemCount)
  return <Tag color={color}>{text}</Tag>
}

const listColumns: ColumnsType<Submission> = [
  {
    title: '提交次数',
    dataIndex: 'submissionNumber',
    key: 'submissionNumber',
    width: 76,
    render: (value: number) => `第 ${value} 次`,
  },
  {
    title: '文件名',
    dataIndex: 'originalFilename',
    key: 'originalFilename',
    ellipsis: { showTitle: false },
    render: (value: string) => (
      <Tooltip title={value} placement="topLeft" mouseEnterDelay={0.3}>
        {value}
      </Tooltip>
    ),
  },
  {
    title: '提交时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 170,
    responsive: ['sm'],
    render: (value: string) => formatTime(value),
  },
  {
    title: '审核结果',
    dataIndex: 'status',
    key: 'status',
    width: 110,
    responsive: ['sm'],
    render: (_: unknown, record: Submission) => <StatusTag submission={record} />,
  },
  {
    title: '操作',
    key: 'action',
    width: 48,
    render: (_: unknown, record: Submission) => <Link to={`/submissions/${record.id}`}>查看</Link>,
  },
]

export function SubmissionList({
  studentId,
  pageSize = 20,
  fixedHeight = false,
}: {
  studentId?: string
  pageSize?: number
  fixedHeight?: boolean
}) {
  const [page, setPage] = useState(1)
  const list = useQuery({
    queryKey: ['submissions', studentId ?? 'mine', page, pageSize],
    queryFn: ({ signal }) => submissionApi.list(page, signal, studentId, pageSize),
    refetchInterval: (query) =>
      query.state.data?.items.some((item) => needsPolling(item.status)) ? 4000 : false,
  })
  if (blocksCachedData(list.error))
    return <ApiAlert error={list.error} retry={() => void list.refetch()} />
  return (
    <Card
      title="提交记录"
      className="table-card"
      style={fixedHeight ? { minHeight: 606 } : undefined}
      extra={
        <Button
          type="text"
          icon={<ReloadOutlined />}
          loading={list.isFetching}
          onClick={() => void list.refetch()}
        >
          刷新记录
        </Button>
      }
    >
      {list.isPending && <CenterSpin />}
      <ApiAlert error={list.error} retry={() => void list.refetch()} />
      {list.data && (
        <Table<Submission>
          rowKey="id"
          size="small"
          columns={listColumns}
          dataSource={list.data.items}
          tableLayout="fixed"
          pagination={{
            current: page,
            pageSize,
            total: list.data.total,
            showTotal: (total) => `共 ${total} 条`,
            showQuickJumper: true,
            onChange: setPage,
          }}
          locale={{ emptyText: <Empty description="还没有提交记录。" /> }}
        />
      )}
    </Card>
  )
}

export function StudentSubmissionsPage() {
  useDocumentTitle('我的论文')
  return (
    <>
      <PageHeader title="我的论文" />
      <Row gutter={16} align="stretch">
        <Col xs={24} xl={9}>
          <UploadForm compact />
        </Col>
        <Col xs={24} xl={15}>
          <SubmissionList pageSize={10} fixedHeight />
        </Col>
      </Row>
    </>
  )
}

export function SubmissionDetailPage() {
  const { id = '' } = useParams()
  const { user } = useSession()
  const detail = useQuery({
    queryKey: ['submission', id],
    queryFn: ({ signal }) => submissionApi.get(id, signal),
    refetchInterval: (query) =>
      query.state.data && needsPolling(query.state.data.status) ? 3000 : false,
  })
  const mode = useQuery({
    queryKey: ['transfer-mode'],
    queryFn: ({ signal }) => submissionApi.transferMode(signal),
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
  const fileDownload = useMutation({
    mutationFn: async ({
      kind,
      name,
    }: {
      kind: 'original' | 'marked' | 'report'
      name: string
    }) => {
      if (mode.data === 'direct') {
        const ticket = await submissionApi.downloadUrl(id, kind)
        openDownloadUrl(ticket.url)
        return
      }
      await download(`/submissions/${encodeURIComponent(id)}/files/${kind}`, name)
    },
  })
  const data = blocksCachedData(detail.error) ? undefined : detail.data
  useDocumentTitle(data ? `第 ${data.submissionNumber} 次提交` : '提交详情')
  if (detail.isPending) return <CenterSpin tip="正在读取提交记录…" />
  const back =
    user?.role === 'TEACHER'
      ? data
        ? `/teacher/students/${data.studentId}`
        : '/teacher/students'
      : '/student/submissions'
  return (
    <>
      <Link to={back}>
        <Button type="link" icon={<ArrowLeftOutlined />} style={{ paddingLeft: 0 }}>
          返回提交列表
        </Button>
      </Link>
      <PageHeader
        title={data ? `第 ${data.submissionNumber} 次提交` : '提交详情'}
        description={data?.originalFilename}
        extra={
          <Button loading={detail.isFetching} onClick={() => void detail.refetch()}>
            刷新状态
          </Button>
        }
      />
      <ApiAlert error={detail.error} retry={() => void detail.refetch()} />
      {detail.isError && data && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title="刷新未成功，以下为上次获取的状态。任务状态以刷新后的结果为准。"
        />
      )}
      {data && (
        <Card>
          <Space size={16} align="center" wrap>
            <StatusTag submission={data} />
            {data.uploadReserved && (
              <Typography.Text type="secondary">文件正在上传，完成后自动进入审核。</Typography.Text>
            )}
            {!data.uploadReserved && needsPolling(data.status) && (
              <Typography.Text type="secondary">页面会自动更新审核进度。</Typography.Text>
            )}
          </Space>
          <Descriptions column={1} bordered style={{ marginTop: 16 }}>
            <Descriptions.Item label="提交时间">{formatTime(data.createdAt)}</Descriptions.Item>
            <Descriptions.Item label="文件大小">{formatSize(data.fileSize)}</Descriptions.Item>
            <Descriptions.Item label="审核完成时间">
              {formatTime(data.finishedAt)}
            </Descriptions.Item>
          </Descriptions>
          {data.status === 'FAILED' && (
            <Alert
              type="error"
              showIcon
              style={{ marginTop: 16 }}
              title={data.errorMessage || '本次审核未完成，请重新提交论文或联系管理员。'}
            />
          )}
          {data.status === 'SUCCESS' && (
            <div style={{ marginTop: 16 }}>
              <Typography.Title level={5}>审核结果</Typography.Title>
              <Typography.Paragraph>
                {data.problemCount === 0
                  ? '本次审核未发现格式问题。'
                  : data.problemCount !== null
                    ? `共发现 ${data.problemCount} 个问题，请下载批注论文查看修改建议。`
                    : '问题统计暂不可用，请刷新后查看。'}
              </Typography.Paragraph>
              {data.byScope && Object.keys(data.byScope).length > 0 && (
                <Table
                  size="small"
                  rowKey="scope"
                  pagination={false}
                  dataSource={Object.entries(data.byScope).map(([scope, count]) => ({
                    scope,
                    count,
                  }))}
                  columns={[
                    { title: '部分', dataIndex: 'scope', key: 'scope' },
                    { title: '问题数', dataIndex: 'count', key: 'count', width: 120 },
                  ]}
                />
              )}
            </div>
          )}
          <Space wrap style={{ marginTop: 16 }}>
            {data.status !== 'FAILED' && data.canDownloadOriginal && (
              <Button
                icon={<DownloadOutlined />}
                loading={fileDownload.isPending}
                onClick={() =>
                  fileDownload.mutate({ kind: 'original', name: data.originalFilename })
                }
              >
                下载原始论文
              </Button>
            )}
            {data.status === 'SUCCESS' && data.canDownloadMarked && (
              <Button
                type="primary"
                icon={<DownloadOutlined />}
                loading={fileDownload.isPending}
                onClick={() =>
                  fileDownload.mutate({
                    kind: 'marked',
                    name: data.originalFilename.replace(/\.docx$/i, '.marked.docx'),
                  })
                }
              >
                下载批注论文
              </Button>
            )}
            {data.status === 'SUCCESS' && data.canDownloadReport && (
              <Button
                icon={<DownloadOutlined />}
                loading={fileDownload.isPending}
                onClick={() =>
                  fileDownload.mutate({
                    kind: 'report',
                    name: data.originalFilename.replace(/\.docx$/i, '-检查报告.docx'),
                  })
                }
              >
                下载检查报告
              </Button>
            )}
          </Space>
          <ApiAlert error={fileDownload.error} />
        </Card>
      )}
    </>
  )
}
