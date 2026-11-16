package cn.luwneshacha.platform.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "submission_rule_issue", uniqueConstraints = @UniqueConstraint(columnNames = {"submission_id", "rule_id"}))
public class SubmissionRuleIssue {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "submission_id", nullable = false) private Long submissionId;
  @Column(name = "rule_id", nullable = false, length = 128) private String ruleId;
  @Column(nullable = false, length = 500) private String description;
  @Column(name = "issue_count", nullable = false) private int issueCount;

  protected SubmissionRuleIssue() {}
  public SubmissionRuleIssue(long submissionId, RuleIssueSummary.Issue issue) {
    this.submissionId = submissionId;
    ruleId = issue.ruleId();
    description = issue.description();
    issueCount = issue.count();
  }
  public Long getId() { return id; }
  public Long getSubmissionId() { return submissionId; }
  public String getRuleId() { return ruleId; }
  public String getDescription() { return description; }
  public int getIssueCount() { return issueCount; }
}
