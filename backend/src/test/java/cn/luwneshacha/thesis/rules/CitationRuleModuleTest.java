package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationRuleModuleTest {
  @Test
  void matchesBodyCitationToReferenceAndDoesNotTreatContentsEntryAsCitation() {
    var tocTitle = paragraph("目录", 0);
    var tocChapter = paragraph("第一章 引言……1", 1);
    var chapter = numberedChapter("引言", "第1章", 2);
    var citation = paragraph("本文依据已有研究[1]。", 3);
    var reference = new ReferenceEntryModel(source(4), "1", "[1] 示例文献[J]", "J", false);
    var document = new ParsedDocument("source", List.of(tocTitle, tocChapter, chapter, citation),
        List.of(), List.of(), List.of(), List.of(), List.of(reference), List.of(), List.of(), List.of());

    var results = new CitationRuleModule(new HeadingRuleChecker(new ParagraphFormatChecker()))
        .check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.reasonCode() != null
        && (result.reasonCode().equals("REFERENCE_NOT_CITED")
            || result.reasonCode().equals("CITED_REFERENCE_MISSING"))), results.toString());
  }

  @Test
  void reportsCitationPositionAndUncitedReferenceFromBodyEvidence() {
    var chapter = numberedChapter("引言", "第1章", 0);
    var citation = paragraph("本文引用[1]后还有正文。", 1);
    var reference = new ReferenceEntryModel(source(2), "1", "[1] 示例文献[J]", "J", false);
    var document = new ParsedDocument("source", List.of(chapter, citation), List.of(), List.of(),
        List.of(), List.of(), List.of(reference), List.of(), List.of(), List.of());

    var results = new CitationRuleModule(new HeadingRuleChecker(new ParagraphFormatChecker()))
        .check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> "CITATION_POSITION_MISMATCH".equals(result.reasonCode())),
        results.toString());
    assertTrue(results.stream().anyMatch(result -> "CITATION_FORMAT_MISMATCH".equals(result.reasonCode())),
        results.toString());
  }

  @Test
  void doesNotTreatArrayIndexInCodeAsBodyCitation() {
    var chapter = numberedChapter("引言", "第1章", 0);
    var code = paragraph("int result = values[1];", 1);
    var reference = new ReferenceEntryModel(source(2), "1", "[1] 示例文献[J]", "J", false);
    var document = new ParsedDocument("source", List.of(chapter, code), List.of(), List.of(),
        List.of(), List.of(), List.of(reference), List.of(), List.of(), List.of());

    var results = new CitationRuleModule(new HeadingRuleChecker(new ParagraphFormatChecker()))
        .check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> "CITATION_FORMAT_MISMATCH".equals(result.reasonCode())),
        results.toString());
  }

  @Test
  void tableTextNamedReferencesDoesNotStopBodyCitationCollection() {
    var chapter = numberedChapter("引言", "第1章", 0);
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, 4);
    var tableParagraph = new ParagraphModel(List.of(new RunModel("参考文献", null, null, null,
        24, false, tableSource)), null, null, null, null, null, null, tableSource, null, null, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var citation = paragraph("本文依据已有研究[1]。", 2);
    var reference = new ReferenceEntryModel(source(3), "1", "[1] 示例文献[J]", "J", false);
    var document = new ParsedDocument("source", List.of(chapter, tableParagraph, citation),
        List.of(), List.of(), List.of(table), List.of(), List.of(reference), List.of(), List.of(), List.of());

    var results = new CitationRuleModule(new HeadingRuleChecker(new ParagraphFormatChecker()))
        .check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> "REFERENCE_NOT_CITED".equals(result.reasonCode())),
        results.toString());
  }

  private static ParagraphModel numberedChapter(String title, String label, int index) {
    var source = source(index);
    return new ParagraphModel(List.of(new RunModel(title, "Times New Roman", "Times New Roman", "黑体",
        null, 32, false, null, source)), null, null, null, null, null, null, source, null, 0, "left", label,
        null, null, false, null, null, true);
  }

  private static ParagraphModel paragraph(String text, int index) {
    var source = source(index);
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, null, source)), null, null, null, null, null, null, source, null, null, null);
  }

  private static SourceAddress source(int index) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, 100);
  }
}
