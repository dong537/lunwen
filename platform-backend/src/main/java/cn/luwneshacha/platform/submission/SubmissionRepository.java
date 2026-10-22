package cn.luwneshacha.platform.submission;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
  Page<Submission> findByStudentIdOrderByCreatedAtDescIdDesc(long studentId, Pageable page);
  long countByStatus(SubmissionStatus status);
  boolean existsByStorageDir(String directory);

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
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING, s.attempts=s.attempts+1, s.reportAvailable=false, s.startedAt=:now, s.updatedAt=:now, s.errorMessage=null where s.id=:id and s.attempts=:attempt and s.attempts<3 and ((s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PENDING and s.updatedAt<=:now) or (s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.startedAt<:cutoff))")
  int claim(@Param("id") long id, @Param("attempt") int attempt, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.SUCCESS, s.problemCount=:count, s.reportAvailable=true, s.errorMessage=null, s.finishedAt=:now, s.updatedAt=:now where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.attempts=:attempt and s.startedAt>=:cutoff")
  int succeed(@Param("id") long id, @Param("attempt") int attempt, @Param("count") int count, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=:status, s.problemCount=0, s.reportAvailable=false, s.byScope=null, s.errorMessage=:message, s.finishedAt=:finished, s.updatedAt=:next where s.id=:id and s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.attempts=:attempt and s.startedAt>=:cutoff")
  int fail(@Param("id") long id, @Param("attempt") int attempt, @Param("status") SubmissionStatus status, @Param("message") String message, @Param("finished") Instant finished, @Param("next") Instant next, @Param("cutoff") Instant cutoff);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Submission s set s.status=cn.luwneshacha.platform.submission.SubmissionStatus.FAILED, s.problemCount=0, s.reportAvailable=false, s.byScope=null, s.errorMessage='审核服务异常，请重新提交或联系管理员', s.finishedAt=:now, s.updatedAt=:now where s.status=cn.luwneshacha.platform.submission.SubmissionStatus.PROCESSING and s.attempts>=3 and s.startedAt<:cutoff")
  int recoverExhausted(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

  List<Submission> findByIdGreaterThanOrderById(long id, Pageable page);
}
