package cn.luwneshacha.thesis.report;

import cn.luwneshacha.thesis.inspection.InspectionReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonReportWriter {
  private final ObjectMapper mapper = new ObjectMapper();

  public String write(InspectionReport report) {
    try { return mapper.writeValueAsString(report); }
    catch (JsonProcessingException error) { throw new IllegalStateException("report serialization failed", error); }
  }
}
