import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Plus } from 'lucide-react'
import type { AccountRole, User } from '../../shared/api/contracts'
import {
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  PageHeading,
  Pagination,
  SearchForm,
  useListParams,
} from '../../shared/ui'
import { userApi } from './api'
import { UserForm } from './UserForm'

export function AdminDashboardPage() {
  const dashboard = useQuery({
    queryKey: ['dashboard'],
    queryFn: ({ signal }) => userApi.dashboard(signal),
  })
  return (
    <>
      <PageHeading title="平台概览" description="管理学生与教师账号，查看平台使用情况。" />
      <ErrorNotice error={dashboard.error} retry={() => void dashboard.refetch()} />
      {dashboard.isPending && <Loading />}
      {dashboard.data && (
        <dl className="overview">
          <div>
            <dt>启用学生</dt>
            <dd>{dashboard.data.activeStudents}</dd>
          </div>
          <div>
            <dt>启用教师</dt>
            <dd>{dashboard.data.activeTeachers}</dd>
          </div>
          <div>
            <dt>累计提交</dt>
            <dd>{dashboard.data.totalSubmissions}</dd>
          </div>
        </dl>
      )}
      <section className="panel">
        <h2>账号维护</h2>
        <p className="muted">新建账号或从 Excel 导入，分配指导教师，维护账号状态。</p>
        <div className="actions">
          <Link className="button primary" to="/admin/users">
            管理账号
          </Link>
          <Link className="button secondary" to="/admin/imports">
            批量导入
          </Link>
        </div>
      </section>
    </>
  )
}

type Action = { kind: 'active' | 'reset'; user: User }

function AccountAction({
  action,
  onClose,
  onSaved,
}: {
  action: Action
  onClose: () => void
  onSaved: (message: string) => void
}) {
  const { user, kind } = action
  const verb = kind === 'reset' ? '重置密码' : user.active ? '停用账号' : '启用账号'
  const submit = useMutation({
    mutationFn: async () => {
      if (kind === 'reset') {
        await userApi.reset(user.id)
        return `已将 ${user.name}（${user.username}）的密码重置为 123456，下次登录需重新设置密码。`
      }
      const result = await userApi.active(user.id, !user.active)
      const notice =
        !result.user.active && user.role === 'TEACHER' && result.assignedStudents > 0
          ? `该教师名下还有 ${result.assignedStudents} 名学生，请及时重新分配。`
          : ''
      return `已${result.user.active ? '启用' : '停用'} ${user.name}（${user.username}）。${result.message || notice}`
    },
    onSuccess: onSaved,
  })
  return (
    <Modal title={verb} onClose={onClose} busy={submit.isPending}>
      <p>
        账号：
        <strong>
          {user.name}（{user.username}）
        </strong>
      </p>
      <p className="muted">
        {kind === 'reset'
          ? '密码将重置为 123456，当前登录会失效，下次登录必须重新设置密码。'
          : user.active
            ? '停用后该账号无法登录，已有论文与提交记录保留。'
            : '启用后该账号可以继续登录使用。'}
      </p>
      <ErrorNotice error={submit.error} />
      <div className="actions">
        <button
          className={`button ${kind === 'reset' || user.active ? 'danger' : 'primary'}`}
          disabled={submit.isPending}
          onClick={() => submit.mutate()}
        >
          {submit.isPending
            ? '正在处理…'
            : kind === 'reset'
              ? '确认重置'
              : user.active
                ? '确认停用'
                : '确认启用'}
        </button>
        <button className="button secondary" disabled={submit.isPending} onClick={onClose}>
          取消
        </button>
      </div>
    </Modal>
  )
}

