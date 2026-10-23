package cn.luwneshacha.thesis.reporting;

import cn.luwneshacha.thesis.document.SourceAddress;

/** source identifies the finding; anchorSource records its actual written location when known. */
public record AnnotationItem(String ruleId, AnnotationStatus status, String reason, SourceAddress source,
    SourceAddress anchorSource) {
  public AnnotationItem(String ruleId, AnnotationStatus status, String reason, SourceAddress source) {
    this(ruleId, status, reason, source, null);
  }
}
