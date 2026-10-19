import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, Navigate } from 'react-router-dom'
import { api } from '../../shared/api/http'
import type { User } from '../../shared/api/contracts'
import { ErrorNotice, Loading, PageHeading } from '../../shared/ui'
import { homeFor, roleNames, useSession } from './session'

export function LoginPage() {
  const { user, loading, error, refresh, setUser } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [validation, setValidation] = useState<string | null>(null)
  const login = useMutation({
    mutationFn: () =>
      api<User>('/auth/login', { method: 'POST', json: { username: username.trim(), password } }),
    onSuccess: setUser,
  })
  if (loading) return <Loading />
  if (user)
    return (
      <Navigate to={user.mustChangePassword ? '/change-password' : homeFor(user.role)} replace />
    )
  function submit(event: FormEvent) {
    event.preventDefault()
    if ([...password].length > 128) {
      setValidation('密码不能超过 128 个字符。')
      return
    }
    setValidation(null)
    login.mutate()
  }
  return (
    <main className="auth-page">
      <div className="auth-intro">
        <div className="brand-mark">论文</div>
        <p className="eyebrow">山东石油化工学院</p>
        <h1>论文审核平台</h1>
        <p>提交论文，查看审核进度，下载批注结果。</p>
      </div>
      <section className="panel auth-card" aria-labelledby="login-heading">
        <h2 id="login-heading">登录账号</h2>
        <p className="muted">使用管理员分配的学号或工号登录。</p>
        <ErrorNotice error={error} retry={refresh} />
        <form className="form-stack" onSubmit={submit}>
          <label>
            账号
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              required
              autoComplete="username"
              maxLength={32}
              autoFocus
            />
          </label>
          <label>
            密码
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              autoComplete="current-password"
              maxLength={256}
            />
          </label>
          <ErrorNotice error={validation ?? login.error} />
          <button
            className="button primary"
            disabled={login.isPending || !username.trim() || !password}
          >
            {login.isPending ? '正在登录…' : '登录'}
          </button>
        </form>
        <p className="help">忘记密码或账号被停用，请联系管理员。</p>
      </section>
    </main>
  )
}

export function PasswordPage() {
  const { user, setUser } = useSession()
  const [oldPassword, setOld] = useState('')
  const [newPassword, setNew] = useState('')
  const [confirmPassword, setConfirm] = useState('')
  const [validation, setValidation] = useState<string | null>(null)
  const change = useMutation({
    mutationFn: () =>
      api<User>('/auth/password', {
        method: 'POST',
        json: { oldPassword, newPassword, confirmPassword },
      }),
    onSuccess: async (updated) => {
      await setUser(updated)
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    const length = [...newPassword].length
    const reason =
      length < 6 || length > 128
        ? '新密码需为 6–128 个字符。'
        : newPassword === oldPassword
          ? '新密码不能与当前密码相同。'
          : newPassword !== confirmPassword
            ? '两次输入的新密码不一致。'
            : null
    setValidation(reason)
    if (!reason) change.mutate()
  }
  if (change.isSuccess && user && !user.mustChangePassword) {
    return <Navigate to={homeFor(user.role)} replace />
  }
  return (
    <div className="narrow">
      <PageHeading
        title={user?.mustChangePassword ? '设置新密码' : '修改密码'}
        description={
          user?.mustChangePassword
            ? '首次登录或密码重置后，请先设置自己的密码。'
            : '新密码需为 6–128 个字符，不能与当前密码相同。'
        }
      />
      <form className="panel form-stack" onSubmit={submit}>
        <label>
          当前密码
          <input
            type="password"
            value={oldPassword}
            onChange={(event) => setOld(event.target.value)}
            required
            autoComplete="current-password"
          />
        </label>
        <label>
          新密码
          <input
            type="password"
            value={newPassword}
            onChange={(event) => setNew(event.target.value)}
            required
            autoComplete="new-password"
          />
        </label>
        <label>
          再次输入新密码
          <input
            type="password"
            value={confirmPassword}
            onChange={(event) => setConfirm(event.target.value)}
            required
            autoComplete="new-password"
          />
        </label>
        <ErrorNotice error={validation ?? change.error} />
        <div className="actions">
          <button className="button primary" disabled={change.isPending}>
            {change.isPending ? '正在保存…' : '保存新密码'}
          </button>
          {!user?.mustChangePassword && (
            <Link className="button secondary" to="/profile">
              取消
            </Link>
          )}
        </div>
      </form>
    </div>
  )
}

export function ProfilePage() {
  const { user } = useSession()
  if (!user) return null
  return (
    <div className="narrow">
      <PageHeading title="个人信息" description="如需更正个人信息，请联系管理员。" />
      <section className="panel">
        <dl className="detail-grid">
          <dt>姓名</dt>
          <dd>{user.name}</dd>
          <dt>{user.role === 'STUDENT' ? '学号' : '账号'}</dt>
          <dd>{user.username}</dd>
          <dt>身份</dt>
          <dd>{roleNames[user.role]}</dd>
          {user.role === 'STUDENT' && (
            <>
              <dt>专业</dt>
              <dd>{user.major || '未填写'}</dd>
              <dt>指导教师</dt>
              <dd>{user.teacherName || '未分配'}</dd>
            </>
          )}
          <dt>账号状态</dt>
          <dd>{user.active ? '启用' : '停用'}</dd>
        </dl>
        <Link className="button secondary" to="/change-password">
          修改密码
        </Link>
      </section>
    </div>
  )
}
