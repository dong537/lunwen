package cn.luwneshacha.platform.submission;

import java.time.Instant;
import java.util.Map;

public record SubmissionView(String id, String studentId, long submissionNumber, String originalFilename,
    long fileSize, SubmissionStatus status, Integer problemCount, Map<String,Integer> byScope,
    String errorMessage, Instant createdAt, Instant startedAt, Instant finishedAt,
    boolean canDownloadOriginal, boolean canDownloadMarked) {
  static SubmissionView from(Submission item, long number) {
    boolean success = item.getStatus() == SubmissionStatus.SUCCESS;
    return new SubmissionView(item.getId().toString(), item.getStudentId().toString(), number,
        item.getOriginalFilename(), item.getFileSize(), item.getStatus(), success ? item.getProblemCount() : null,
        success ? item.getByScope() : null, item.getErrorMessage(), item.getCreatedAt(), item.getStartedAt(), item.getFinishedAt(),
        item.getStatus() != SubmissionStatus.FAILED && !item.isUploadReserved(Instant.now()), success);
  }
}
