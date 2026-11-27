package cn.luwneshacha.platform.teacher;

import cn.luwneshacha.platform.submission.Submission;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read models for the teacher's current roster; no client-supplied teacher ID is accepted. */
public interface TeacherInsightsRepository extends Repository<Submission, Long> {
  String LATEST_SUCCESS = """
      s.studentId=u.id and u.teacherId=:teacherId
          and u.role=cn.luwneshacha.platform.account.Role.STUDENT
          and (:year is null or u.grade=:year) and (:unset=false or u.grade is null)
          and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS
          and s.createdAt>=:since
          and not exists (select newer.id from Submission newer
              where newer.studentId=s.studentId
                  and newer.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS
                  and (newer.createdAt>s.createdAt or (newer.createdAt=s.createdAt and newer.id>s.id)))
      """;

  @Query("""
      select count(s.id),
          coalesce(sum(case when s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.READY then 1 else 0 end),0),
          coalesce(sum(case when s.problemCount=0 then 1 else 0 end),0),
          coalesce(sum(case when s.problemCount between 1 and 5 then 1 else 0 end),0),
          coalesce(sum(case when s.problemCount between 6 and 10 then 1 else 0 end),0),
          coalesce(sum(case when s.problemCount>10 then 1 else 0 end),0)
      from Submission s, User u where """ + " " + LATEST_SUCCESS)
  List<Object[]> latestReviewSummary(@Param("teacherId") long teacherId,
      @Param("year") Integer year, @Param("unset") boolean unset, @Param("since") Instant since);

  @Query("""
      select issue.ruleId, min(issue.description), sum(issue.issueCount),
          count(distinct s.studentId)
      from SubmissionRuleIssue issue, Submission s, User u
      where issue.submissionId=s.id
          and s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.READY
          and """ + " " + LATEST_SUCCESS + " group by issue.ruleId order by sum(issue.issueCount) desc, issue.ruleId")
  List<Object[]> topRuleRows(@Param("teacherId") long teacherId,
      @Param("year") Integer year, @Param("unset") boolean unset, @Param("since") Instant since,
      org.springframework.data.domain.Pageable limit);

  @Query("""
      select s.studentId, sum(issue.issueCount)
      from SubmissionRuleIssue issue, Submission s, User u
      where issue.submissionId=s.id
          and s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.READY
          and """ + " " + LATEST_SUCCESS + " group by s.studentId order by sum(issue.issueCount) desc, s.studentId")
  List<Object[]> topStudentRows(@Param("teacherId") long teacherId,
      @Param("year") Integer year, @Param("unset") boolean unset, @Param("since") Instant since,
      org.springframework.data.domain.Pageable limit);

  @Query("""
      select s.studentId, issue.ruleId, sum(issue.issueCount)
      from SubmissionRuleIssue issue, Submission s, User u
      where issue.submissionId=s.id
          and s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.READY
          and s.studentId in :studentIds and issue.ruleId in :ruleIds
          and """ + " " + LATEST_SUCCESS + " group by s.studentId, issue.ruleId")
  List<Object[]> heatmapCounts(@Param("teacherId") long teacherId,
      @Param("year") Integer year, @Param("unset") boolean unset, @Param("since") Instant since,
      @Param("studentIds") Collection<Long> studentIds, @Param("ruleIds") Collection<String> ruleIds);

  @Query("""
      select s from Submission s where s.studentId in :studentIds and s.createdAt>=:since
          and not exists (select newer.id from Submission newer where newer.studentId=s.studentId
              and (newer.createdAt>s.createdAt or (newer.createdAt=s.createdAt and newer.id>s.id)))
      """)
  List<Submission> latestSubmissionsForStudents(@Param("studentIds") Collection<Long> studentIds,
      @Param("since") Instant since);

  @Query("""
      select s from Submission s where s.studentId in :studentIds and s.createdAt>=:since
          and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS
          and not exists (select newer.id from Submission newer where newer.studentId=s.studentId
              and newer.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS
              and (newer.createdAt>s.createdAt or (newer.createdAt=s.createdAt and newer.id>s.id)))
      """)
  List<Submission> latestSuccessfulForStudents(@Param("studentIds") Collection<Long> studentIds,
      @Param("since") Instant since);

  @Query(value = """
      select to_char(s.created_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD'), count(*)
      from submission s join sys_user u on u.id=s.student_id
      where u.teacher_id=:teacherId and u.role='STUDENT'
          and (cast(:year as integer) is null or u.grade=:year)
          and (:unset=false or u.grade is null) and s.created_at>=:since
      group by 1 order by 1
      """, nativeQuery = true)
  List<Object[]> dailySubmissions(@Param("teacherId") long teacherId,
      @Param("year") Integer year, @Param("unset") boolean unset, @Param("since") Instant since);
}
