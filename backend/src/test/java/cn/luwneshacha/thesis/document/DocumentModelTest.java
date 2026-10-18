package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentModelTest {
  @Test
  void parsedDocumentCarriesStructuredObjects() {
    var document = new ParsedDocument("sha", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    assertTrue(document.tables().isEmpty());
    assertTrue(document.equations().isEmpty());
    assertTrue(document.references().isEmpty());
  }
}
