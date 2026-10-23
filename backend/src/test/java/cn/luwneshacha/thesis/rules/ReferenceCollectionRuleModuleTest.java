package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceCollectionRuleModuleTest {
  private final ReferenceCollectionRuleModule module = new ReferenceCollectionRuleModule();

  @Test
  void reportsLowReferenceCountAtTheFirstReference() {
    var first = reference("1", "J", 1);
    var document = document(List.of(first, reference("2", "M", 2)), List.of());

    var findings = module.check(document, RuleSet.schoolV1());

    var countFinding = findings.stream().filter(finding -> "REF-05".equals(finding.ruleId()))
        .findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, countFinding.judgement());
    assertEquals(first.source(), countFinding.source());
  }

  @Test
  void anchorsMissingReferenceCountToTheFirstParagraphWhenThereAreNoReferences() {
    var source = source(4);
    var paragraph = new ParagraphModel(List.of(new RunModel("正文", null, null, null,
        24, false, source)), null, null, null, null, null, source, null);

    var findings = module.check(document(List.of(), List.of(paragraph)), RuleSet.schoolV1());

    assertEquals(1, findings.size());
    assertEquals("REF-05", findings.get(0).ruleId());
    assertEquals(source, findings.get(0).source());
  }

  @Test
  void reportsDuplicateNumbersAndMissingCategoriesWithoutChangingFindingOrder() {
    var duplicate = reference("1", null, 2);
    var document = document(List.of(reference("1", "J", 1), duplicate), List.of());

    var findings = module.check(document, RuleSet.schoolV1());

    assertEquals(List.of("REF-05", "REF-03", "REF-02"),
        findings.stream().map(CheckResult::ruleId).toList());
    assertEquals(duplicate.source(), findings.get(1).source());
    assertEquals(duplicate.source(), findings.get(2).source());
  }

  @Test
  void doesNotReportTenReferencesAsTooFew() {
    var references = java.util.stream.IntStream.rangeClosed(1, 10)
        .mapToObj(index -> reference(String.valueOf(index), "J", index)).toList();

    var findings = module.check(document(references, List.of()), RuleSet.schoolV1());

    assertFalse(findings.stream().anyMatch(finding -> "REF-05".equals(finding.ruleId())));
    assertEquals(List.of(), findings);
  }

  private static ParsedDocument document(List<ReferenceEntryModel> references, List<ParagraphModel> paragraphs) {
    return new ParsedDocument("source", paragraphs, List.of(), List.of(), List.of(), List.of(), references);
  }

  private static ReferenceEntryModel reference(String number, String category, int index) {
    return new ReferenceEntryModel(source(index), number, "文献 " + index, category, false);
  }

  private static SourceAddress source(int index) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, 1);
  }
}
