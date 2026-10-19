package cn.luwneshacha.platform.submission;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "submission")
public class Submission {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false) private Long studentId;
  @Column(nullable = false, length = 255) private String originalFilename;
  @Column(nullable = false) private long fileSize;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private SubmissionStatus status;
  @Column(nullable = false, unique = true, length = 255) private String storageDir;
  @Column(nullable = false) private int problemCount;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Map<String, Integer> byScope;
  @Column(length = 500) private String errorMessage;
  @Column(nullable = false) private int attempts;
  private Instant startedAt;
  private Instant finishedAt;
  @Column(nullable = false) private Instant createdAt;
  @Column(nullable = false) private Instant updatedAt;
  protected Submission() {}
  public Submission(long studentId, String filename, long size, String directory, Instant now) {
    this.studentId = studentId; originalFilename = filename; fileSize = size; storageDir = directory;
    status = SubmissionStatus.PENDING; createdAt = now; updatedAt = now;
  }
  public void reserveUploadUntil(Instant deadline) { updatedAt = deadline; }
  public boolean isUploadReserved(Instant now) {
    return status == SubmissionStatus.PENDING && attempts == 0 && updatedAt.isAfter(now);
  }
  public Long getId() { return id; }
  public Long getStudentId() { return studentId; }
  public String getOriginalFilename() { return originalFilename; }
  public long getFileSize() { return fileSize; }
  public SubmissionStatus getStatus() { return status; }
  public String getStorageDir() { return storageDir; }
  public int getProblemCount() { return problemCount; }
  public Map<String,Integer> getByScope() { return byScope; }
  public String getErrorMessage() { return errorMessage; }
  public int getAttempts() { return attempts; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getFinishedAt() { return finishedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
