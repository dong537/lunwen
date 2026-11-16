package cn.luwneshacha.platform.submission;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRuleIssueRepository extends JpaRepository<SubmissionRuleIssue, Long> {
  List<SubmissionRuleIssue> findBySubmissionIdIn(Collection<Long> ids);
  void deleteBySubmissionId(long submissionId);
}
