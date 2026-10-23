export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId?: string,
    public fieldErrors: Record<string, string> = {},
  ) {
    super(message)
  }
}

export function blocksCachedData(error: unknown): boolean {
  return error instanceof ApiError && [401, 403, 404].includes(error.status)
}

interface Envelope<T> {
  data: T
  error: { code: string; message: string; fieldErrors?: Record<string, string> } | null
  requestId?: string
}
type Options = RequestInit & { json?: unknown }

async function readEnvelope<T>(response: Response): Promise<T> {
  let envelope: Envelope<T>
  try {
    envelope = (await response.json()) as Envelope<T>
  } catch {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务暂时无法响应，请稍后重试。')
  }
  if (
    !envelope ||
    typeof envelope !== 'object' ||
    !('data' in envelope) ||
    !('error' in envelope)
  ) {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回的数据不完整，请刷新后重试。')
  }
  if (!response.ok || envelope.error) {
    const error = new ApiError(
      response.status,
      envelope.error?.code ?? 'REQUEST_FAILED',
      envelope.error?.message ?? '请求未成功，请重试。',
      envelope.requestId,
      envelope.error?.fieldErrors,
    )
    if (
      response.status === 401 ||
      error.code === 'PASSWORD_CHANGE_REQUIRED' ||
      error.code === 'MUST_CHANGE_PASSWORD'
    ) {
      window.dispatchEvent(new CustomEvent('platform:auth', { detail: error }))
    }
    throw error
  }
  return envelope.data
}

async function request(path: string, options: Options): Promise<Response> {
  const { json, ...init } = options
  const headers = new Headers(init.headers)
  if (json !== undefined) {
    headers.set('Content-Type', 'application/json')
    init.body = JSON.stringify(json)
  }
  if (!['GET', 'HEAD'].includes((init.method ?? 'GET').toUpperCase())) {
    const csrf = await api<{ token: string; headerName: string }>('/auth/csrf', {
      signal: init.signal,
    })
    headers.set(csrf.headerName, csrf.token)
  }
  try {
    return await fetch(`/api${path}`, { ...init, headers, credentials: 'include' })
  } catch (error) {
    if (init.signal?.aborted) throw error
    throw new ApiError(0, 'NETWORK_ERROR', '网络连接失败，请检查网络后重试。')
  }
}

export async function api<T = unknown>(path: string, options: Options = {}): Promise<T> {
  return readEnvelope<T>(await request(path, options))
}

export async function download(path: string, fallbackName: string): Promise<void> {
  const response = await request(path, {})
  if (!response.ok) {
    await readEnvelope(response)
    return
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const filename = encoded
    ? decodeURIComponent(encoded)
    : (disposition.match(/filename="([^"]+)"/i)?.[1] ?? fallbackName)
  saveBlob(await response.blob(), filename)
}

export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename.replace(/[\\/\x00-\x1f]/g, '_')
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
