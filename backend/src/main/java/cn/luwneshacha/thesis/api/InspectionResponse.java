package cn.luwneshacha.thesis.api;

import cn.luwneshacha.thesis.inspection.InspectionReport;

public record InspectionResponse(
    String requestId,
    String sourceSha256,
    String ruleSetId,
    InspectionReport report,
    AnnotationInfo annotation) {
  public record AnnotationInfo(boolean available, String downloadPath) {}
}
