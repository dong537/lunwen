package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class EquationSequenceRuleCheckerTest {
  @Test
  void ignoresChapterLikeTextInsideTableWhenCheckingEquationChapterNumber() {
    ParagraphModel chapterOne = paragraph("\u7b2c1\u7ae0 \u5f15\u8a00", 0, 0);
    ParagraphModel tableText = paragraph("\u7b2c9\u7ae0 \u8868\u683c\u4e2d\u7684\u6587\u5b57", 0, 1, 0, 0);
    var table = new TableModel(source(0, 1), null, null,
        1, 1, false, false, false, false, false, false);
    var equation = new EquationModel(source(0, 2, 0), "OMML", "x", "1-1", true, false);
    var document = new ParsedDocument("source", List.of(chapterOne, tableText), List.of(), List.of(),
        List.of(table), List.of(equation), List.of());

    var sequenceFindings = new EquationSequenceRuleChecker().check(document).stream()
        .filter(result -> "EQUATION_NUMBER_SEQUENCE_MISMATCH".equals(result.reasonCode()))
        .count();

    assertEquals(0, sequenceFindings);
  }

  private static ParagraphModel paragraph(String text, int... path) {
    SourceAddress source = source(path);
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "\u5b8b\u4f53", null,
        24, false, source)), 0, null, null, null, null, source, 0, null, "left");
  }

  private static SourceAddress source(int... path) {
    return new SourceAddress("word/document.xml", java.util.Arrays.stream(path).boxed().toList(), 0, 0);
  }
}
