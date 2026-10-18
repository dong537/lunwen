package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class TocBodyClassificationRegressionTest {
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Test
  void missingPageStyledChapterEntryCannotStartTheMainBody() {
    for (String[] style : contentsStyles()) {
      var entry = paragraph("第一章  绪论", 1, style[0], style[1], false);

      assertFalse(regions.isBodyChapterStart(entry), style[0] + " / " + style[1]);
      assertFalse(regions.isNumberedChapterHeading(entry), style[0] + " / " + style[1]);
    }
  }

  @Test
  void missingPageContentsEntriesDoNotReceiveBodyIndentWhileLaterBodyStillDoes() {
    var contentsTitle = paragraph("目    录", 0, null, null, false);
    var contentsChapter = paragraph("第一章  绪论", 1, "TOC1", null, false);
    var contentsEntry = paragraph("研究背景", 2, "TOC2", null, false);
    var actualChapter = paragraph("第一章  绪论", 3, "Heading1", null, false);
    var body = paragraph("正文内容。", 4, null, null, false);
    var document = document(contentsTitle, contentsChapter, contentsEntry, actualChapter, body);

    var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
        && (result.source().equals(contentsChapter.source())
            || result.source().equals(contentsEntry.source()))), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
        && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
        findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-LINE")
        && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
        findings.toString());
  }

  @Test
  void definiteContentsEvidenceIsExcludedEvenWithoutAContentsTitle() {
    var chapter = paragraph("第一章  绪论", 0, "Heading1", null, false);
    for (boolean generated : List.of(false, true)) {
      var entry = paragraph("研究背景", 1, generated ? null : "TOC2", null, generated);
      var body = paragraph("正文内容。", 2, null, null, false);

      var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
          .check(document(chapter, entry, body), RuleSet.schoolV1());

      assertTrue(findings.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
          && result.source().equals(entry.source())), findings.toString());
      assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
          && result.source().equals(body.source())), findings.toString());
    }
  }

  @Test
  void definiteContentsEntriesCannotStartEndMatterOrAppendixRegions() {
    for (String text : List.of("参考文献", "致    谢", "附    录", "附录A 符号说明")) {
      for (boolean generated : List.of(false, true)) {
        var entry = paragraph(text, 1, generated ? null : "TOC1", null, generated);

        assertFalse(regions.isEndMatterHeading(entry), text + " / generated=" + generated);
        assertNull(regions.appendixHeadingLetter(entry), text + " / generated=" + generated);
      }
    }
  }

  @Test
  void realHeadingsAndBodyStylesThatOnlyMentionContentsRemainEligible() {
    assertTrue(regions.isBodyChapterStart(paragraph("第一章  绪论", 0, "Heading1", null, false)));
    assertTrue(regions.isEndMatterHeading(paragraph("参考文献", 2, "Heading1", null, false)));
    for (String style : List.of("BodyTOC1", "TOCReference", "目录说明", "Contents")) {
      var chapter = paragraph("第一章  绪论", 0, "Heading1", null, false);
      var body = paragraph("正文内容。", 1, style, style, false);

      var findings = new BodyFormatRuleChecker(new ParagraphFormatChecker())
          .check(document(chapter, body), RuleSet.schoolV1());

      assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("BODY-INDENT")
          && result.judgement() == Judgement.FAIL && result.source().equals(body.source())),
          style + ": " + findings);
    }
  }

  @Test
  void missingPageThirdLevelContentsEntryDoesNotReceiveHeadingIndent() {
    var contentsTitle = paragraph("目    录", 0, null, null, false);
    var entry = paragraph("1.1.1 研究范围", 1, "TOC3", null, false);
    var chapter = paragraph("第一章  绪论", 2, "Heading1", null, false);
    var actualHeading = paragraph("1.1.1 实际正文标题", 3, "Heading3", null, false);
    var document = document(contentsTitle, entry, chapter, actualHeading);

    var findings = headingModule().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(entry.source())),
        findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("HEAD-03-INDENT")
        && result.judgement() == Judgement.FAIL && result.source().equals(actualHeading.source())),
        findings.toString());
  }

  @Test
  void definiteContentsLandmarksDoNotReceiveNamedSectionFormatting() {
    for (String text : List.of("参考文献", "致谢", "附录")) {
      for (boolean generated : List.of(false, true)) {
        var entry = paragraph(text, 1, generated ? null : "TOC1", null, generated);
        var actualHeading = paragraph(text, 3, "Heading1", null, false);
        var document = document(paragraph("目    录", 0, null, null, false), entry,
            paragraph("第一章  绪论", 2, "Heading1", null, false), actualHeading);

        var findings = headingModule().check(document, RuleSet.schoolV1());

        assertTrue(findings.stream().noneMatch(result -> result.source().equals(entry.source())),
            text + " / generated=" + generated + ": " + findings);
        assertTrue(findings.stream().anyMatch(result -> result.source().equals(actualHeading.source())
            && result.judgement() == Judgement.FAIL), findings.toString());
      }
    }
  }

  private static HeadingDocumentRuleModule headingModule() {
    var format = new ParagraphFormatChecker();
    return new HeadingDocumentRuleModule(new HeadingRuleChecker(format), format);
  }

  private static List<String[]> contentsStyles() {
    return List.of(new String[] {"TOC1", null}, new String[] {"toc2", null},
        new String[] {"TOC3", null}, new String[] {"20", "toc 1"},
        new String[] {"21", "目录 1"});
  }

  private static ParsedDocument document(ParagraphModel... paragraphs) {
    return new ParsedDocument("source", List.of(paragraphs), List.of(), List.of());
  }

  private static ParagraphModel paragraph(String text, int index, String styleId,
      String styleName, boolean generatedTocField) {
    var source = new SourceAddress("word/document.xml", List.of(0, index), 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), 0, null, null, null, 240, "auto", source, 0, null, "left",
        null, null, null, generatedTocField, null, null, false, false, false, false,
        null, null, styleId, false, false, styleName);
  }
}
