package cn.luwneshacha.thesis.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderedLayoutEvidenceTest {
  @Test
  void confirmsAnAlignedAutomaticallyWrappedKeywordParagraph() {
    var evidence = evidence(List.of(
        line(1, 100, "Keywords: " , 30, "Alpha; Beta;", 80),
        line(1, 118, "Gamma", 80)));

    assertEquals(RenderedLayoutEvidence.KeywordStatus.ALIGNED,
        evidence.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
  }

  @Test
  void rejectsAContinuationThatStartsAtTheLabelInsteadOfTheFirstKeyword() {
    var evidence = evidence(List.of(
        line(1, 100, "Keywords: " , 30, "Alpha; Beta;", 80),
        line(1, 118, "Gamma", 30)));

    assertEquals(RenderedLayoutEvidence.KeywordStatus.MISALIGNED,
        evidence.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
  }

  @Test
  void cannotConfirmKeywordAlignmentFromNonFinitePdfCoordinates() {
    var invalidFirstKeyword = evidence(List.of(
        line(1, 100, "Keywords: " , 30, "Alpha; Beta;", Double.NaN),
        line(1, 118, "Gamma", 80)));
    var invalidContinuation = evidence(List.of(
        line(1, 100, "Keywords: " , 30, "Alpha; Beta;", 80),
        line(1, 118, "Gamma", Double.NaN)));

    assertEquals(RenderedLayoutEvidence.KeywordStatus.UNKNOWN,
        invalidFirstKeyword.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
    assertEquals(RenderedLayoutEvidence.KeywordStatus.UNKNOWN,
        invalidContinuation.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
  }

  @Test
  void provesNoAlignmentRequirementWhenKeywordsFitOnOneLine() {
    var evidence = evidence(List.of(line(1, 100, "Keywords: Alpha; Beta; Gamma", 30)));

    assertEquals(RenderedLayoutEvidence.KeywordStatus.NO_WRAP,
        evidence.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
  }

  @Test
  void leavesAmbiguousOrIncompleteMappingUnknown() {
    var first = line(1, 100, "Keywords: Alpha; Beta; Gamma", 30);
    var duplicate = line(2, 100, "Keywords: Alpha; Beta; Gamma", 30);
    assertEquals(RenderedLayoutEvidence.KeywordStatus.UNKNOWN,
        evidence(List.of(first, duplicate)).englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
    assertEquals(RenderedLayoutEvidence.KeywordStatus.UNKNOWN,
        evidence(List.of(line(1, 100, "Keywords: Alpha", 30)))
            .englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
    assertEquals(RenderedLayoutEvidence.KeywordStatus.UNKNOWN,
        evidence(List.of(line(1, 100, "Keywords: Alpha; Beta;", 30),
            line(2, 20, "Gamma", 80)))
            .englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status());
  }

  @Test
  void establishesChapterPaginationOnlyWhenBothAdjacentParagraphsMapUniquely() {
    var prior = line(1, 700, "上一章末段", 40);
    var newPage = line(2, 70, "第二章 设计", 40);
    var samePage = line(1, 750, "第二章 设计", 40);

    assertEquals(RenderedLayoutEvidence.ChapterStatus.NEW_PAGE,
        evidence(List.of(prior, newPage)).chapterStart("第二章 设计", "上一章末段").status());
    assertEquals(RenderedLayoutEvidence.ChapterStatus.SAME_PAGE,
        evidence(List.of(prior, samePage)).chapterStart("第二章 设计", "上一章末段").status());
    assertEquals(RenderedLayoutEvidence.ChapterStatus.UNKNOWN,
        evidence(List.of(prior, newPage, line(3, 70, "第二章 设计", 40)))
            .chapterStart("第二章 设计", "上一章末段").status());
  }

  @Test
  void recordsOnlyUniqueStandaloneCenteredPageBottomNumbersAsCandidates() {
    var source = evidence(List.of(
        line(1, 100, "7", 306),
        line(1, 800, "1", 306),
        line(2, 800, "2", 306),
        line(2, 812, "3", 306),
        line(3, 800, "4.", 306),
        line(4, 800, "5", 50)),
        List.of(new RenderedLayoutEvidence.PageBox(1, 612, 842),
            new RenderedLayoutEvidence.PageBox(2, 612, 842),
            new RenderedLayoutEvidence.PageBox(3, 612, 842),
            new RenderedLayoutEvidence.PageBox(4, 612, 842)));

    assertEquals(List.of(new RenderedLayoutEvidence.PageBottomNumberCandidate(1, "1", 306, 800)),
        source.pageBottomNumberCandidates());
    assertEquals(List.of(), evidence(List.of(line(1, 800, "1", 306)))
        .pageBottomNumberCandidates());
  }

  private static RenderedLayoutEvidence evidence(List<RenderedLayoutEvidence.TextLine> lines) {
    return evidence(lines, List.of());
  }

  private static RenderedLayoutEvidence evidence(List<RenderedLayoutEvidence.TextLine> lines,
      List<RenderedLayoutEvidence.PageBox> pages) {
    return new RenderedLayoutEvidence(
        new RenderedLayoutEvidence.Provenance("available", "source", "pdf",
            "LibreOffice", "24.2", "2026-11-18T00:00:00Z", null,
            pages.isEmpty() ? lines.size() : pages.size()),
        lines, pages);
  }

  private static RenderedLayoutEvidence.TextLine line(int page, double y, String text, double x) {
    return line(page, y, text, x, "", 0);
  }

  private static RenderedLayoutEvidence.TextLine line(int page, double y, String left, double leftX,
      String right, double rightX) {
    var glyphs = new ArrayList<RenderedLayoutEvidence.Glyph>();
    for (int i = 0; i < left.length(); i++)
      glyphs.add(new RenderedLayoutEvidence.Glyph(String.valueOf(left.charAt(i)), leftX + i * 3));
    for (int i = 0; i < right.length(); i++)
      glyphs.add(new RenderedLayoutEvidence.Glyph(String.valueOf(right.charAt(i)), rightX + i * 3));
    return new RenderedLayoutEvidence.TextLine(page, y, glyphs);
  }
}