export function UsersPage() {
  const { params, page, change } = useListParams()
  const role: AccountRole = params.get('role') === 'TEACHER' ? 'TEACHER' : 'STUDENT'
  const q = params.get('q') ?? ''
  const active = ['true', 'false'].includes(params.get('active') ?? '') ? params.get('active')! : ''
  const client = useQueryClient()
  const [form, setForm] = useState<{ user: User | null } | null>(null)
  const [action, setAction] = useState<Action | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const list = useQuery({
    queryKey: ['users', role, q, active, page],
    queryFn: ({ signal }) => userApi.list(role, q, active, page, signal),
  })
  function saved(message: string) {
    setForm(null)
    setAction(null)
    setNotice(message)
    void client.invalidateQueries({ queryKey: ['users'] })
    void client.invalidateQueries({ queryKey: ['teacher-options'] })
    void client.invalidateQueries({ queryKey: ['dashboard'] })
  }
  return (
    <>
      <PageHeading
        title="账号管理"
        description="维护学生与教师资料、指导关系和账号状态。"
        action={
          <button
            className="button primary"
            onClick={() => {
              setNotice(null)
              setForm({ user: null })
            }}
          >
            <Plus size={17} aria-hidden="true" />
            新建{role === 'STUDENT' ? '学生' : '教师'}
          </button>
        }
      />
      {notice && (
        <div className="notice success" role="status">
          {notice}
        </div>
      )}
      <section className="panel list-panel">
        <nav className="section-tabs" aria-label="账号类型">
          <Link to="?role=STUDENT" aria-current={role === 'STUDENT' ? 'page' : undefined}>
            学生
          </Link>
          <Link to="?role=TEACHER" aria-current={role === 'TEACHER' ? 'page' : undefined}>
            教师
          </Link>
        </nav>
        <div className="toolbar">
          <SearchForm value={q} onSearch={(value) => change({ q: value, page: 1 })} />
          <label className="filter">
            账号状态
            <select
              value={active}
              onChange={(event) => change({ active: event.target.value, page: 1 })}
            >
              <option value="">全部状态</option>
              <option value="true">启用</option>
              <option value="false">停用</option>
            </select>
          </label>
        </div>
        {list.isPending && <Loading />}
        <ErrorNotice error={list.error} retry={() => void list.refetch()} />
        {list.data && (
          <>
            {!list.data.items.length ? (
              <Empty>
                {q || active ? '没有符合条件的账号。' : '还没有账号，可新建或批量导入。'}
              </Empty>
            ) : (
              <div className="table-scroll">
                <table>
                  <caption className="sr-only">
                    {role === 'STUDENT' ? '学生' : '教师'}账号列表
                  </caption>
                  <thead>
                    <tr>
                      <th>{role === 'STUDENT' ? '学号' : '工号'}</th>
                      <th>姓名</th>
                      {role === 'STUDENT' && (
                        <>
                          <th>专业</th>
                          <th>指导教师</th>
                        </>
                      )}
                      <th>状态</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.data.items.map((user) => (
                      <tr key={user.id}>
                        <td>{user.username}</td>
                        <td>{user.name}</td>
                        {role === 'STUDENT' && (
                          <>
                            <td>{user.major || '—'}</td>
                            <td>{user.teacherName || '未分配'}</td>
                          </>
                        )}
                        <td>
                          <span className={`status-badge ${user.active ? 'success' : 'disabled'}`}>
                            {user.active ? '启用' : '停用'}
                          </span>
                        </td>
                        <td>
                          <div className="row-actions">
                            <button
                              className="button plain"
                              aria-label={`编辑${user.name}`}
                              onClick={() => {
                                setNotice(null)
                                setForm({ user })
                              }}
                            >
                              编辑
                            </button>
                            <button
                              className="button plain"
                              aria-label={`${user.active ? '停用' : '启用'}${user.name}`}
                              onClick={() => {
                                setNotice(null)
                                setAction({ kind: 'active', user })
                              }}
                            >
                              {user.active ? '停用' : '启用'}
                            </button>
                            <button
                              className="button plain"
                              aria-label={`重置${user.name}的密码`}
                              onClick={() => {
                                setNotice(null)
                                setAction({ kind: 'reset', user })
                              }}
                            >
                              重置密码
                            </button>
                          </div>
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
      {form && (
        <UserForm user={form.user} role={role} onClose={() => setForm(null)} onSaved={saved} />
      )}
      {action && <AccountAction action={action} onClose={() => setAction(null)} onSaved={saved} />}
    </>
  )
}
