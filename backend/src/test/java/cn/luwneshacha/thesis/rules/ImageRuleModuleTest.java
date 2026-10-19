package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageRuleModuleTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "\u3000", "\u00a0", "\t", "\u200b", "\n"})
  void captionSpacingFailureDoesNotRemoveItsNumberFromTheSequence(String separator) {
    var findings = numberedFigures("图5-1 第一幅图", "图5-2" + separator + "第二幅图", "图5-3 第三幅图");
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("FIG-03")
        && result.judgement() == Judgement.FAIL), findings.toString());
    var sequence = findings.stream().filter(result -> result.ruleId().equals("FIG-05")).toList();
    assertEquals(3, sequence.size(), sequence.toString());
    assertTrue(sequence.stream().allMatch(result -> result.judgement() == Judgement.PASS), sequence.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "4"})
  void duplicateAndSkippedNumbersStillFailAfterACaptionSpacingError(String lastNumber) {
    var findings = numberedFigures("图5-1 第一幅图", "图5-2第二幅图", "图5-" + lastNumber + " 第三幅图");
    var last = findings.stream().filter(result -> result.ruleId().equals("FIG-05")
        && result.actual().endsWith("第三幅图")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, last.judgement());
    assertEquals("图5-3", last.expected());
  }

  private static List<CheckResult> numberedFigures(String... captions) {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    var images = new java.util.ArrayList<ImageModel>();
    paragraphs.add(paragraph("第五章 系统设计", List.of(0, 0), "黑体", 32, "center"));
    for (int index = 0; index < captions.length; index++) {
      var caption = paragraph(captions[index], List.of(0, index * 2 + 2), "宋体", 21, "center");
      paragraphs.add(caption);
      images.add(new ImageModel(new SourceAddress("word/document.xml", List.of(0, index * 2 + 1), 0, 0),
          caption.source(), captions[index], 100, 100, true, true, true));
    }
    return new ImageRuleChecker().check(new ParsedDocument("sha", paragraphs, List.of(), List.of(),
        List.of(), List.of(), List.of(), images), RuleSet.schoolV1());
  }

  @Test
  void reportsMissingCaptionForAnImageInTheMainBody() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0), "黑体", 32, "left");
    var imageSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 0);
    var image = new ImageModel(imageSource, null, null, 100, 100, true, true, null);
    var document = document(List.of(chapter), image);

    var findings = new ImageRuleChecker().check(document, RuleSet.schoolV1());

    var missing = findings.stream().filter(f -> "FIG-03".equals(f.ruleId())).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, missing.judgement());
    assertEquals("FIGURE_CAPTION_MISSING", missing.reasonCode());
  }

  @Test
  void checksCaptionReferenceAlignmentAndNumberSequenceInsideTheImageModule() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0), "黑体", 32, "left");
    var reference = paragraph("如图1-1所示，系统结构如下。", List.of(0, 1), "宋体", 24, "left");
    var caption = paragraph("图1-1 系统结构图", List.of(0, 3), "宋体", 21, "center");
    var imageSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 0);
    var image = new ImageModel(imageSource, caption.source(), "图1-1 系统结构图", 100, 100,
        true, true, true);
    var document = document(List.of(chapter, reference, caption), image);

    var findings = new ImageRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(f -> "FIG-01".equals(f.ruleId()) && f.judgement() == Judgement.PASS));
    assertTrue(findings.stream().anyMatch(f -> "FIG-02".equals(f.ruleId()) && f.judgement() == Judgement.PASS));
    assertTrue(findings.stream().anyMatch(f -> "FIG-03".equals(f.ruleId()) && f.judgement() == Judgement.PASS), findings.toString());
    assertTrue(findings.stream().anyMatch(f -> "FIG-05".equals(f.ruleId()) && f.judgement() == Judgement.PASS));
    assertTrue(findings.stream().anyMatch(f -> "CAPTION-FONT".equals(f.ruleId())));
    assertTrue(findings.stream().anyMatch(f -> "CAPTION-SIZE".equals(f.ruleId())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"5- 4", "5 -4", "5 - 4", "5-\u00a04", "5\u3000-\u30004", "5\t-\t4"})
  void recognizesHorizontalWhitespaceBesideTheReferenceHyphen(String number) {
    assertEquals(Judgement.PASS, figureReference("5-4", "调酒师上传教程界面流程图，如图" + number + "所示。").judgement());
  }

  @ParameterizedTest
  @ValueSource(strings = {"没有图号的正文。", "如图5-40所示。", "如图5- 40所示。", "如图5－ 4所示。",
      "如图5-\n4所示。", "如图5\n-4所示。", "如图5-\u20284所示。"})
  void doesNotInventAMatchingReferenceFromOtherNumbersOrVerticalWhitespace(String reference) {
    var result = figureReference("5-4", reference);
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void doesNotJoinDigitsOrParagraphsToCreateAReference() {
    assertEquals(Judgement.FAIL, figureReference("50-4", "如图5 0-4所示。").judgement());
    assertEquals(Judgement.FAIL, figureReference("5-40", "如图5-4 0所示。").judgement());
    assertEquals(Judgement.FAIL, figureReference("5-4", "如图5-", "4所示。").judgement());
  }

  private static CheckResult figureReference(String number, String... references) {
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    paragraphs.add(paragraph("第五章 系统设计", List.of(0, 0), "黑体", 32, "center"));
    for (int index = 0; index < references.length; index++) {
      paragraphs.add(paragraph(references[index], List.of(0, index + 1), "宋体", 24, "left"));
    }
    var caption = paragraph("图" + number + " 系统结构图", List.of(0, references.length + 2), "宋体", 21, "center");
    paragraphs.add(caption);
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, references.length + 1), 0, 0),
        caption.source(), "图" + number + " 系统结构图", 100, 100, true, true, true);
    var results = new ImageRuleChecker().check(document(paragraphs, image), RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("FIG-01")).toList();
    assertEquals(1, results.size(), results.toString());
    return results.get(0);
  }

  @Test
  void doesNotTreatAnUnnumberedFigureMentionInATableAsABodyReference() {
    var chapter = paragraph("第一章 绪论", List.of(0, 0), "黑体", 32, "left");
    var tableReference = paragraph("如图所示，系统结构如下", List.of(0, 1, 0, 0), "宋体", 24, "left");
    var caption = paragraph("图1-1 系统结构图", List.of(0, 3), "宋体", 21, "center");
    var imageSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 0);
    var image = new ImageModel(imageSource, caption.source(), "图1-1 系统结构图", 100, 100,
        true, true, true);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("sha", List.of(chapter, tableReference, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of(image), List.of(), List.of(), List.of());

    var result = new ImageRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(finding -> "FIG-01".equals(finding.ruleId())).findFirst().orElseThrow();

    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void checksCaptionCenteringWithoutRequiringTheImageItselfToBeCentered() {
    assertFigurePlacement(true, true, true, Judgement.PASS, "");
    assertFigurePlacement(false, true, true, Judgement.PASS, "");
    assertFigurePlacement(null, true, true, Judgement.PASS, "");
    assertFigurePlacement(false, false, true, Judgement.FAIL, "FIGURE_NOT_CENTERED");
    assertFigurePlacement(null, false, false, Judgement.FAIL, "FIGURE_NOT_CENTERED");
    assertFigurePlacement(false, null, true, Judgement.REVIEW, "FIGURE_LAYOUT_REVIEW");
  }

  private static void assertFigurePlacement(Boolean imageCentered, Boolean captionCentered,
      boolean inline, Judgement judgement, String reasonCode) {
    var chapter = paragraph("第一章 绪论", List.of(0, 0), "黑体", 32, "left");
    var caption = paragraph("图1-1 系统结构图", List.of(0, 3), "宋体", 21, "center");
    var imageSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 0);
    var image = new ImageModel(imageSource, caption.source(), caption.runs().get(0).text(),
        100, 100, inline, imageCentered, captionCentered);
    var document = document(List.of(chapter, caption), image);

    var result = new ImageRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(f -> "FIG-02".equals(f.ruleId())).findFirst().orElseThrow();

    assertEquals(judgement, result.judgement());
    assertEquals(reasonCode, result.reasonCode());
  }

  private static ParsedDocument document(List<ParagraphModel> paragraphs, ImageModel image) {
    return new ParsedDocument("sha", paragraphs, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));
  }

  private static ParagraphModel paragraph(String text, List<Integer> path, String font, int size, String alignment) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    var run = new RunModel(text, "Times New Roman", "Times New Roman", font, null, size, null,
        false, null, source);
    return new ParagraphModel(List.of(run), null, null, null, 360, "auto", source, null, null, alignment);
  }
}
