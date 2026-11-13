package cn.luwneshacha.platform.teacher;

import cn.luwneshacha.platform.account.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Aggregates only students currently assigned to the teacher, without loading their papers. */
public interface TeacherStatisticsRepository extends Repository<User, Long> {
  @Query("""
      select count(u.id) as totalStudents,
          coalesce(sum(case when u.active=true then 1 else 0 end), 0) as activeStudents
      from User u
      where u.teacherId=:teacherId and u.role=cn.luwneshacha.platform.account.Role.STUDENT
      """)
  StudentTotals studentTotals(@Param("teacherId") long teacherId);

  @Query("""
      select count(distinct s.studentId) as submittedStudents,
          coalesce(sum(case when s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS then 1 else 0 end), 0) as successfulSubmissions,
          coalesce(sum(case when s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PENDING then 1 else 0 end), 0) as pendingSubmissions,
          coalesce(sum(case when s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING then 1 else 0 end), 0) as processingSubmissions,
          coalesce(sum(case when s.status=cn.luwneshacha.platform.submission.SubmissionStatus.FAILED then 1 else 0 end), 0) as failedSubmissions
      from Submission s, User u
      where s.studentId=u.id and u.teacherId=:teacherId and u.role=cn.luwneshacha.platform.account.Role.STUDENT
      """)
  SubmissionTotals submissionTotals(@Param("teacherId") long teacherId);

  @Query("""
      select count(s.id) as studentsWithSuccessfulReview,
          sum(s.problemCount) as latestSuccessfulProblemCount
      from Submission s, User u
      where s.studentId=u.id and u.teacherId=:teacherId and u.role=cn.luwneshacha.platform.account.Role.STUDENT
          and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS
          and not exists (
            select newer.id from Submission newer
            where newer.studentId=s.studentId and newer.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS
                and (newer.createdAt>s.createdAt or (newer.createdAt=s.createdAt and newer.id>s.id)))
      """)
  LatestSuccessfulTotals latestSuccessfulTotals(@Param("teacherId") long teacherId);

  interface StudentTotals {
    long getTotalStudents();
    long getActiveStudents();
  }
  interface SubmissionTotals {
    long getSubmittedStudents();
    long getSuccessfulSubmissions();
    long getPendingSubmissions();
    long getProcessingSubmissions();
    long getFailedSubmissions();
  }
  interface LatestSuccessfulTotals {
    long getStudentsWithSuccessfulReview();
    Long getLatestSuccessfulProblemCount();
  }
}
