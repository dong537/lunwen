package cn.luwneshacha.platform.submission;

import cn.luwneshacha.platform.engine.HttpAuditEngine;
import cn.luwneshacha.platform.storage.ObjectStorage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Gradually indexes successful reviews produced before rule issue persistence was added. */
@Component
@ConditionalOnProperty(name = "platform.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RuleIssueBackfill {
  private static final Logger log = LoggerFactory.getLogger(RuleIssueBackfill.class);
  private static final int BATCH_SIZE = 10;
  private static final Duration LEASE = Duration.ofMinutes(10);
  private static final Duration MAX_RETRY_DELAY = Duration.ofHours(6);
  private static final long MAX_REPORT_BYTES = 16L * 1024 * 1024;

  private final SubmissionRepository submissions;
  private final SubmissionRuleIssueRepository ruleIssues;
  private final ObjectStorage storage;
  private final TransactionTemplate transactions;

  public RuleIssueBackfill(SubmissionRepository submissions, SubmissionRuleIssueRepository ruleIssues,
      ObjectStorage storage, PlatformTransactionManager manager) {
    this.submissions = submissions; this.ruleIssues = ruleIssues; this.storage = storage;
    transactions = new TransactionTemplate(manager);
  }

  @Scheduled(fixedDelayString = "${platform.worker.rule-index-delay-ms:60000}")
  public void poll() { processBatch(Instant.now()); }

  /** Public for focused tests; transient storage failures remain eligible for scheduled retry. */
  public void processBatch(Instant now) {
    try {
      for (Submission item : submissions.ruleIndexCandidates(now, PageRequest.of(0, BATCH_SIZE))) {
        int attempt = item.getRuleIndexAttempts() + 1;
        boolean claimed = Boolean.TRUE.equals(transactions.execute(status ->
            submissions.claimRuleIndex(item.getId(), now, now.plus(LEASE)) == 1));
        if (!claimed) continue;
        try {
          var summary = read(item);
          transactions.executeWithoutResult(status -> {
            if (submissions.completeRuleIndex(item.getId(), attempt) != 1) return;
            ruleIssues.deleteBySubmissionId(item.getId());
            ruleIssues.saveAll(summary.issues().stream()
                .map(issue -> new SubmissionRuleIssue(item.getId(), issue)).toList());
          });
        } catch (IOException | RuntimeException error) {
          boolean permanent = error instanceof InvalidReportException || reportMissing(item);
          RuleIndexStatus nextStatus = permanent ? RuleIndexStatus.UNAVAILABLE : RuleIndexStatus.PENDING;
          Instant retryAt = permanent ? null : now.plus(retryDelay(attempt));
          log.warn("rule_issue_backfill_failed submission={} attempt={} type={} next={}",
              item.getId(), attempt, error.getClass().getSimpleName(), nextStatus);
          try { transactions.executeWithoutResult(status ->
              submissions.failRuleIndex(item.getId(), attempt, nextStatus, retryAt)); }
          catch (RuntimeException stateError) {
            log.error("rule_issue_backfill_state_pending submission={} type={}",
                item.getId(), stateError.getClass().getSimpleName());
          }
        }
      }
    } catch (RuntimeException error) {
      log.error("rule_issue_backfill_scan_failed type={}", error.getClass().getSimpleName());
    }
  }

  private RuleIssueSummary read(Submission item) throws IOException {
    try (var object = storage.get(reportKey(item)); var output = new ByteArrayOutputStream()) {
      if (object.size() > MAX_REPORT_BYTES) throw new InvalidReportException();
      HttpAuditEngine.copyLimited(object.stream(), output, MAX_REPORT_BYTES);
      try { return RuleIssueSummary.parse(output.toByteArray(), item.getProblemCount()); }
      catch (IllegalArgumentException error) { throw new InvalidReportException(); }
    }
  }

  private boolean reportMissing(Submission item) {
    try { return storage.head(reportKey(item)).isEmpty(); }
    catch (IOException | RuntimeException probeError) {
      log.warn("rule_issue_backfill_probe_failed submission={} type={}",
          item.getId(), probeError.getClass().getSimpleName());
      return false;
    }
  }

  private static String reportKey(Submission item) {
    return item.getStorageDir() + "/attempt-" + item.getAttempts() + "/report.json";
  }

  private static Duration retryDelay(int attempt) {
    long seconds = 60L << Math.min(Math.max(attempt - 1, 0), 8);
    return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
  }

  private static final class InvalidReportException extends RuntimeException {}
}
