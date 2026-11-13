package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DocumentRegionClassifierTest {
  private final DocumentRegionClassifier classifier = new DocumentRegionClassifier();

  @Test
  void classifiesATableBeforeTheFirstChapterByXmlOrder() {
    var document = document();

    assertTrue(classifier.isBeforeMainBody(document, tableSource(List.of(0, 1))));
  }

  @Test
  void classifiesATableAfterTheFirstChapterAsMainBodyContent() {
    var document = document();

    assertFalse(classifier.isBeforeMainBody(document, tableSource(List.of(0, 3))));
  }

  @Test
  void classifiesNestedContentAfterTheFirstChapterAsMainBodyContent() {
    var document = document();

    assertFalse(classifier.isBeforeMainBody(document, paragraphSource(List.of(0, 2, 0), 0, 6)));
  }

  @Test
  void ignoresGeneratedContentsChapterWhenFindingTheMainBodyStart() {
    var generatedContentsChapter = paragraph("第一章 引言", List.of(0, 1), 0, "1", true);
    var actualChapter = paragraph("第一章 引言", List.of(0, 5));
    var document = new ParsedDocument("source",
        List.of(generatedContentsChapter, actualChapter), List.of(), List.of(),
        List.of(table(List.of(0, 3))), List.of(), List.of(), List.of(), List.of(), List.of());

    assertTrue(classifier.isBeforeMainBody(document, tableSource(List.of(0, 3))));
  }

  @Test
  void recognizesAnAppendixHeadingStartingWithAnAmbiguousHanCharacter() {
    var heading = paragraph("附录A中使用的符号", List.of(0, 1), 0);
    var object = paragraph("图A1 符号说明", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(heading, object), List.of(), List.of());

    assertEquals("A", classifier.appendixLetterAt(document, object.source()));
  }

  @Test
  void doesNotClassifyAnAppendixMentionInBodyTextAsAHeading() {
    var mention = paragraph("附录A的实验结果见下文。", List.of(0, 1));
    var object = paragraph("图A1 实验结果", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(mention, object), List.of(), List.of());

    assertNull(classifier.appendixLetterAt(document, object.source()));
  }

  @Test
  void doesNotCountAnUnnumberedFigureReferenceInsideATableAsBodyText() {
    var chapter = paragraph("第一章 引言", List.of(0, 1), 0);
    var tableReference = paragraph("如图所示，系统结构如下", List.of(0, 3, 0, 0), null);
    var caption = paragraph("图1-1 系统结构", List.of(0, 5));
    var table = table(List.of(0, 3));
    var document = new ParsedDocument("source", List.of(chapter, tableReference, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of(), List.of(), List.of());

    assertFalse(classifier.hasUnnumberedFigureReference(document, caption.source(),
        Pattern.compile("图\\s*\\d+[-－]\\d+"),
        Pattern.compile("(?:如|见|参见)图(?!\\s*\\d)(?:所示|如下)?|下图所示")));
  }

  @Test
  void figureLabelsInsideAnotherTextBoxAreNotBodyCitationsWithoutChangingTableCitationSemantics() {
    var chapter = paragraph("第一章 引言", List.of(0, 0), 0);
    var groupOuter = paragraph("", List.of(0, 2, 0, 0));
    var figureCaption = textBoxParagraph("图1-1 图题", List.of(0, 2, 0, 0, 1, 0));
    var otherBox = textBoxParagraph("如图1-1、表1-1所示", List.of(0, 2, 0, 0, 2, 0));
    var document = new ParsedDocument("source", List.of(chapter, groupOuter, figureCaption, otherBox),
        List.of(), List.of());

    assertFalse(classifier.isFigureReferencedInBody(document, "1-1", figureCaption.source(),
        Pattern.compile("图\\s*(\\d+-\\d+)")));
    assertTrue(classifier.isLabelReferencedInBody(document, "1-1", null,
        Pattern.compile("表\\s*(\\d+-\\d+)")));
  }

  @Test
  void anIndependentBodyTextBoxStillCountsAsAFigureCitation() {
    var chapter = paragraph("第一章 引言", List.of(0, 0), 0);
    var groupOuter = paragraph("", List.of(0, 2));
    var figureCaption = textBoxParagraph("图1-1 图题", List.of(0, 2, 0, 0));
    var independentOuter = paragraph("", List.of(0, 4));
    var reference = textBoxParagraph("如图1-1所示", List.of(0, 4, 0, 0));
    var document = new ParsedDocument("source",
        List.of(chapter, groupOuter, figureCaption, independentOuter, reference), List.of(), List.of());

    assertTrue(classifier.isFigureReferencedInBody(document, "1-1", figureCaption.source(),
        Pattern.compile("图\\s*(\\d+-\\d+)")));
  }

  private static ParsedDocument document() {
    return new ParsedDocument("source", List.of(
        paragraph("封面信息", List.of(0, 0)),
        paragraph("第一章 引言", List.of(0, 2)),
        paragraph("正文内容", List.of(0, 4))), List.of(), List.of(),
        List.of(table(List.of(0, 1)), table(List.of(0, 3))), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    return paragraph(text, path, null);
  }

  private static ParagraphModel paragraph(String text, List<Integer> path, Integer outlineLevel) {
    SourceAddress source = paragraphSource(path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, null, null, null, null, source)),
        null, null, null, null, null, null, source, null, outlineLevel, null);
  }

  private static ParagraphModel textBoxParagraph(String text, List<Integer> path) {
    SourceAddress source = paragraphSource(path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, null, null, null, null, source)),
        null, null, null, null, null, null, source, null, null, null,
        null, null, null, false, null, null, false, false, false, false,
        null, null, null, false, true, null);
  }

  private static ParagraphModel paragraph(String text, List<Integer> path, Integer outlineLevel,
      String numberingLabel, boolean generatedTocField) {
    SourceAddress source = paragraphSource(path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, null, null, null, null, source)),
        null, null, null, null, null, null, source, null, outlineLevel, null, numberingLabel,
        null, null, generatedTocField, null, null, true);
  }

  private static TableModel table(List<Integer> path) {
    return new TableModel(tableSource(path), null, null, 1, 1, false, false, false, false, false, false);
  }

  private static SourceAddress tableSource(List<Integer> path) {
    return new SourceAddress("word/document.xml", path, 0, 0);
  }

  private static SourceAddress paragraphSource(List<Integer> path, int start, int end) {
    return new SourceAddress("word/document.xml", path, start, end);
  }
}
