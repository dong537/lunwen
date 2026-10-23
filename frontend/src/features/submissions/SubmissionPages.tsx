import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Upload, Download, ArrowLeft } from 'lucide-react'
import type { Submission } from '../../shared/api/contracts'
import { blocksCachedData, download } from '../../shared/api/http'
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
import { failureSummary, needsPolling, outcomeText } from './submission-state'
import { useSession } from '../auth/session'

function UploadForm() {
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [validation, setValidation] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const client = useQueryClient()
  const navigate = useNavigate()
  const upload = useMutation({
    mutationFn: submissionApi.upload,
  })
  function choose(files: FileList | null) {
    if (upload.isPending) return
    upload.reset()
    if (!files?.length) return
    const error = files.length !== 1 ? '每次只能提交一篇论文。' : validateFile(files[0], 'docx')
    setValidation(error)
    setFile(error ? null : files[0])
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (file && !validation) {
      upload.mutate(file, {
        // 离开上传页或切换账号后仍保留后台任务，但不再写回旧页面状态。
        onSuccess: (submitted) => {
          client.setQueryData(['submission', submitted.id], submitted)
          void client.invalidateQueries({ queryKey: ['submissions'] })
          navigate(`/submissions/${submitted.id}`)
        },
      })
    }
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
          disabled={upload.isPending}
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
              disabled={upload.isPending}
              onClick={() => {
                setFile(null)
                if (input.current) input.current.value = ''
              }}
            >
              移除
            </button>
          </div>
        )}
      </div>
      <ErrorNotice error={validation ?? upload.error} />
      <button className="button primary" disabled={!file || upload.isPending}>
        {upload.isPending ? '正在上传，请稍候…' : '提交审核'}
      </button>
    </form>
  )
}

export function StatusBadge({ submission }: { submission: Submission }) {
  return (
    <span className={`status-badge ${submission.status.toLowerCase()}`}>
      {outcomeText(submission.status, submission.problemCount)}
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
      {list.error && list.data ? (
        <p className="help" role="status">
          列表刷新失败，当前显示的是上次加载的记录，状态可能已过期。
        </p>
      ) : (
        <ErrorNotice error={list.error} retry={() => void list.refetch()} />
      )}
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
                        {item.status === 'FAILED' && (
                          <p className="help">{failureSummary(item.errorMessage)}</p>
                        )}
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
  const fileDownload = useMutation({
    mutationFn: ({ kind, name }: { kind: 'original' | 'marked' | 'report'; name: string }) =>
      download(`/submissions/${encodeURIComponent(id)}/files/${kind}`, name),
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
            {needsPolling(data.status) && <span className="muted">页面会自动更新审核进度。</span>}
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
            {data.status === 'SUCCESS' && data.canDownloadReport && (
              <button
                className="button secondary"
                disabled={fileDownload.isPending}
                onClick={() =>
                  fileDownload.mutate({
                    kind: 'report',
                    name: data.originalFilename.replace(/\.docx$/i, '-检查报告.docx'),
                  })
                }
              >
                <Download size={16} aria-hidden="true" />
                下载检查报告
              </button>
            )}
          </div>
          <ErrorNotice error={fileDownload.error} />
        </section>
      )}
    </>
  )
}
