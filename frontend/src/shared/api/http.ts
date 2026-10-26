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

interface CsrfToken {
  token: string
  headerName: string
}

// CSRF 令牌与会话绑定，仅登录/改密/登出时轮换； mutation 级缓存。
// 403 时先强制刷新一次令牌：只有令牌真的变了才重试，否则视为真实无权限，直接返回原响应，避免非幂等操作被发两遍。
let cachedCsrf: CsrfToken | null = null
let csrfInflight: Promise<CsrfToken> | null = null

export function clearCsrfCache(): void {
  cachedCsrf = null
  csrfInflight = null
}

async function getCsrf(): Promise<CsrfToken> {
  if (cachedCsrf) return cachedCsrf
  if (!csrfInflight) {
    // 不复用业务请求的 abort signal，避免组件卸载连带 abort 共享的 csrf 请求。
    csrfInflight = api<CsrfToken>('/auth/csrf', {}).finally(() => {
      csrfInflight = null
    })
  }
  cachedCsrf = await csrfInflight
  return cachedCsrf
}

async function refreshCsrfForce(): Promise<CsrfToken | null> {
  cachedCsrf = null
  csrfInflight = null
  try {
    return await getCsrf()
  } catch {
    return null
  }
}

function isUnsafeMethod(method: string): boolean {
  return !['GET', 'HEAD'].includes(method.toUpperCase())
}

async function request(path: string, options: Options, retried = false): Promise<Response> {
  const { json, ...init } = options
  const headers = new Headers(init.headers)
  if (json !== undefined) {
    headers.set('Content-Type', 'application/json')
    init.body = JSON.stringify(json)
  }
  const unsafe = isUnsafeMethod(init.method ?? 'GET')
  let usedCsrf: CsrfToken | null = null
  if (unsafe) {
    const csrf = await getCsrf()
    usedCsrf = csrf
    headers.set(csrf.headerName, csrf.token)
  }
  try {
    const response = await fetch(`/api${path}`, { ...init, headers, credentials: 'include' })
    if (unsafe && !retried && response.status === 403) {
      const fresh = await refreshCsrfForce()
      if (
        !fresh ||
        (usedCsrf && fresh.token === usedCsrf.token && fresh.headerName === usedCsrf.headerName)
      ) {
        return response
      }
      return request(path, options, true)
    }
    return response
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

export function openDownloadUrl(url: string): void {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.target = '_blank'
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
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
