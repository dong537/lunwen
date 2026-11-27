package cn.luwneshacha.platform.account;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL transaction lock for major assignments; always acquire before any user row lock. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class MajorAssignmentLock {
  // "MAJORSNC": reserved exclusively for catalog renames and account major assignments.
  private static final long KEY = 0x4D414A4F52534E43L;
  private final EntityManager entityManager;
  public MajorAssignmentLock(EntityManager entityManager) { this.entityManager = entityManager; }

  public void shared() {
    entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock_shared(:lockKey)")
        .setParameter("lockKey", KEY).getSingleResult();
  }

  public void exclusive() {
    entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(:lockKey)")
        .setParameter("lockKey", KEY).getSingleResult();
  }
}
