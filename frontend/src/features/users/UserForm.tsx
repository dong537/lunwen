import { useDeferredValue, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Form, Input, message, Modal, Select } from 'antd'
import type { AccountRole, User } from '../../shared/api/contracts'
import { ApiAlert } from '../../shared/ui'
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
  onSaved: () => void
}) {
  const [form] = Form.useForm()
  const [teacherSearch, setTeacherSearch] = useState('')
  const search = useDeferredValue(teacherSearch)
  const teachers = useQuery({
    queryKey: ['teacher-options', search],
    queryFn: ({ signal }) => userApi.teachers(search, signal),
    enabled: role === 'STUDENT',
  })
  const save = useMutation({
    mutationFn: (values: {
      username: string
      name: string
      major?: string
      teacherId?: string
    }) => {
      const input = {
        name: values.name.trim(),
        ...(role === 'STUDENT'
          ? { major: values.major?.trim() || null, teacherId: values.teacherId || null }
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
          : `已创建${saved.name}的账号，初始密码为 123456，首次登录需重新设置密码。`,
      )
      onSaved()
    },
  })
  return (
    <Modal
      title={`${user ? '编辑' : '新建'}${role === 'STUDENT' ? '学生' : '教师'}账号`}
      open
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={save.isPending}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <Form
        form={form}
        layout="vertical"
        preserve={false}
        initialValues={{
          username: user?.username ?? '',
          name: user?.name ?? '',
          major: user?.major ?? '',
          teacherId: user?.teacherId ?? undefined,
        }}
        onFinish={(values) => save.mutate(values)}
      >
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
        {role === 'STUDENT' && (
          <>
            <Form.Item label="专业（选填）" name="major">
              <Input maxLength={64} />
            </Form.Item>
            <Form.Item label="指导教师（选填）" name="teacherId">
              <Select
                allowClear
                showSearch
                filterOption={false}
                placeholder="输入工号或姓名搜索"
                onSearch={setTeacherSearch}
                loading={teachers.isPending}
                options={(teachers.data ?? []).map((teacher) => ({
                  value: teacher.id,
                  label: `${teacher.name} · ${teacher.username}`,
                }))}
              />
            </Form.Item>
          </>
        )}
        <ApiAlert error={save.error} />
      </Form>
    </Modal>
  )
}
