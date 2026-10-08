package cn.luwneshacha.thesis.api;

import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.reporting.AnnotationItem;
import java.util.List;

public record InspectionResponse(
    String requestId,
    String sourceSha256,
    String ruleSetId,
    InspectionReport report,
    AnnotationInfo annotation) {
  public record AnnotationInfo(boolean available, String downloadPath, List<AnnotationItem> items) {
    public AnnotationInfo { items = List.copyOf(items); }
  }
}
