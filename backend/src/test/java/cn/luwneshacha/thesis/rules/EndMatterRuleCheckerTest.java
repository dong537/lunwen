package cn.luwneshacha.thesis.rules;

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
  void doesNotCheckBlankLineAfterAcknowledgementTextInsideATable() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 2);
    var paragraph = new ParagraphModel(List.of(new RunModel("致谢", null, null, null,
        24, false, source)), null, null, null, null, null, null, source, null, null, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var findings = new EndMatterRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.isEmpty(), findings.toString());
  }
}
