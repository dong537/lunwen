import { useQuery } from '@tanstack/react-query'
import { Card, Col, Row, Statistic, Typography } from 'antd'
import { blocksCachedData } from '../../shared/api/http'
import { ApiAlert, CenterSpin, PageHeader, useDocumentTitle } from '../../shared/ui'
import { teacherApi } from './api'

export function TeacherStatisticsPage() {
  useDocumentTitle('指导统计')
  const statistics = useQuery({
    queryKey: ['teacher-statistics'],
    queryFn: ({ signal }) => teacherApi.statistics(signal),
  })
  if (blocksCachedData(statistics.error)) {
    return <ApiAlert error={statistics.error} retry={() => void statistics.refetch()} />
  }
  const value = statistics.data
  return (
    <>
      <PageHeader title="指导统计" description="按当前指导名单和提交记录实时汇总。" />
      <ApiAlert error={statistics.error} retry={() => void statistics.refetch()} />
      {statistics.isPending && <CenterSpin />}
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
    </>
  )
}
