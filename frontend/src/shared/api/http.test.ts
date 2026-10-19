import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './http'

afterEach(() => vi.unstubAllGlobals())
const response = (data: unknown, status = 200) =>
  new Response(JSON.stringify({ data, error: null, requestId: 'test' }), { status })

describe('平台请求契约', () => {
  it('读取数据，保留字符串 ID，使用同源会话', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ id: '9223372036854775807' }))
    vi.stubGlobal('fetch', fetcher)
    expect(await api('/auth/me')).toEqual({ id: '9223372036854775807' })
    expect(fetcher).toHaveBeenCalledWith(
      '/api/auth/me',
      expect.objectContaining({ credentials: 'include' }),
    )
  })
  it('提交前取最新 CSRF；不设置 multipart Content-Type', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response({ token: 'fresh', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response({ id: '4' }, 202))
    vi.stubGlobal('fetch', fetcher)
    const body = new FormData()
    body.append('file', new File(['word'], '论文.docx'))
    await api('/submissions', { method: 'POST', body })
    const options = fetcher.mock.calls[1][1] as RequestInit
    expect(new Headers(options.headers).get('X-CSRF-TOKEN')).toBe('fresh')
    expect(new Headers(options.headers).has('Content-Type')).toBe(false)
    expect(options.body).toBe(body)
  })
  it('403 保留错误码与中文原因，不返回空数据', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            data: null,
            error: { code: 'FORBIDDEN', message: '无权查看这次提交' },
            requestId: 'r1',
          }),
          { status: 403 },
        ),
      ),
    )
    await expect(api('/submissions/9')).rejects.toMatchObject({
      status: 403,
      code: 'FORBIDDEN',
      message: '无权查看这次提交',
      requestId: 'r1',
    })
  })
  it('网关 HTML 错误不能被当作成功', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('<html>502</html>', { status: 502 })),
    )
    await expect(api('/submissions')).rejects.toBeInstanceOf(ApiError)
  })
  it('保留后端字段校验原因，供表单显示', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            data: null,
            error: {
              code: 'VALIDATION_ERROR',
              message: '请检查填写内容',
              fieldErrors: { name: '姓名不能为空' },
            },
            requestId: 'r2',
          }),
          { status: 400 },
        ),
      ),
    )
    await expect(api('/admin/users')).rejects.toMatchObject({
      fieldErrors: { name: '姓名不能为空' },
    })
  })
})
