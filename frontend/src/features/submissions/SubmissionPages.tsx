import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Upload, Download, ArrowLeft } from 'lucide-react'
import type { Submission } from '../../shared/api/contracts'
import { ApiError, blocksCachedData, download, openDownloadUrl } from '../../shared/api/http'
import {
  Empty,
  ErrorNotice,
  Loading,
  PageHeading,
  Pagination,
  useListParams,
} from '../../shared/ui'
import { formatSize, formatTime, validateFile } from '../../shared/files'
import { submissionApi } from './api'
import { needsPolling, outcomeText } from './submission-state'
import { useSession } from '../auth/session'

function expiresSoon(value: string): boolean {
  return new Date(value).getTime() - Date.now() < 60_000
}

function UploadForm() {
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [validation, setValidation] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
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
    if (input.current) input.current.value = ''
  }, [selectedUserId, user])
  const busy = upload.isPending || retry.isPending
  function choose(files: FileList | null) {
    if (busy) return
    upload.reset()
    retry.reset()
    setProgress(null)
    setPendingId(null)
    setUploaded(false)
    setValidation(null)
    if (!files?.length) {
      setFile(null)
      setSelectedUserId(null)
      return
    }
    const error = files.length !== 1 ? '每次只能提交一篇论文。' : validateFile(files[0], 'docx')
    setValidation(error)
    setFile(error ? null : files[0])
    setSelectedUserId(error ? null : (user?.id ?? null))
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!file || validation) return
    if (selectedUserId && user?.id !== selectedUserId) {
      setFile(null)
      setSelectedUserId(null)
      setValidation('登录身份已变化，请重新选择文件。')
      if (input.current) input.current.value = ''
      return
    }
    upload.mutate(file, { onSuccess: completeSuccess })
  }
  return (
    <form className="panel upload-panel" onSubmit={submit} aria-label="提交论文">
      <div className="upload-intro">
        <Upload size={25} aria-hidden="true" />
        <div>
          <h2>提交新版本</h2>
          <p>支持 .docx 文件，最大 50 MB。审核完成后下载批注论文。</p>
        </div>
      </div>
      <div
        className={`file-drop ${dragging ? 'dragging' : ''}`}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDragging(false)
          choose(event.dataTransfer.files)
        }}
      >
        <label htmlFor="thesis-file">选择论文</label>
        <input
          ref={input}
          id="thesis-file"
          type="file"
          accept=".docx"
          disabled={busy}
          onChange={(event) => choose(event.target.files)}
        />
        <p className="help">也可以将文件拖放到此处。每次提交都会保留独立的审核记录。</p>
        {file && (
          <div className="selected-file">
            <span>{file.name}</span>
            <span>{formatSize(file.size)}</span>
            <button
              type="button"
              className="button plain"
              disabled={busy}
              onClick={() => {
                setFile(null)
                setSelectedUserId(null)
                if (input.current) input.current.value = ''
              }}
            >
              移除
            </button>
          </div>
        )}
      </div>
      {progress !== null && upload.isPending && (
        <p className="help" role="status">
          正在上传：{progress}%
        </p>
      )}
      {uploaded && pendingId && !busy && upload.isError && (
        <button type="button" className="button secondary" onClick={() => retry.mutate(pendingId)}>
          重试完成上传
        </button>
      )}
      <ErrorNotice error={validation ?? upload.error ?? retry.error} />
      <button className="button primary" disabled={!file || busy || mode.isPending}>
        {busy
          ? direct && progress !== null
            ? `正在上传 ${progress}%`
            : '正在上传，请稍候…'
          : '提交审核'}
      </button>
    </form>
  )
}

export function StatusBadge({ submission }: { submission: Submission }) {
  return (
    <span className={`status-badge ${submission.status.toLowerCase()}`}>
      {submission.status === 'PENDING' && submission.uploadReserved
        ? '上传中'
        : outcomeText(submission.status, submission.problemCount)}
    </span>
  )
}

