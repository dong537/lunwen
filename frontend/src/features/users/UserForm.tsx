import { useDeferredValue, useState } from 'react'
import { useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query'
import { App as AntdApp, Form, Input, InputNumber, Modal, Select } from 'antd'
import type { Role, User } from '../../shared/api/contracts'
import { ApiAlert } from '../../shared/ui'
import { useSession } from '../auth/session'
import { userApi } from './api'

export function UserForm({
  user,
  role,
  onClose,
  onSaved,
}: {
  user: User | null
  role: Role
  onClose: () => void
  onSaved: () => void
}) {
  const { message } = AntdApp.useApp()
  const [form] = Form.useForm()
  const { user: currentUser } = useSession()
  const collegeCode = Form.useWatch('collegeCode', form) as string | undefined
  const catalog = useQuery({
    queryKey: ['school-catalog'],
    queryFn: ({ signal }) => userApi.catalog(signal),
  })
  const majors = useQuery({
    queryKey: ['majors', collegeCode],
    queryFn: ({ signal }) => userApi.majors(collegeCode!, signal),
    enabled: role !== 'ADMIN' && !!collegeCode,
  })
  const [teacherSearch, setTeacherSearch] = useState('')
  const search = useDeferredValue(teacherSearch)
  const teachers = useInfiniteQuery({
    queryKey: ['teacher-options', collegeCode, search],
    initialPageParam: 1,
    queryFn: ({ signal, pageParam }) => userApi.teachers(collegeCode!, search, pageParam, signal),
    getNextPageParam: (lastPage) =>
      lastPage.page * lastPage.size < lastPage.total ? lastPage.page + 1 : undefined,
    enabled: role === 'STUDENT' && !!collegeCode,
  })
  const teacherOptions = (teachers.data?.pages.flatMap((page) => page.items) ?? []).map(
    (teacher) => ({
      value: teacher.id,
      label: teacher.name + ' · ' + teacher.username,
    }),
  )
  if (
    user?.teacherId &&
    user.collegeCode === collegeCode &&
    !teacherOptions.some((option) => option.value === user.teacherId)
  ) {
    teacherOptions.push({
      value: user.teacherId,
      label: (user.teacherName || '原指导教师') + '（当前所选）',
    })
  }
  const save = useMutation({
    mutationFn: (values: {
      username: string
      name: string
      collegeCode: string
      major?: string
      grade?: number
      teacherId?: string
    }) => {
      const input = {
        name: values.name.trim(),
        collegeCode: values.collegeCode,
        ...(role !== 'ADMIN' ? { major: values.major?.trim() || null } : {}),
        ...(role === 'STUDENT'
          ? { grade: values.grade ?? null, teacherId: values.teacherId || null }
          : {}),
      }
      return user
        ? userApi.update(user.id, input)
        : userApi.create({ ...input, username: values.username.trim(), role })
    },
    onSuccess: (saved) => {
      void message.success(
        user
          ? `已更新${saved.name}的账号信息。`
          : `已创建${saved.name}的账号，初始密码为 123456，${saved.mustChangePassword ? '首次登录需重新设置密码。' : '可直接登录。'}`,
      )
      onSaved()
    },
  })
  return (
    <Modal
      title={
        (user ? '编辑' : '新建') +
        (role === 'STUDENT' ? '学生' : role === 'TEACHER' ? '教师' : '学院管理员') +
        '账号'
      }
      open
      onCancel={onClose}
      onOk={() => form.submit()}
      okButtonProps={{ disabled: catalog.isPending || !!catalog.error }}
      confirmLoading={save.isPending}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        preserve={false}
        initialValues={{
          username: user?.username ?? '',
          name: user?.name ?? '',
          collegeCode: user?.collegeCode ?? currentUser?.collegeCode ?? undefined,
          major: user?.major ?? '',
          grade: user?.grade ?? undefined,
          teacherId: user?.teacherId ?? undefined,
        }}
        onFinish={(values) => save.mutate(values)}
      >
        <Form.Item label="学校">
          <Select
            disabled
            value={catalog.data?.schoolName ?? '山东石油化工学院'}
            options={[
              {
                value: catalog.data?.schoolName ?? '山东石油化工学院',
                label: catalog.data?.schoolName ?? '山东石油化工学院',
              },
            ]}
          />
        </Form.Item>
        <Form.Item
          label="学院"
          name="collegeCode"
          rules={[{ required: true, message: '请选择学院' }]}
        >
          <Select
            placeholder={catalog.isPending ? '正在加载学院' : '请选择学院'}
            loading={catalog.isPending}
            disabled={!!currentUser?.collegeCode}
            options={(catalog.data?.colleges ?? [])
              .filter(
                (college) => !currentUser?.collegeCode || college.code === currentUser.collegeCode,
              )
              .map((college) => ({ value: college.code, label: college.name }))}
            onChange={() => {
              form.setFieldsValue({ major: undefined, teacherId: undefined })
              setTeacherSearch('')
            }}
          />
        </Form.Item>
        <Form.Item
          label={role === 'STUDENT' ? '学号' : '工号'}
          name="username"
          rules={[
            { required: true, message: '请填写账号' },
            { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '账号需为 3–32 位字母、数字或下划线' },
          ]}
        >
          <Input
            disabled={!!user}
            maxLength={32}
            autoComplete="off"
            placeholder={user ? undefined : '初始密码为 123456'}
          />
        </Form.Item>
        <Form.Item label="姓名" name="name" rules={[{ required: true, message: '请填写姓名' }]}>
          <Input maxLength={64} />
        </Form.Item>
        {role !== 'ADMIN' && (
          <>
            <Form.Item label="专业（选填）" name="major">
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                placeholder={collegeCode ? '选择本学院专业' : '请先选择学院'}
                disabled={!collegeCode}
                loading={majors.isPending && !!collegeCode}
                options={(majors.data ?? [])
                  .filter(
                    (major) =>
                      major.active ||
                      (major.name === user?.major && user.collegeCode === collegeCode),
                  )
                  .map((major) => ({
                    value: major.name,
                    label: major.active ? major.name : major.name + '（已停用）',
                  }))}
              />
            </Form.Item>
            {role === 'STUDENT' && (
              <Form.Item
                label="入学年级"
                name="grade"
                rules={[{ required: !user || user.grade !== null, message: '请填写四位入学年份' }]}
              >
                <InputNumber min={2000} max={2100} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            )}
            {role === 'STUDENT' && (
              <Form.Item label="指导教师（选填）" name="teacherId">
                <Select
                  allowClear
                  showSearch
                  filterOption={false}
                  placeholder="输入工号或姓名搜索"
                  disabled={!collegeCode}
                  onSearch={setTeacherSearch}
                  loading={teachers.isPending || teachers.isFetchingNextPage}
                  options={teacherOptions}
                  onPopupScroll={(event) => {
                    const element = event.currentTarget
                    if (
                      element.scrollTop + element.clientHeight >= element.scrollHeight - 24 &&
                      teachers.hasNextPage &&
                      !teachers.isFetchingNextPage
                    ) {
                      void teachers.fetchNextPage()
                    }
                  }}
                />
              </Form.Item>
            )}
          </>
        )}
        <ApiAlert error={catalog.error} retry={() => void catalog.refetch()} />
        <ApiAlert error={majors.error} retry={() => void majors.refetch()} />
        <ApiAlert error={teachers.error} retry={() => void teachers.refetch()} />
        <ApiAlert error={save.error} />
      </Form>
    </Modal>
  )
}
