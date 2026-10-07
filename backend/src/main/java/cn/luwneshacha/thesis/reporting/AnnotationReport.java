package cn.luwneshacha.thesis.reporting;

import java.util.List;

public record AnnotationReport(List<AnnotationItem> items, boolean outputWritten) {
  public AnnotationReport { items = List.copyOf(items); }
}
