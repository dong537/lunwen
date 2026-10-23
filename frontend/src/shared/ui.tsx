import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiError } from './api/http'

export function Loading({ children = '正在加载…' }: { children?: ReactNode }) {
  return (
    <div className="notice loading" role="status">
      <span className="spinner" aria-hidden="true" />
      {children}
    </div>
  )
}
export function ErrorNotice({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null
  return (
    <div className="notice error" role="alert">
      <div>
        {error instanceof Error ? error.message : String(error)}
        {error instanceof ApiError && Object.keys(error.fieldErrors).length > 0 && (
          <ul>
            {Object.entries(error.fieldErrors).map(([field, message]) => (
              <li key={field}>{message}</li>
            ))}
          </ul>
        )}
        {error instanceof ApiError && error.requestId && (
          <small>联系管理员时可提供编号：{error.requestId}</small>
        )}
      </div>
      {retry && (
        <button type="button" className="button secondary" onClick={retry}>
          重试
        </button>
      )}
    </div>
  )
}
export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>
}
export function PageHeading({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="page-heading">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </div>
  )
}
export function useListParams() {
  const [params, setParams] = useSearchParams()
  const rawPage = Number(params.get('page'))
  const page = Number.isSafeInteger(rawPage) && rawPage > 0 ? rawPage : 1
  const change = (values: Record<string, string | number | null>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(values)) {
      if (value === null || value === '') next.delete(key)
      else next.set(key, String(value))
    }
    setParams(next)
  }
  return { params, page, change }
}
export function SearchForm({
  value,
  placeholder = '按账号或姓名搜索',
  onSearch,
}: {
  value: string
  placeholder?: string
  onSearch: (value: string) => void
}) {
  const [draft, setDraft] = useState(value)
  useEffect(() => setDraft(value), [value])
  function submit(event: FormEvent) {
    event.preventDefault()
    onSearch(draft.trim())
  }
  return (
    <form className="search-form" role="search" onSubmit={submit}>
      <label className="sr-only" htmlFor="list-search">
        {placeholder}
      </label>
      <input
        id="list-search"
        type="search"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={placeholder}
        maxLength={100}
      />
      <button className="button secondary">搜索</button>
    </form>
  )
}
export function Pagination({
  page,
  size,
  total,
  onChange,
}: {
  page: number
  size: number
  total: number
  onChange: (page: number) => void
}) {
  const pages = Math.max(1, Math.ceil(total / size))
  return (
    <nav className="pagination" aria-label="分页">
      <span>
        共 {total} 条 · 第 {page} / {pages} 页
      </span>
      <button className="button secondary" disabled={page <= 1} onClick={() => onChange(page - 1)}>
        上一页
      </button>
      <button
        className="button secondary"
        disabled={page >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </button>
    </nav>
  )
}
export function Modal({
  title,
  children,
  onClose,
  busy = false,
}: {
  title: string
  children: ReactNode
  onClose: () => void
  busy?: boolean
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => {
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = ref.current!
    dialog.showModal()
    return () => {
      dialog.close()
      trigger?.focus()
    }
  }, [])
  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      className="modal"
      onCancel={(event) => {
        event.preventDefault()
        if (!busy) onClose()
      }}
    >
      <div className="modal-heading">
        <h2 id={titleId}>{title}</h2>
        <button
          type="button"
          aria-label="关闭"
          className="button plain"
          onClick={onClose}
          disabled={busy}
        >
          ×
        </button>
      </div>
      {children}
    </dialog>
  )
}
