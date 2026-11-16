package cn.luwneshacha.platform.submission;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
  Page<Submission> findByStudentIdOrderByCreatedAtDescIdDesc(long studentId, Pageable page);
  long countByStatus(SubmissionStatus status);
  @Query("select count(s) from Submission s where s.studentId in (select u.id from User u where u.collegeCode=:collegeCode)")
  long countByStudentCollege(@Param("collegeCode") String collegeCode);
  @Query(value = "select to_char(s.created_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD'), count(*) from submission s where s.created_at>=:since group by 1 order by 1", nativeQuery = true)
  List<Object[]> dailySubmissions(@Param("since") Instant since);
  @Query(value = "select to_char(s.created_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD'), count(*) from submission s join sys_user u on u.id=s.student_id where s.created_at>=:since and u.college_code=:collegeCode group by 1 order by 1", nativeQuery = true)
  List<Object[]> dailySubmissionsByCollege(@Param("since") Instant since, @Param("collegeCode") String collegeCode);
  @Query("select s.status, count(s) from Submission s where s.createdAt>=:since group by s.status")
  List<Object[]> recentStatusCounts(@Param("since") Instant since);
  @Query("select s.status, count(s) from Submission s, User u where s.studentId=u.id and u.collegeCode=:collegeCode and s.createdAt>=:since group by s.status")
  List<Object[]> recentStatusCountsByCollege(@Param("since") Instant since, @Param("collegeCode") String collegeCode);
  boolean existsByStorageDir(String directory);
  boolean existsByStudentId(Long studentId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.updatedAt=:now where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PENDING and s.attempts=0 and s.updatedAt>:now")
  int releaseUpload(@Param("id") long id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.FAILED, s.errorMessage='论文保存失败，请重新提交或联系管理员', s.finishedAt=:now, s.updatedAt=:now where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PENDING and s.attempts=0")
  int failUpload(@Param("id") long id, @Param("now") Instant now);

  @Query("select s.id, count(p.id) from Submission s, Submission p where s.id in :ids and p.studentId=s.studentId and (p.createdAt<s.createdAt or (p.createdAt=s.createdAt and p.id<=s.id)) group by s.id")
  List<Object[]> submissionNumbers(@Param("ids") List<Long> ids);

  @Query("select s.studentId, count(s.id) from Submission s where s.studentId in :ids and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS group by s.studentId")
  List<Object[]> successCounts(@Param("ids") List<Long> ids);

  @Query("select s from Submission s where s.studentId in :ids and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS and not exists (select newer.id from Submission newer where newer.studentId=s.studentId and newer.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS and (newer.createdAt>s.createdAt or (newer.createdAt=s.createdAt and newer.id>s.id)))")
  List<Submission> latestSuccesses(@Param("ids") List<Long> ids);

  @Query("select s from Submission s where s.attempts<3 and ((s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PENDING and s.updatedAt<=:now) or (s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.startedAt<:cutoff)) order by s.createdAt,s.id")
  List<Submission> candidates(@Param("now") Instant now, @Param("cutoff") Instant cutoff, Pageable limit);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING, s.attempts=s.attempts+1, s.reportAvailable=false, s.startedAt=:now, s.updatedAt=:now where s.id=:id and s.attempts=:attempt and s.attempts<3 and ((s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PENDING and s.updatedAt<=:now) or (s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.startedAt<:cutoff))")
  int claim(@Param("id") long id, @Param("attempt") int attempt, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS, s.problemCount=:count, s.reportAvailable=true, s.errorMessage=null, s.finishedAt=:now, s.updatedAt=:now where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.attempts=:attempt and s.startedAt>=:cutoff")
  int succeed(@Param("id") long id, @Param("attempt") int attempt, @Param("count") int count, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS, s.problemCount=:count, s.reportAvailable=true, s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.READY, s.errorMessage=null, s.finishedAt=:now, s.updatedAt=:now where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.attempts=:attempt and s.startedAt>=:cutoff")
  int succeedIndexed(@Param("id") long id, @Param("attempt") int attempt, @Param("count") int count, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

  @Query("select s from Submission s where s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS and s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.PENDING and (s.ruleIndexNextRetryAt is null or s.ruleIndexNextRetryAt<=:now) order by s.id")
  List<Submission> ruleIndexCandidates(@Param("now") Instant now, Pageable limit);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.ruleIndexAttempts=s.ruleIndexAttempts+1, s.ruleIndexNextRetryAt=:leaseUntil where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS and s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.PENDING and (s.ruleIndexNextRetryAt is null or s.ruleIndexNextRetryAt<=:now)")
  int claimRuleIndex(@Param("id") long id, @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.READY, s.ruleIndexNextRetryAt=null where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS and s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.PENDING and s.ruleIndexAttempts=:attempt")
  int completeRuleIndex(@Param("id") long id, @Param("attempt") int attempt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.ruleIndexStatus=:status, s.ruleIndexNextRetryAt=:next where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS and s.ruleIndexStatus=cn.luwneshacha.platform.submission.RuleIndexStatus.PENDING and s.ruleIndexAttempts=:attempt")
  int failRuleIndex(@Param("id") long id, @Param("attempt") int attempt, @Param("status") RuleIndexStatus status, @Param("next") Instant next);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=:status, s.problemCount=0, s.reportAvailable=false, s.byScope=null, s.errorMessage=coalesce(nullif(trim(:message), ''), nullif(trim(s.errorMessage), ''), '审核服务异常，请重新提交或联系管理员'), s.finishedAt=:finished, s.updatedAt=:next where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.attempts=:attempt and s.startedAt>=:cutoff")
  int fail(@Param("id") long id, @Param("attempt") int attempt, @Param("status") SubmissionStatus status, @Param("message") String message, @Param("finished") Instant finished, @Param("next") Instant next, @Param("cutoff") Instant cutoff);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.FAILED, s.problemCount=0, s.reportAvailable=false, s.byScope=null, s.errorMessage=coalesce(nullif(trim(s.errorMessage), ''), '审核服务异常，请重新提交或联系管理员'), s.finishedAt=:now, s.updatedAt=:now where s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.attempts>=3 and s.startedAt<:cutoff")
  int recoverExhausted(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

  List<Submission> findByIdGreaterThanOrderById(long id, Pageable page);
}
