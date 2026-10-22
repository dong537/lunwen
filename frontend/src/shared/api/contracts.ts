export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN'
export type AccountRole = Exclude<Role, 'ADMIN'>
export interface User {
  id: string
  username: string
  name: string
  role: Role
  major: string | null
  teacherId: string | null
  teacherName: string | null
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
  submissionCount: number
  lastSubmittedAt: string | null
  latestProblemCount: number | null
}
export interface Dashboard {
  activeStudents: number
  activeTeachers: number
  totalSubmissions: number
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
  role: AccountRole
  major?: string | null
  teacherId?: string | null
}
