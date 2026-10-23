import { useDeferredValue, useState, type FormEvent } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import type { AccountRole, User } from '../../shared/api/contracts'
import { ErrorNotice, Loading, Modal } from '../../shared/ui'
import { userApi } from './api'

export function UserForm({
  user,
  role,
  onClose,
  onSaved,
}: {
  user: User | null
  role: AccountRole
  onClose: () => void
  onSaved: (message: string) => void
}) {
  const [username, setUsername] = useState(user?.username ?? '')
  const [name, setName] = useState(user?.name ?? '')
  const [major, setMajor] = useState(user?.major ?? '')
  const [teacherSelection, setTeacherSelection] = useState({
    id: user?.teacherId ?? '',
    label: user?.teacherName ?? '',
  })
  const teacherId = teacherSelection.id
  const [teacherSearch, setTeacherSearch] = useState('')
  const [validation, setValidation] = useState<string | null>(null)
  const search = useDeferredValue(teacherSearch)
  const teachers = useQuery({
    queryKey: ['teacher-options', search],
    queryFn: ({ signal }) => userApi.teachers(search, signal),
    enabled: role === 'STUDENT',
  })
  const save = useMutation({
    mutationFn: () => {
      const input = {
        name: name.trim(),
        ...(role === 'STUDENT'
          ? { major: major.trim() || null, teacherId: teacherId || null }
          : {}),
      }
      return user
        ? userApi.update(user.id, input)
        : userApi.create({ ...input, username: username.trim(), role })
    },
    onSuccess: () =>
      onSaved(
        user
          ? `已更新${name.trim()}的账号信息。`
          : `已创建${name.trim()}的账号，初始密码为 123456，首次登录需重新设置密码。`,
      ),
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    const error =
      !user && !/^[A-Za-z0-9_]{3,32}$/.test(username.trim())
        ? '账号需为 3–32 位字母、数字或下划线。'
        : !name.trim()
          ? '请填写姓名。'
          : null
    setValidation(error)
    if (!error) save.mutate()
  }
  return (
    <Modal
      title={`${user ? '编辑' : '新建'}${role === 'STUDENT' ? '学生' : '教师'}账号`}
      onClose={onClose}
      busy={save.isPending}
    >
      <form className="form-stack" onSubmit={submit}>
        <label>
          {role === 'STUDENT' ? '学号' : '工号'}
          <input
            value={username}
            disabled={!!user || save.isPending}
            onChange={(event) => setUsername(event.target.value)}
            required
            maxLength={32}
            autoComplete="off"
            aria-describedby={!user ? 'username-help' : undefined}
          />
        </label>
        {!user && (
          <p className="help" id="username-help">
            3–32 位字母、数字或下划线。初始密码为 123456。
          </p>
        )}
        <label>
          姓名
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            maxLength={64}
            disabled={save.isPending}
          />
        </label>
        {role === 'STUDENT' && (
          <>
            <label>
              专业（选填）
              <input
                value={major}
                onChange={(event) => setMajor(event.target.value)}
                maxLength={64}
                disabled={save.isPending}
              />
            </label>
            <fieldset>
              <legend>指导教师（选填）</legend>
              <label>
                搜索指导教师
                <input
                  type="search"
                  value={teacherSearch}
                  maxLength={100}
                  onChange={(event) => setTeacherSearch(event.target.value)}
                  placeholder="输入工号或姓名"
                  disabled={save.isPending}
                />
              </label>
              {teachers.isPending && <Loading>正在加载教师…</Loading>}
              <ErrorNotice error={teachers.error} retry={() => void teachers.refetch()} />
              <label>
                指导教师
                <select
                  value={teacherId}
                  onChange={(event) => {
                    const selected = teachers.data?.find((item) => item.id === event.target.value)
                    setTeacherSelection({ id: event.target.value, label: selected?.name ?? '' })
                  }}
                  disabled={save.isPending}
                >
                  <option value="">暂不分配</option>
                  {teacherId && !teachers.data?.some((item) => item.id === teacherId) && (
                    <option value={teacherId}>{teacherSelection.label}（当前选择）</option>
                  )}
                  {teachers.data?.map((teacher) => (
                    <option key={teacher.id} value={teacher.id}>
                      {teacher.name} · {teacher.username}
                    </option>
                  ))}
                </select>
              </label>
              <p className="help">可搜索并选择启用中的教师。</p>
            </fieldset>
          </>
        )}
        <ErrorNotice error={validation ?? save.error} />
        <div className="actions">
          <button className="button primary" disabled={save.isPending}>
            {save.isPending ? '正在保存…' : '保存'}
          </button>
          <button
            type="button"
            className="button secondary"
            onClick={onClose}
            disabled={save.isPending}
          >
            取消
          </button>
        </div>
      </form>
    </Modal>
  )
}
