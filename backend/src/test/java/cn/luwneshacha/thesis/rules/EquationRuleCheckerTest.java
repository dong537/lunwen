package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class EquationRuleCheckerTest {
  @Test
  void unknownAlignmentEvidenceDoesNotBecomeHardFailure() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 0);
    var paragraph = new ParagraphModel(List.of(), null, null, null, null, null,
        paragraphSource, null, null, null);
    var equation = new EquationModel(equationSource, "OMML", "x=1", "1-1", true, false);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var alignment = new EquationRuleChecker().check(document, equation, RuleSet.schoolV1())
        .stream().filter(result -> "EQ-03".equals(result.ruleId())).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, alignment.judgement());
    assertEquals("EQUATION_ALIGNMENT_UNKNOWN", alignment.reasonCode());
  }
}
