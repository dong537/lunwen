package cn.luwneshacha.platform.submission;

import java.time.*;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SubmissionTasks {
  public record Claim(long id, String directory, int attempt, Instant startedAt) {
    public String attemptPrefix() { return directory + "/attempt-" + attempt + "/"; }
  }
  private final SubmissionRepository repository;
  private final TransactionTemplate transaction;
  private final Duration lease;
  @Autowired public SubmissionTasks(SubmissionRepository repository, PlatformTransactionManager manager, WorkerProperties properties) {
    this(repository, manager, properties.lease());
  }
  public SubmissionTasks(SubmissionRepository repository, PlatformTransactionManager manager, Duration lease) {
    this.repository = repository; transaction = new TransactionTemplate(manager); this.lease = lease;
  }
  public Optional<Claim> claim(Instant now) {
    Instant cutoff = now.minus(lease);
    for (Submission item : repository.candidates(now, cutoff, PageRequest.of(0, 10))) {
      boolean claimed = Boolean.TRUE.equals(transaction.execute(status -> repository.claim(item.getId(), item.getAttempts(), now, cutoff) == 1));
      if (claimed) return Optional.of(new Claim(item.getId(), item.getStorageDir(), item.getAttempts() + 1, now));
    }
    return Optional.empty();
  }
  public boolean succeed(Claim claim, int problems, Instant now) {
    if (problems < 0) throw new IllegalArgumentException("negative problem count");
    return Boolean.TRUE.equals(transaction.execute(status -> repository.succeed(claim.id(), claim.attempt(), problems, now, now.minus(lease)) == 1));
  }
  public boolean fail(Claim claim, boolean retryable, String message, Instant now) {
    boolean retry = retryable && claim.attempt() < 3;
    return Boolean.TRUE.equals(transaction.execute(status -> repository.fail(claim.id(), claim.attempt(),
        retry ? SubmissionStatus.PENDING : SubmissionStatus.FAILED, retry ? null : message,
        retry ? null : now, retry ? now.plusSeconds(30) : now, now.minus(lease)) == 1));
  }
  public int recoverExhausted(Instant now) {
    return transaction.execute(status -> repository.recoverExhausted(now, now.minus(lease)));
  }
}
