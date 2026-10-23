package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class TocTableStateIsolationTest {
  private static final RuleSet RULES = RuleSet.schoolV1();

  @Test
  void contentsCheckerDoesNotReadTableTitlesOrEntriesAsContents() {
    var title = paragraph("目    录", List.of(0, 1, 0, 0), "黑体", 32, true);
    var tableEntry = paragraph("2.1 表格示例……1", List.of(0, 1, 0, 1), "宋体", 24, false);
    var bodyHeading = paragraph("2.1 ESP32设备2", List.of(0, 2), "黑体", 28, true);
    var document = document(List.of(title, tableEntry, bodyHeading), table(List.of(0, 1)));

    var findings = new ContentsRuleChecker(new ParagraphFormatChecker()).check(document, RULES);

    assertFalse(findings.stream().anyMatch(result -> result.source().equals(title.source())
        || result.source().equals(tableEntry.source())), findings.toString());
    assertFalse(findings.stream().anyMatch(result -> result.source().equals(bodyHeading.source())
        && result.ruleId().equals("TOC-ENTRY-PAGE")), findings.toString());
  }

  @Test
  void tableContentsTitleDoesNotSuppressChapterGapCheck() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0), "黑体", 32, true);
    var tableTitle = paragraph("目录", List.of(0, 1, 0, 0), "黑体", 32, true);
    var tableEntry = paragraph("2.1 表格示例……1", List.of(0, 1, 0, 1), "宋体", 24, false);
    var bodyHeading = paragraph("第二章 2024", List.of(0, 2), "黑体", 28, true);
    var document = document(List.of(chapter, tableTitle, tableEntry, bodyHeading), table(List.of(0, 1)));

    var findings = headingModule().check(document, RULES);

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("HEAD-02-BLANK")
        && result.source().equals(bodyHeading.source())), findings.toString());
  }

  @Test
  void tableContentsTitleDoesNotPreventBodyChecksFromStartingAtChapter() {
    var tableTitle = paragraph("目录", List.of(0, 0, 0, 0), "黑体", 32, true);
    var tableEntry = paragraph("第一章 2024", List.of(0, 0, 0, 1), "宋体", 24, false);
    var chapter = paragraph("第一章 2024", List.of(0, 1), "黑体", 32, true);
    var body = paragraph("正文内容", List.of(0, 2), "Arial", 18, false);
    var document = document(List.of(tableTitle, tableEntry, chapter, body), table(List.of(0, 0)));

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker()).check(document, RULES);

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.source().equals(body.source())), findings.toString());
  }

  private static HeadingDocumentRuleModule headingModule() {
    var formatChecker = new ParagraphFormatChecker();
    return new HeadingDocumentRuleModule(new HeadingRuleChecker(formatChecker), formatChecker);
  }

  private static ParsedDocument document(List<ParagraphModel> paragraphs, TableModel table) {
    return new ParsedDocument("source", paragraphs, List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());
  }

  private static TableModel table(List<Integer> path) {
    return new TableModel(new SourceAddress("word/document.xml", path, 0, 0),
        null, null, 2, 1, false, false, false, false, false, false);
  }

  private static ParagraphModel paragraph(String text, List<Integer> path,
      String eastAsiaFont, int size, boolean bold) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    var run = new RunModel(text, "Times New Roman", eastAsiaFont, null, size, bold, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, 360, "auto", source,
        0, null, "left");
  }
}
