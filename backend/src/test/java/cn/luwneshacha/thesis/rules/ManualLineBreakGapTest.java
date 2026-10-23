package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualLineBreakGapTest {
  private static final RuleSet RULES = RuleSet.schoolV1();

  @Test
  void acceptsTwoManualLineBreaksAfterChapterHeadingAsOneBlankLine() {
    var heading = paragraph("第一章 绪论\n\n", 0, "黑体", 32, true);
    var body = paragraph("正文内容", 1, "宋体", 24, false);

    var findings = headingModule().check(document(heading, body), RULES);

    assertNoReason(findings, "CHAPTER_BODY_BLANK_LINE_MISSING");
    assertNoReason(findings, "CHAPTER_BODY_BLANK_LINE_COUNT_MISMATCH");
  }

  @Test
  void acceptsManualLineBreakAfterAbstractTitleAsOneBlankLine() {
    var title = paragraph("摘    要\n\n", 0, "黑体", 32, true);
    var body = paragraph("本文研究了相关问题。", 1, "宋体", 24, false);

    var findings = new AbstractRuleChecker(new ParagraphFormatChecker())
        .check(document(title, body), RULES);

    assertNoReason(findings, "ABSTRACT_TITLE_BLANK_LINE_MISSING");
    assertNoReason(findings, "ABSTRACT_TITLE_SPACING_MISMATCH");
  }

  @Test
  void acceptsManualLineBreakBetweenAbstractTextAndKeywordsAsOneBlankLine() {
    var title = paragraph("摘    要", 0, "黑体", 32, true);
    var body = paragraph("本文研究了相关问题。\n\n", 1, "宋体", 24, false);
    var keywords = paragraph("关键词：甲；乙；丙", 2, "宋体", 24, false);

    var findings = new AbstractRuleChecker(new ParagraphFormatChecker())
        .check(document(title, paragraph("", 3, "宋体", 24, false), body, keywords), RULES);

    assertNoReason(findings, "ABSTRACT_KEYWORD_BLANK_LINE_MISSING");
  }

  @Test
  void acceptsManualLineBreakAfterReferenceTitleAsOneBlankLine() {
    var heading = paragraph("参考文献\n\n", 0, "黑体", 32, true);
    var body = paragraph("[1] 文献条目。", 1, "宋体", 24, false);

    var findings = new EndMatterRuleChecker().check(document(heading, body), RULES);

    assertNoReason(findings, "REFERENCE_TITLE_BLANK_LINE_MISSING");
  }

  @Test
  void acceptsManualLineBreakAfterFourSpaceAcknowledgementTitle() {
    var heading = paragraph("致    谢\n\n", 0, "黑体", 32, true);
    var body = paragraph("感谢指导教师。", 1, "宋体", 24, false);
    var parsed = document(heading, body);

    var findings = headingModule().check(parsed, RULES);
    findings = java.util.stream.Stream.concat(findings.stream(),
        new EndMatterRuleChecker().check(parsed, RULES).stream()).toList();

    assertNoReason(findings, "ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH");
    assertNoReason(findings, "ACKNOWLEDGEMENT_TITLE_BLANK_LINE_MISSING");
  }

  @Test
  void acceptsManualLineBreakAfterAppendixTitleAsOneBlankLine() {
    var heading = paragraph("附    录\n\n", 0, "黑体", 32, true);
    var body = paragraph("附录A 名词术语", 1, "黑体", 24, true);

    var findings = new AppendixRuleModule().check(document(heading, body), RULES);

    assertNoReason(findings, "APPENDIX_TITLE_BLANK_LINE_MISSING");
    assertNoReason(findings, "APPENDIX_TITLE_BLANK_LINE_COUNT_MISMATCH");
  }

  private static HeadingDocumentRuleModule headingModule() {
    var formatChecker = new ParagraphFormatChecker();
    return new HeadingDocumentRuleModule(new HeadingRuleChecker(formatChecker), formatChecker);
  }

  private static void assertNoReason(List<CheckResult> findings, String reason) {
    assertTrue(findings.stream().noneMatch(result -> reason.equals(result.reasonCode())),
        reason + " was reported: " + findings);
  }

  private static ParsedDocument document(ParagraphModel... paragraphs) {
    return new ParsedDocument("source", List.of(paragraphs), List.of(), List.of());
  }

  private static ParagraphModel paragraph(String text, int index, String eastAsiaFont,
      int size, boolean bold) {
    var source = new SourceAddress("word/document.xml", List.of(0, index), 0, text.length());
    var run = new RunModel(text, "Times New Roman", eastAsiaFont, null, size, bold, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, 360, "auto", source,
        0, null, "left");
  }
}
