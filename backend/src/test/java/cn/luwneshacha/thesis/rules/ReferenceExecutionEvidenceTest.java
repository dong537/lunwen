package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceExecutionEvidenceTest {
  @Test
  void referenceEntryEvidenceCountsActualEntriesAndKeepsUnknownCategoriesUnknown() {
    var complete = reference("1", "M", 1);
    var unknownCategory = reference("2", null, 2);
    var document = document(List.of(), List.of(complete, unknownCategory));
    var evaluation = new ReferenceRuleChecker().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("REF-07", "ReferenceRuleChecker", 2, 2, 0, null),
        run(evaluation, "REF-07"));
    assertEquals(new RuleRun("REF-04", "ReferenceRuleChecker", 2, 1, 1, null),
        run(evaluation, "REF-04"));
    assertEquals(new ReferenceRuleChecker().check(document, RuleSet.schoolV1()), evaluation.results());
  }

  @Test
  void referenceNumberSpacingRemainsUnknownWithoutAVisibleMatchingNumber() {
    var reference = new ReferenceEntryModel(source(1), "1",
        "Author. Title[J]. Journal, 2024, 1(1): 1-2.", "J", false);
    var document = document(List.of(), List.of(reference));
    var checker = new ReferenceRuleChecker();

    var evaluation = checker.evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("REF-07", "ReferenceRuleChecker", 1, 0, 1, null),
        run(evaluation, "REF-07"));
    assertEquals(checker.check(document, RuleSet.schoolV1()), evaluation.results());
    assertFalse(evaluation.results().stream()
        .anyMatch(result -> "REFERENCE_NUMBER_SPACING_MISMATCH".equals(result.reasonCode())));
  }

  @Test
  void uncheckableSpacingDoesNotSuppressAReferenceNumberSequenceFinding() {
    var reference = new ReferenceEntryModel(source(1), "2",
        "Author. Title[J]. Journal, 2024, 1(1): 1-2.", "J", false);
    var evaluation = new ReferenceRuleChecker().evaluate(
        document(List.of(), List.of(reference)), RuleSet.schoolV1());

    assertEquals(new RuleRun("REF-07", "ReferenceRuleChecker", 1, 0, 1, null),
        run(evaluation, "REF-07"));
    assertTrue(evaluation.results().stream().anyMatch(result ->
        "REF-07".equals(result.ruleId())
            && "REFERENCE_NUMBER_SEQUENCE_MISMATCH".equals(result.reasonCode())));
  }

  @Test
  void referenceFormattingCannotPassWhenEntryHasNoMatchingParagraph() {
    var document = document(List.of(), List.of(reference("1", "J", 1)));
    var evaluation = new ReferenceFormatRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    for (String id : List.of("REF-FONT", "REF-SIZE", "REF-LINE")) {
      assertEquals(new RuleRun(id, "ReferenceFormatRuleChecker", 1, 0, 1, null),
          run(evaluation, id));
    }
    assertTrue(evaluation.results().isEmpty());
  }

  @Test
  void collectionEvidenceSeparatesCountCategoryAndUnresolvedNumber() {
    var document = document(List.of(), List.of(reference("1", "J", 1), reference(null, null, 2)));
    var evaluation = new ReferenceCollectionRuleModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("REF-05", "ReferenceCollectionRuleModule", 1, 1, 0, null),
        run(evaluation, "REF-05"));
    assertEquals(new RuleRun("REF-02", "ReferenceCollectionRuleModule", 2, 2, 0, null),
        run(evaluation, "REF-02"));
    assertEquals(new RuleRun("REF-03", "ReferenceCollectionRuleModule", 2, 1, 1, null),
        run(evaluation, "REF-03"));
  }

  @Test
  void citationRelationshipRemainsUnknownWithoutBodyBoundary() {
    var document = document(List.of(paragraph("摘要内容", 0)), List.of(reference("1", "J", 1)));
    var evaluation = citationModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("REF-03", "CitationRuleModule", 1, 0, 1, null),
        run(evaluation, "REF-03"));
    assertFalse(evaluation.ruleRuns().stream().anyMatch(run -> run.ruleId().equals("CITE-01")));
  }

  @Test
  void citationEvidenceCountsFoundBodyMarkersAndReferenceRelationships() {
    var document = document(List.of(chapter(0), paragraph("本文引用[1]。", 1)),
        List.of(reference("1", "J", 2)));
    var evaluation = citationModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("CITE-01", "CitationRuleModule", 1, 1, 0, null),
        run(evaluation, "CITE-01"));
    assertEquals(new RuleRun("REF-03", "CitationRuleModule", 2, 2, 0, null),
        run(evaluation, "REF-03"));
    assertEquals(citationModule().check(document, RuleSet.schoolV1()), evaluation.results());
  }

  @Test
  void aDetectedBodyWithNoCitationDoesNotProveTheReferenceRelationship() {
    var document = document(List.of(chapter(0), paragraph("本文没有引用标记。", 1)),
        List.of(reference("1", "J", 2)));
    var evaluation = citationModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("REF-03", "CitationRuleModule", 1, 0, 1, null),
        run(evaluation, "REF-03"));
    assertTrue(evaluation.results().stream()
        .anyMatch(result -> "REFERENCE_NOT_CITED".equals(result.reasonCode())));
  }

  @Test
  void malformedCitationDoesNotMakeTheReferenceRelationshipComplete() {
    var document = document(List.of(chapter(0), paragraph("本文引用[1;2]。", 1)),
        List.of(reference("1", "J", 2), reference("2", "J", 3)));
    var evaluation = citationModule().evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("CITE-01", "CitationRuleModule", 1, 1, 0, null),
        run(evaluation, "CITE-01"));
    assertEquals(new RuleRun("REF-03", "CitationRuleModule", 5, 0, 5, null),
        run(evaluation, "REF-03"));
  }

  private static CitationRuleModule citationModule() {
    return new CitationRuleModule(new HeadingRuleChecker(new ParagraphFormatChecker()));
  }

  private static RuleRun run(RuleModule.Evaluation evaluation, String id) {
    return evaluation.ruleRuns().stream().filter(run -> run.ruleId().equals(id))
        .findFirst().orElseThrow();
  }

  private static ParsedDocument document(List<ParagraphModel> paragraphs,
      List<ReferenceEntryModel> references) {
    return new ParsedDocument("source", paragraphs, List.of(), List.of(), List.of(), List.of(), references);
  }

  private static ReferenceEntryModel reference(String number, String category, int index) {
    return new ReferenceEntryModel(source(index), number, "[" + number + "] 文献" + index,
        category, false, "作者", "题名", "出版者", "2024", null);
  }

  private static ParagraphModel chapter(int index) {
    var source = source(index);
    return new ParagraphModel(List.of(new RunModel("绪论", "Times New Roman", "Times New Roman", "黑体",
        null, 32, false, null, source)), null, null, null, null, null, null, source, null, 0, "left", "第1章",
        null, null, false, null, null, true);
  }

  private static ParagraphModel paragraph(String text, int index) {
    var source = source(index);
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, null, source)), null, null, null, null, null, null, source, null, null, null);
  }

  private static SourceAddress source(int index) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, 100);
  }
}
