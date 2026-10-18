package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.HeaderModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderImageCoverScopeTest {
  private static final String REQUIRED = "山东石油化工学院本科毕业设计（论文）";

  @Test
  void exemptsOnlyInitialImageOnlySectionImmediatelyBeforeChineseAbstract() {
    var result = check(document(List.of(section(1, false), section(3, true)),
        List.of(image(0, null)), separateHeaders(), "", "", "摘    要", "摘要正文"));

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void recognizesInitialImageOnlySectionImmediatelyBeforeEnglishAbstract() {
    var result = check(document(List.of(section(1, false), section(3, true)),
        List.of(image(0, null)), separateHeaders(), "", "", "ABSTRACT", "Abstract text"));

    assertTrue(result.isEmpty(), result.toString());
  }

  @Test
  void stillChecksHeaderInheritedByAbstractAfterImageCover() {
    var result = check(document(List.of(section(1, false), section(3, true)),
        List.of(image(0, null)), List.of(header("shared", "", List.of(0, 1))),
        "", "", "摘    要", "摘要正文"));

    assertEquals(1, result.size());
    assertEquals("HEADER_TEXT_MISMATCH", result.get(0).reasonCode());
    assertEquals("word/header-shared.xml", result.get(0).source().partName());
  }

  @Test
  void stillRequiresHeaderForAbstractAfterImageCover() {
    var result = check(document(List.of(section(1, false), section(3, true)),
        List.of(image(0, null)), List.of(header("cover", "", List.of(0))),
        "", "", "摘    要", "摘要正文"));

    assertEquals(1, result.size());
    assertEquals("HEADER_MISSING", result.get(0).reasonCode());
    assertEquals(source(3, 0), result.get(0).source());
  }

  @Test
  void doesNotExemptImageSectionBeforeBodyOrContents() {
    for (String nextTitle : List.of("第一章  绪论", "目    录", "普通正文")) {
      var result = check(document(List.of(section(1, false), section(3, true)),
          List.of(image(0, null)), separateHeaders(), "", "", nextTitle, "正文"));

      assertEquals(1, result.size(), nextTitle + ": " + result);
      assertEquals("word/header-cover.xml", result.get(0).source().partName());
    }
  }

  @Test
  void doesNotExemptImageAndAbstractWithoutSeparateSectionEvidence() {
    var result = check(document(List.of(section(3, true)), List.of(image(0, null)),
        List.of(header("shared", "", List.of(0))), "", "", "摘    要", "摘要正文"));

    assertEquals(1, result.size());
    assertEquals("HEADER_TEXT_MISMATCH", result.get(0).reasonCode());
  }

  @Test
  void doesNotExemptImageSectionContainingOtherText() {
    var result = check(document(List.of(section(1, false), section(3, true)),
        List.of(image(0, null)), separateHeaders(), "插图说明", "", "摘    要", "摘要正文"));

    assertEquals(1, result.size());
    assertEquals("word/header-cover.xml", result.get(0).source().partName());
  }

  @Test
  void doesNotExemptCaptionedImageOrMultipleImages() {
    for (List<ImageModel> images : List.of(List.of(image(0, "图1-1 示例")),
        List.of(image(0, null), image(1, null)), List.<ImageModel>of())) {
      var result = check(document(List.of(section(1, false), section(3, true)), images,
          separateHeaders(), "", "", "摘    要", "摘要正文"));

      assertEquals(1, result.size(), result.toString());
      assertEquals("word/header-cover.xml", result.get(0).source().partName());
    }
  }

  @Test
  void doesNotExemptImageSectionSeparatedFromAbstractByAnotherSection() {
    var result = check(document(List.of(section(1, false), section(3, false), section(5, true)),
        List.of(image(0, null)), List.of(header("cover", "", List.of(0)),
            header("later", REQUIRED, List.of(1, 2))),
        "", "", "", "", "摘    要", "摘要正文"));

    assertEquals(1, result.size());
    assertEquals("word/header-cover.xml", result.get(0).source().partName());
  }

  @Test
  void doesNotExemptImageSectionThatOccursAfterAbstract() {
    var result = check(document(List.of(section(1, false), section(3, true)),
        List.of(image(2, null)), List.of(header("abstract", REQUIRED, List.of(0)),
            header("later", "", List.of(1))), "摘    要", "摘要正文", "", ""));

    assertEquals(1, result.size());
    assertEquals("word/header-later.xml", result.get(0).source().partName());
  }

  private List<CheckResult> check(ParsedDocument document) {
    return new HeaderRuleModule().check(document, RuleSet.schoolV1());
  }

  private List<HeaderModel> separateHeaders() {
    return List.of(header("cover", "", List.of(0)), header("abstract", REQUIRED, List.of(1)));
  }

  private ParsedDocument document(List<SectionModel> sections, List<ImageModel> images,
      List<HeaderModel> headers, String... texts) {
    var paragraphs = new ArrayList<ParagraphModel>();
    for (int index = 0; index < texts.length; index++) {
      SourceAddress source = source(index, texts[index].length());
      paragraphs.add(new ParagraphModel(List.of(new RunModel(texts[index], "Times New Roman",
          "宋体", null, 24, false, source)), null, null, null, null, null, null,
          source, null, null, "left", null));
    }
    return new ParsedDocument("sha", paragraphs, sections, List.of(), List.of(), List.of(),
        List.of(), images, List.of(), List.of(), headers);
  }

  private HeaderModel header(String name, String text, List<Integer> sections) {
    SourceAddress source = new SourceAddress("word/header-" + name + ".xml", List.of(0), 0, text.length());
    return new HeaderModel(source, text, true,
        List.of(new HeaderModel.RunFormatting("黑体", "黑体", 21, source)),
        Map.of(HeaderModel.Type.DEFAULT, sections));
  }

  private SectionModel section(int index, boolean terminal) {
    return new SectionModel(1474, 1474, 1474, 1474, source(index, 0), null, null, terminal);
  }

  private ImageModel image(int index, String caption) {
    return new ImageModel(source(index, 0), caption == null ? null : source(index + 1, caption.length()),
        caption, 7560000, 10692000, true, true, null);
  }

  private SourceAddress source(int index, int length) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, length);
  }
}
