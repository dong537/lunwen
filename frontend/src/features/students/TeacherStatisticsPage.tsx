import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  App as AntdApp,
  Alert,
  Button,
  Card,
  Col,
  InputNumber,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Typography,
} from 'antd'
import { DownloadOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'
import type { StatisticsRange, TeacherInsights } from '../../shared/api/contracts'
import { blocksCachedData } from '../../shared/api/http'
import {
  DailyTrend,
  DistributionBars,
  IssueHeatmap,
  RankedBars,
} from '../../shared/AnalyticsVisuals'
import { formatTime } from '../../shared/files'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { teacherApi } from './api'

type InsightStudent = TeacherInsights['students']['items'][number]

const reviewStatus = {
  PENDING: '等待审核',
  PROCESSING: '审核中',
  SUCCESS: '审核成功',
  FAILED: '审核失败',
}

const studentColumns: ColumnsType<InsightStudent> = [
  { title: '学号', dataIndex: 'username', key: 'username' },
  { title: '姓名', dataIndex: 'name', key: 'name' },
  {
    title: '年级',
    dataIndex: 'grade',
    key: 'grade',
    render: (value: number | null) => (value === null ? '未填写' : value + '年'),
  },
  {
    title: '专业',
    dataIndex: 'major',
    key: 'major',
    render: (value: string | null) => value || '—',
  },
  {
    title: '最近成功审核',
    dataIndex: 'lastSubmittedAt',
    key: 'lastSubmittedAt',
    render: (value: string | null) => formatTime(value),
  },
  {
    title: '最新问题数',
    dataIndex: 'latestProblemCount',
    key: 'latestProblemCount',
    align: 'right',
    render: (value: number | null) => (value === null ? '—' : value),
  },
  {
    title: '当前状态',
    dataIndex: 'latestReviewStatus',
    key: 'latestReviewStatus',
    render: (value: InsightStudent['latestReviewStatus']) =>
      value ? reviewStatus[value] : '未提交',
  },
  {
    title: '操作',
    key: 'action',
    render: (_: unknown, student: InsightStudent) => (
      <Link to={'/teacher/students/' + student.id}>查看</Link>
    ),
  },
]

export function TeacherStatisticsPage() {
  useDocumentTitle('指导统计')
  const { message } = AntdApp.useApp()
  const [gradeDraft, setGradeDraft] = useState<number | null>(null)
  const [grade, setGrade] = useState<number | 'UNSET' | null>(null)
  const [range, setRange] = useState<StatisticsRange>('30')
  const [page, setPage] = useState(1)
  const statistics = useQuery({
    queryKey: ['teacher-statistics', grade],
    queryFn: ({ signal }) => teacherApi.statistics(grade, signal),
  })
  const insights = useQuery({
    queryKey: ['teacher-insights', grade, range, page],
    queryFn: ({ signal }) => teacherApi.insights(grade, range, page, signal),
  })
  const exportFile = useMutation({
    mutationFn: () => teacherApi.exportStatistics(grade, range),
    onSuccess: () => void message.success('统计明细已开始下载。'),
  })
  if (blocksCachedData(statistics.error) || blocksCachedData(insights.error)) {
    const failed = statistics.error ? statistics : insights
    return <ApiAlert error={failed.error} retry={() => void failed.refetch()} />
  }
  const value = statistics.data
  const insight = insights.data
  const coverage = insight?.coverage
  return (
    <>
      <PageHeader
        title="指导统计"
        description="按本人指导名单查看真实提交和审核结果；问题数按每名学生最近一次成功审核统计。"
        extra={
          <Button
            icon={<DownloadOutlined />}
            loading={exportFile.isPending}
            onClick={() => exportFile.mutate()}
          >
            导出 Excel
          </Button>
        }
      />
      <Card style={{ marginBottom: 16 }}>
        <Space wrap size={12}>
          <InputNumber
            aria-label="按入学年级筛选统计"
            min={2000}
            max={2100}
            precision={0}
            value={gradeDraft}
            onChange={setGradeDraft}
            placeholder="全部年级"
            style={{ width: 140 }}
          />
          <Button
            onClick={() => {
              if (gradeDraft !== null && (gradeDraft < 2000 || gradeDraft > 2100)) {
                void message.error('请输入 2000—2100 年的入学年份。')
                return
              }
              setGrade(gradeDraft)
              setPage(1)
            }}
          >
            筛选年级
          </Button>
          <Button
            type={grade === 'UNSET' ? 'primary' : 'default'}
            onClick={() => {
              setGradeDraft(null)
              setGrade('UNSET')
              setPage(1)
            }}
          >
            未填写年级
          </Button>
          <Select<StatisticsRange>
            aria-label="统计时间范围"
            value={range}
            style={{ width: 150 }}
            onChange={(next) => {
              setRange(next)
              setPage(1)
            }}
            options={[
              { value: '30', label: '近 30 天' },
              { value: '90', label: '近 90 天' },
              { value: '365', label: '近一年' },
              { value: 'ALL', label: '全部时间' },
            ]}
          />
          <Typography.Text type="secondary">
            年级留空时包含尚未补录年级的学生；图表按所选时间范围统计。
          </Typography.Text>
        </Space>
      </Card>
      <ApiAlert error={statistics.error} retry={() => void statistics.refetch()} />
      <ApiAlert error={insights.error} retry={() => void insights.refetch()} />
      <ApiAlert error={exportFile.error} />
      {(statistics.isPending || insights.isPending) && <CenterSpin />}
      {value && (
        <>
          <Card title="学生名单" style={{ marginBottom: 16 }}>
            <Row gutter={[16, 16]}>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="指导学生" value={value.totalStudents} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="启用学生" value={value.activeStudents} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="已提交学生" value={value.submittedStudents} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="未提交学生" value={value.notSubmittedStudents} />
              </Col>
            </Row>
          </Card>
          <Card title="审核记录" style={{ marginBottom: 16 }}>
            <Row gutter={[16, 16]}>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="已有成功审核的学生" value={value.studentsWithSuccessfulReview} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="成功审核记录" value={value.successfulSubmissions} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="等待审核记录" value={value.pendingSubmissions} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="审核中记录" value={value.processingSubmissions} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic title="审核失败记录" value={value.failedSubmissions} />
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Statistic
                  title="各学生最近成功审核问题数合计"
                  value={value.latestSuccessfulProblemCount ?? '—'}
                />
              </Col>
            </Row>
            <Typography.Paragraph type="secondary" style={{ margin: '16px 0 0' }}>
              问题数只取每名学生最近一次成功审核；“—”表示尚无成功审核，不代表零问题。
            </Typography.Paragraph>
          </Card>
        </>
      )}
      {coverage && coverage.indexedReviews < coverage.eligibleReviews && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title={
            '规则明细已覆盖 ' +
            coverage.indexedReviews +
            ' / ' +
            coverage.eligibleReviews +
            ' 份最近成功审核'
          }
          description="历史审核的规则明细正在补录；规则榜单和热力图仅统计已补录部分，未补录不计为零问题。"
        />
      )}
      {insight && (
        <>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} lg={12}>
              <Card title="高频格式问题 TOP10" style={{ height: '100%' }}>
                <RankedBars
                  items={insight.topRules.map((rule) => ({
                    key: rule.ruleId,
                    label: rule.description,
                    value: rule.count,
                    affectedStudents: rule.affectedStudents,
                  }))}
                  valueTitle="问题数"
                  extraColumn={{
                    title: '触达人数',
                    render: (rule) => rule.affectedStudents + '人',
                  }}
                  emptyText="所选范围内没有已索引的规则问题"
                />
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card title="问题数分布" style={{ height: '100%' }}>
                <DistributionBars items={insight.problemDistribution} />
              </Card>
            </Col>
          </Row>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} lg={12}>
              <Card title="提交趋势" style={{ height: '100%' }}>
                <DailyTrend points={insight.dailySubmissions} label="指导学生每日论文提交次数" />
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card title="学生与规则问题热力图" style={{ height: '100%' }}>
                <IssueHeatmap rules={insight.heatmap.rules} students={insight.heatmap.students} />
              </Card>
            </Col>
          </Row>
          <Card title="学生明细">
            <Table<InsightStudent>
              size="small"
              rowKey="id"
              columns={studentColumns}
              dataSource={insight.students.items}
              scroll={{ x: 'max-content' }}
              pagination={{
                current: insight.students.page,
                pageSize: insight.students.size,
                total: insight.students.total,
                showTotal: (total) => '共 ' + total + ' 名学生',
                onChange: setPage,
              }}
              locale={{ emptyText: '所选范围内没有学生记录。' }}
            />
          </Card>
        </>
      )}
    </>
  )
}
