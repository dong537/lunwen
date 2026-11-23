package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.api.InspectionResponse;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.inspection.InspectionReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonReportWriterImageCompatibilityTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void internalCaptionEvidenceDoesNotChangeReportOrInspectionResponseImageFields() throws Exception {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 2, 0, 0), 0, 4),
        "图4-1", 100, 200, false, null, null,
        0, 0, false, false, true, true, List.of("4", "5"));
    var report = new InspectionReport("sha", "school-v1", List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    JsonNode reportImage = mapper.readTree(new JsonReportWriter().write(report)).path("images").get(0);
    JsonNode responseImage = mapper.valueToTree(new InspectionResponse("request", "sha", "school-v1",
        report, new InspectionResponse.AnnotationInfo(false, null, List.of()),
        new InspectionResponse.ReportInfo(false, null))).path("report").path("images").get(0);

    for (JsonNode serialized : List.of(reportImage, responseImage)) {
      assertTrue(serialized.has("captionText"));
      assertTrue(serialized.has("widthEmu"));
      assertFalse(serialized.has("captionFragment"));
      assertFalse(serialized.has("captionNumberAmbiguous"));
      assertFalse(serialized.has("ambiguousCaptionChapters"));
    }
  }
}