export function SubmissionList({ studentId }: { studentId?: string }) {
  const { page, change } = useListParams()
  const list = useQuery({
    queryKey: ['submissions', studentId ?? 'mine', page],
    queryFn: ({ signal }) => submissionApi.list(page, signal, studentId),
    refetchInterval: (query) =>
      query.state.data?.items.some((item) => needsPolling(item.status)) ? 4000 : false,
  })
  if (blocksCachedData(list.error))
    return <ErrorNotice error={list.error} retry={() => void list.refetch()} />
  return (
    <section className="panel list-panel" aria-label="提交记录">
      <div className="section-heading">
        <h2>提交记录</h2>
        <button
          type="button"
          className="button plain"
          onClick={() => void list.refetch()}
          disabled={list.isFetching}
        >
          刷新记录
        </button>
      </div>
      {list.isPending && <Loading />}
      <ErrorNotice error={list.error} retry={() => void list.refetch()} />
      {list.data && (
        <>
          {list.data.items.length === 0 ? (
            <Empty>还没有提交记录。</Empty>
          ) : (
            <div className="table-scroll">
              <table>
                <caption className="sr-only">论文提交记录，按提交时间倒序</caption>
                <thead>
                  <tr>
                    <th>提交次数</th>
                    <th>文件名</th>
                    <th>提交时间</th>
                    <th>审核结果</th>
                    <th>
                      <span className="sr-only">操作</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {list.data.items.map((item) => (
                    <tr key={item.id}>
                      <td className="nowrap">第 {item.submissionNumber} 次</td>
                      <td className="filename">{item.originalFilename}</td>
                      <td className="nowrap">{formatTime(item.createdAt)}</td>
                      <td>
                        <StatusBadge submission={item} />
                      </td>
                      <td>
                        <Link
                          to={`/submissions/${item.id}`}
                          aria-label={`查看第 ${item.submissionNumber} 次提交`}
                        >
                          查看
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <Pagination
            page={page}
            size={list.data.size}
            total={list.data.total}
            onChange={(next) => change({ page: next })}
          />
        </>
      )}
    </section>
  )
}

export function StudentSubmissionsPage() {
  return (
    <>
      <PageHeading title="我的论文" description="提交论文后可离开页面，审核仍会继续。" />
      <UploadForm />
      <SubmissionList />
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
    mutationFn: async ({ kind, name }: { kind: 'original' | 'marked'; name: string }) => {
      if (mode.data === 'direct') {
        const ticket = await submissionApi.downloadUrl(id, kind)
        openDownloadUrl(ticket.url)
        return
      }
      await download(`/submissions/${encodeURIComponent(id)}/files/${kind}`, name)
    },
  })
  const data = blocksCachedData(detail.error) ? undefined : detail.data
  if (detail.isPending) return <Loading>正在读取提交记录…</Loading>
  const back =
    user?.role === 'TEACHER'
      ? data
        ? `/teacher/students/${data.studentId}`
        : '/teacher/students'
      : '/student/submissions'
  return (
    <>
      <Link className="back-link" to={back}>
        <ArrowLeft size={16} aria-hidden="true" />
        返回提交列表
      </Link>
      <PageHeading
        title={data ? `第 ${data.submissionNumber} 次提交` : '提交详情'}
        description={data?.originalFilename}
        action={
          <button
            className="button secondary"
            onClick={() => void detail.refetch()}
            disabled={detail.isFetching}
          >
            刷新状态
          </button>
        }
      />
      <ErrorNotice error={detail.error} retry={() => void detail.refetch()} />
      {detail.isError && data && (
        <p className="help">刷新未成功，以下为上次获取的状态。任务状态以刷新后的结果为准。</p>
      )}
      {data && (
        <section className="panel">
          <div className="result-heading" aria-live="polite">
            <StatusBadge submission={data} />
            {data.uploadReserved && (
              <span className="muted">文件正在上传，完成后自动进入审核。</span>
            )}
            {!data.uploadReserved && needsPolling(data.status) && (
              <span className="muted">页面会自动更新审核进度。</span>
            )}
          </div>
          <dl className="detail-grid">
            <dt>提交时间</dt>
            <dd>{formatTime(data.createdAt)}</dd>
            <dt>文件大小</dt>
            <dd>{formatSize(data.fileSize)}</dd>
            <dt>审核完成</dt>
            <dd>{formatTime(data.finishedAt)}</dd>
          </dl>
          {data.status === 'FAILED' && (
            <ErrorNotice
              error={data.errorMessage || '本次审核未完成，请重新提交论文或联系管理员。'}
            />
          )}
          {data.status === 'SUCCESS' && (
            <>
              <h2>审核结果</h2>
              <p>
                {data.problemCount === 0
                  ? '本次审核未发现格式问题。'
                  : data.problemCount !== null
                    ? `共发现 ${data.problemCount} 个问题，请下载批注论文查看修改建议。`
                    : '问题统计暂不可用，请刷新后查看。'}
              </p>
              {data.byScope && Object.keys(data.byScope).length > 0 && (
                <div className="scope-summary">
                  <h3>各部分问题数</h3>
                  <dl>
                    {Object.entries(data.byScope).map(([scope, count]) => (
                      <div key={scope}>
                        <dt>{scope}</dt>
                        <dd>{count}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              )}
            </>
          )}
          <div className="actions">
            {data.status !== 'FAILED' && data.canDownloadOriginal && (
              <button
                className="button secondary"
                disabled={fileDownload.isPending}
                onClick={() =>
                  fileDownload.mutate({ kind: 'original', name: data.originalFilename })
                }
              >
                <Download size={16} aria-hidden="true" />
                下载原始论文
              </button>
            )}
            {data.status === 'SUCCESS' && data.canDownloadMarked && (
              <button
                className="button primary"
                disabled={fileDownload.isPending}
                onClick={() =>
                  fileDownload.mutate({
                    kind: 'marked',
                    name: data.originalFilename.replace(/\.docx$/i, '.marked.docx'),
                  })
                }
              >
                <Download size={16} aria-hidden="true" />
                下载批注论文
              </button>
            )}
          </div>
          <ErrorNotice error={fileDownload.error} />
        </section>
      )}
    </>
  )
}
