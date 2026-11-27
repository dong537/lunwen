export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN'
export type AccountRole = Exclude<Role, 'ADMIN'>
export interface User {
  id: string
  username: string
  name: string
  role: Role
  major: string | null
  grade: number | null
  teacherId: string | null
  teacherName: string | null
  collegeCode: string | null
  collegeName: string | null
  active: boolean
  mustChangePassword: boolean
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
export type SubmissionStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'
export type TransferMode = 'proxy' | 'direct'
export interface UploadTicket {
  submissionId: string
  url: string
  method: 'PUT'
  headers: Record<string, string>
  expiresAt: string
}
export interface DownloadTicket {
  url: string
  filename: string
  expiresAt: string
}
export interface Submission {
  id: string
  studentId: string
  submissionNumber: number
  originalFilename: string
  fileSize: number
  status: SubmissionStatus
  problemCount: number | null
  byScope: Record<string, number> | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  canDownloadOriginal: boolean
  canDownloadMarked: boolean
  canDownloadReport: boolean
  uploadReserved?: boolean
}
export interface StudentSummary {
  id: string
  username: string
  name: string
  major: string | null
  grade: number | null
  submissionCount: number
  lastSubmittedAt: string | null
  latestProblemCount: number | null
}
export interface Dashboard {
  activeStudents: number
  activeTeachers: number
  totalSubmissions: number
  dailySubmissions: { date: string; count: number }[]
  statusDistribution: { status: SubmissionStatus; count: number }[]
  studentsByCollege: { collegeCode: string | null; collegeName: string; count: number }[]
}
export interface College {
  code: string
  name: string
}
export interface SchoolCatalog {
  schoolName: string
  colleges: College[]
}
export interface Major {
  id: string
  collegeCode: string
  name: string
  active: boolean
}
export interface TeacherStatistics {
  totalStudents: number
  activeStudents: number
  submittedStudents: number
  notSubmittedStudents: number
  studentsWithSuccessfulReview: number
  successfulSubmissions: number
  pendingSubmissions: number
  processingSubmissions: number
  failedSubmissions: number
  latestSuccessfulProblemCount: number | null
}
export type StatisticsRange = '30' | '90' | '365' | 'ALL'
export interface TeacherInsights {
  coverage: { eligibleReviews: number; indexedReviews: number }
  topRules: { ruleId: string; description: string; count: number; affectedStudents: number }[]
  problemDistribution: { label: string; students: number }[]
  dailySubmissions: { date: string; count: number }[]
  heatmap: {
    rules: { ruleId: string; description: string }[]
    students: {
      studentId: string
      name: string
      counts: Record<string, number>
    }[]
  }
  students: Page<{
    id: string
    username: string
    name: string
    grade: number | null
    major: string | null
    lastSubmittedAt: string | null
    latestProblemCount: number | null
    latestReviewStatus: SubmissionStatus | null
  }>
}
export interface ImportError {
  row: number
  username: string
  message: string
}
export interface ImportResult {
  totalRows: number
  validRows: number
  successCount: number
  skippedCount: number
  failedCount: number
  errors: ImportError[]
}
export interface UserInput {
  username: string
  name: string
  role: Role
  collegeCode: string
  major?: string | null
  grade?: number | null
  teacherId?: string | null
}
