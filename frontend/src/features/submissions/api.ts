import { api, ApiError } from '../../shared/api/http'
import type {
  Page,
  Submission,
  DownloadTicket,
  TransferMode,
  UploadTicket,
} from '../../shared/api/contracts'

function requestId(xhr: XMLHttpRequest): string | undefined {
  try {
    return xhr.getResponseHeader('x-cos-request-id') ?? undefined
  } catch {
    return undefined
  }
}

function uploadToCos(
  ticket: UploadTicket,
  file: File,
  onProgress?: (percent: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    let settled = false
    const fail = (status: number, code: string, message: string) => {
      if (settled) return
      settled = true
      reject(new ApiError(status, code, message, requestId(xhr)))
    }
    xhr.open('PUT', ticket.url, true)
    xhr.withCredentials = false
    xhr.timeout = 11 * 60 * 1000
    Object.entries(ticket.headers).forEach(([name, value]) => {
      xhr.setRequestHeader(name, value)
    })
    if (xhr.upload) {
      xhr.upload.onprogress = (event) => {
        if (settled || !event.lengthComputable || event.total <= 0) return
        onProgress?.(Math.min(100, Math.round((event.loaded / event.total) * 100)))
      }
    }
    xhr.onload = () => {
      if (settled) return
      if (xhr.status >= 200 && xhr.status < 300) {
        settled = true
        onProgress?.(100)
        resolve()
        return
      }
      if (xhr.status === 403) {
        fail(403, 'COS_FORBIDDEN', '上传凭证已过期或无效，请重试')
      } else if (xhr.status === 400 || xhr.status === 412) {
        fail(xhr.status, 'COS_REJECTED', '上传文件与凭证不匹配，请重新选择文件')
      } else if (xhr.status === 413) {
        fail(413, 'COS_TOO_LARGE', '论文文件不能超过 50MiB')
      } else {
        fail(xhr.status, 'COS_UPLOAD_FAILED', '直传对象存储失败，请稍后重试')
      }
    }
    xhr.onerror = () =>
      fail(0, 'COS_NETWORK', '浏览器直传对象存储失败，请检查网络或联系管理员检查 COS 跨域配置')
    xhr.ontimeout = () => fail(0, 'COS_TIMEOUT', '上传超时，请重试')
    xhr.onabort = () => fail(0, 'COS_ABORTED', '上传已取消')
    const abort = () => xhr.abort()
    if (signal?.aborted) {
      fail(0, 'COS_ABORTED', '上传已取消')
      return
    }
    signal?.addEventListener('abort', abort, { once: true })
    xhr.send(file)
  })
}

export const submissionApi = {
  list: (page: number, signal?: AbortSignal, studentId?: string) =>
    api<Page<Submission>>(
      `${studentId ? `/teacher/students/${encodeURIComponent(studentId)}` : ''}/submissions?page=${page}&size=20`,
      { signal },
    ),
  get: (id: string, signal?: AbortSignal) =>
    api<Submission>(`/submissions/${encodeURIComponent(id)}`, { signal }),
  upload: (file: File) => {
    const body = new FormData()
    body.append('file', file)
    return api<Submission>('/submissions', { method: 'POST', body })
  },
  transferMode: (signal?: AbortSignal) =>
    api<{ mode: TransferMode }>('/submissions/transfer-mode', { signal }).then(
      (value) => value.mode,
    ),
  createUpload: (filename: string, size: number) =>
    api<UploadTicket>('/submissions/uploads', {
      method: 'POST',
      json: { filename, size },
    }),
  refreshUpload: (id: string) =>
    api<UploadTicket>(`/submissions/${encodeURIComponent(id)}/upload-url`, { method: 'POST' }),
  completeUpload: (id: string) =>
    api<Submission>(`/submissions/${encodeURIComponent(id)}/upload-complete`, { method: 'POST' }),
  downloadUrl: (id: string, kind: 'original' | 'marked' | 'report' | 'report-pdf') =>
    api<DownloadTicket>(`/submissions/${encodeURIComponent(id)}/files/${kind}/url`),
  uploadToCos,
}
