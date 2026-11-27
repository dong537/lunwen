import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  App as AntdApp,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { Major } from '../../shared/api/contracts'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { useSession } from '../auth/session'
import { userApi } from './api'

export function MajorsPage() {
  useDocumentTitle('专业目录')
  const { message, modal } = AntdApp.useApp()
  const { user } = useSession()
  const client = useQueryClient()
  const [collegeCode, setCollegeCode] = useState(user?.collegeCode ?? '')
  const [name, setName] = useState('')
  const [editing, setEditing] = useState<Major | null>(null)
  const catalog = useQuery({
    queryKey: ['school-catalog'],
    queryFn: ({ signal }) => userApi.catalog(signal),
  })
  const majors = useQuery({
    queryKey: ['majors', collegeCode],
    queryFn: ({ signal }) => userApi.majors(collegeCode, signal),
    enabled: !!collegeCode,
  })
  const create = useMutation({
    mutationFn: () => userApi.createMajor(collegeCode, name.trim()),
    onSuccess: () => {
      setName('')
      void client.invalidateQueries({ queryKey: ['majors', collegeCode] })
      void message.success('专业已添加。')
    },
  })
  const setActive = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      userApi.setMajorActive(id, active),
    onSuccess: (updated) => {
      void client.invalidateQueries({ queryKey: ['majors', collegeCode] })
      void message.success(updated.active ? '专业已启用。' : '专业已停用，已有学生资料不会删除。')
    },
    onError: (error) => {
      void message.error(error instanceof Error ? error.message : '专业状态修改失败，请稍后重试。')
    },
  })
  function toggle(major: Major) {
    modal.confirm({
      title: major.active ? '停用专业' : '启用专业',
      content: major.active
        ? '停用后无法再将新学生分配到“' + major.name + '”；已有学生的专业信息保留。'
        : '启用后可再次选用“' + major.name + '”。',
      okText: major.active ? '确认停用' : '确认启用',
      okType: major.active ? 'danger' : 'primary',
      cancelText: '取消',
      onOk: () =>
        setActive.mutateAsync({ id: major.id, active: !major.active }).then(() => undefined),
    })
  }
  const columns: ColumnsType<Major> = [
    { title: '专业名称', dataIndex: 'name', key: 'name' },
    {
      title: '状态',
      dataIndex: 'active',
      key: 'active',
      width: 110,
      render: (active: boolean) => (active ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>),
    },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_: unknown, major: Major) => (
        <Space size={0}>
          <Button type="link" aria-label={'修改' + major.name} onClick={() => setEditing(major)}>
            修改
          </Button>
          <Button type="link" danger={major.active} onClick={() => toggle(major)}>
            {major.active ? '停用' : '启用'}
          </Button>
        </Space>
      ),
    },
  ]
  return (
    <>
      <PageHeader title="专业目录" description="按学院维护可用于新建学生账号的专业选项。" />
      <Card>
        <Space orientation="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <Typography.Text>学校</Typography.Text>
            <Select
              disabled
              value={catalog.data?.schoolName ?? '山东石油化工学院'}
              options={[
                {
                  value: catalog.data?.schoolName ?? '山东石油化工学院',
                  label: catalog.data?.schoolName ?? '山东石油化工学院',
                },
              ]}
              style={{ display: 'block', maxWidth: 360, marginTop: 6 }}
            />
          </div>
          <div>
            <Typography.Text>学院</Typography.Text>
            <Select
              aria-label="专业所属学院"
              value={collegeCode || undefined}
              placeholder="请选择学院"
              loading={catalog.isPending}
              disabled={!!user?.collegeCode || create.isPending || setActive.isPending}
              style={{ display: 'block', maxWidth: 360, marginTop: 6 }}
              options={(catalog.data?.colleges ?? [])
                .filter((college) => !user?.collegeCode || college.code === user.collegeCode)
                .map((college) => ({ value: college.code, label: college.name }))}
              onChange={setCollegeCode}
            />
          </div>
          <Space.Compact style={{ width: '100%', maxWidth: 480 }}>
            <Input
              aria-label="新增专业名称"
              placeholder="输入专业全称"
              value={name}
              maxLength={64}
              disabled={!collegeCode}
              onChange={(event) => setName(event.target.value)}
              onPressEnter={() => {
                if (collegeCode && name.trim()) create.mutate()
              }}
            />
            <Button
              type="primary"
              disabled={!collegeCode || !name.trim()}
              loading={create.isPending}
              onClick={() => create.mutate()}
            >
              添加专业
            </Button>
          </Space.Compact>
          <ApiAlert error={catalog.error} retry={() => void catalog.refetch()} />
          <ApiAlert error={majors.error} retry={() => void majors.refetch()} />
          <ApiAlert error={create.error ?? setActive.error} />
          {majors.isPending && collegeCode && <CenterSpin />}
          {collegeCode && majors.data && (
            <Table<Major>
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={majors.data}
              pagination={false}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: <Empty description="该学院尚未录入专业。" /> }}
            />
          )}
          {!collegeCode && <Empty description="请选择学院后维护专业。" />}
        </Space>
      </Card>
      {editing && (
        <MajorEditDialog
          major={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            for (const key of [
              'majors',
              'teacher-majors',
              'users',
              'teacher-options',
              'students',
              'student',
              'teacher-insights',
            ]) {
              void client.invalidateQueries({ queryKey: [key] })
            }
          }}
        />
      )}
    </>
  )
}

function MajorEditDialog({
  major,
  onClose,
  onSaved,
}: {
  major: Major
  onClose: () => void
  onSaved: () => void
}) {
  const { message } = AntdApp.useApp()
  const [form] = Form.useForm<{ name: string }>()
  const save = useMutation({
    mutationFn: (values: { name: string }) => userApi.renameMajor(major.id, values.name.trim()),
    onSuccess: () => {
      void message.success('专业名称已修改。')
      onSaved()
    },
  })
  return (
    <Modal
      title="修改专业名称"
      open
      onCancel={() => {
        if (!save.isPending) onClose()
      }}
      onOk={() => form.submit()}
      confirmLoading={save.isPending}
      cancelButtonProps={{ disabled: save.isPending }}
      closable={!save.isPending}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">
        修改后，同学院中使用原专业名称的学生和教师资料会同步更新。
      </Typography.Paragraph>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ name: major.name }}
        onFinish={(values) => save.mutate(values)}
      >
        <Form.Item
          label="专业名称"
          name="name"
          htmlFor="major-edit-name"
          rules={[
            { required: true, whitespace: true, message: '请填写专业名称' },
            { max: 64, message: '专业名称不能超过64个字符' },
          ]}
        >
          <Input id="major-edit-name" autoFocus disabled={save.isPending} />
        </Form.Item>
        <ApiAlert error={save.error} />
      </Form>
    </Modal>
  )
}
