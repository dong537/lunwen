package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndMatterRuleCheckerTest {
  @Test
  void recordsVisitedReferenceAndAcknowledgementBlankLineCandidates() {
    var reference = paragraph("参考文献", 0);
    var referenceBlank = paragraph("", 1);
    var referenceBody = paragraph("[1] 文献", 2);
    var acknowledgement = paragraph("致谢", 3);
    var acknowledgementBlank = paragraph("", 4);
    var acknowledgementBody = paragraph("感谢老师", 5);
    var document = new ParsedDocument("source", List.of(reference, referenceBlank, referenceBody,
        acknowledgement, acknowledgementBlank, acknowledgementBody), List.of(), List.of());

    var evaluation = new EndMatterRuleChecker().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().isEmpty());
    assertEquals(List.of(
        new RuleRun("REF-01-BLANK", "EndMatterRuleChecker", 1, 1, 0, null),
        new RuleRun("END-01-BLANK", "EndMatterRuleChecker", 1, 1, 0, null)),
        evaluation.ruleRuns());
  }

  @Test
  void keepsUnverifiableDocumentEndAsUnknown() {
    var document = new ParsedDocument("source", List.of(paragraph("致谢", 0)),
        List.of(), List.of());

    var evaluation = new EndMatterRuleChecker().evaluate(document, RuleSet.schoolV1());

    assertEquals(Judgement.REVIEW, evaluation.results().getFirst().judgement());
    assertEquals(List.of(new RuleRun("END-01-BLANK", "EndMatterRuleChecker", 1, 0, 1,
        null)), evaluation.ruleRuns());
  }

  @Test
  void doesNotCheckBlankLineAfterAcknowledgementTextInsideATable() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 2);
    var paragraph = new ParagraphModel(List.of(new RunModel("致谢", null, null, null,
        24, false, source)), null, null, null, null, null, null, source, null, null, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var evaluation = new EndMatterRuleChecker().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().isEmpty(), evaluation.results().toString());
    assertTrue(evaluation.ruleRuns().isEmpty());
  }

  private static ParagraphModel paragraph(String text, int block) {
    var source = new SourceAddress("word/document.xml", List.of(0, block), 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, null, null, null,
        24, false, source)), null, null, null, null, null, null, source, null, null, null);
  }
}
