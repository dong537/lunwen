package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceElectronicRuleTest {
  @Test
  void doesNotEnforceElectronicReferenceFieldsThatTheSchoolSpecDoesNotList() {
    var reference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0), 0, 30),
        "1", "[1] 作者. 网络资料[EB/OL]. [2024-01-01].", "EB/OL", false,
        "作者", "网络资料", null, "2024", null);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(reference));

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("REF-06")));
  }

  @Test
  void doesNotRequireUnspecifiedPublicationOrAccessDateFields() {
    var reference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0), 0, 48),
        "1", "[1] 作者. 网络期刊[J/OL]. https://example.edu/journal", "J/OL", false,
        "作者", "网络期刊", "https://example.edu/journal", null, null);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(reference));

    assertTrue(new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .noneMatch(result -> result.ruleId().equals("REF-06")));
  }
}
