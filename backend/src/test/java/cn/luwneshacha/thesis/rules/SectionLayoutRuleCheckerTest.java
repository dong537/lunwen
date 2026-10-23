package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.HeadingBreakModel;
import cn.luwneshacha.thesis.document.PageNumberModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionLayoutRuleCheckerTest {
  private static final SourceAddress ADDRESS = new SourceAddress("word/document.xml", List.of(0, 0), 0, 7);

  @Test
  void reportsExactMarginMismatchFromSectionEvidence() {
    var document = new ParsedDocument("source", List.of(chapter("第一章 引言")),
        List.of(new SectionModel(1473, 1474, 1474, 1474, ADDRESS)), List.of());

    var finding = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("PAGE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("MARGIN_MISMATCH", finding.reasonCode());
    assertEquals("1473", finding.actual());
  }

  @Test
  void keepsCheckingKnownMarginSidesWhenAnotherSideIsMissing() {
    var section = new SectionModel(null, 1473, 1474, 1474, ADDRESS);
    var document = new ParsedDocument("source", List.of(), List.of(section), List.of());

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("PAGE-01")).toList();

    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("MARGIN_UNKNOWN")
        && result.judgement() == Judgement.REVIEW));
    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("MARGIN_MISMATCH")
        && result.actual().equals("1473") && result.judgement() == Judgement.FAIL));
  }

  @Test
  void reportsMissingBreakOnlyForLaterChapters() {
    var chapters = List.of(new HeadingBreakModel(ADDRESS, "第一章 引言", false),
        new HeadingBreakModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 7), "第二章 设计", false));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), chapters);

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("CHAPTER_PAGE_BREAK_REVIEW")).toList();

    assertEquals(1, findings.size());
    assertEquals(Judgement.REVIEW, findings.get(0).judgement());
    assertTrue(findings.get(0).source().elementPath().equals(List.of(0, 1)));
  }

  @Test
  void checksEachEnabledFooterVariantForAPageField() {
    var chapter = chapter("第一章 引言");
    var bodySection = section(chapter.source(), true, true);
    var defaultFooter = page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(0));
    var document = new ParsedDocument("source", List.of(chapter), List.of(bodySection), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(defaultFooter));

    var missing = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY"))
        .toList();

    assertEquals(2, missing.size());
    assertTrue(missing.stream().allMatch(result -> result.judgement() == Judgement.FAIL), missing.toString());
    assertTrue(missing.stream().anyMatch(result -> result.expected().equals("首页页脚中的 PAGE 域")), missing.toString());
    assertTrue(missing.stream().anyMatch(result -> result.expected().equals("偶数页页脚中的 PAGE 域")), missing.toString());
  }

  @Test
  void checksFooterStyleForEveryEnabledVariant() {
    var chapter = chapter("第一章 引言");
    var bodySection = section(chapter.source(), true, true);
    var pages = List.of(
        page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
            true, "Times New Roman", 21, List.of(0)),
        page("word/footer-first.xml", PageNumberModel.FooterVariant.FIRST,
            true, "Arial", 21, List.of(0)),
        page("word/footer-even.xml", PageNumberModel.FooterVariant.EVEN,
            true, "Times New Roman", 22, List.of(0)));
    var document = new ParsedDocument("source", List.of(chapter), List.of(bodySection), List.of(),
        List.of(), List.of(), List.of(), List.of(), pages);

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("PAGE-05") && result.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("PAGE_NUMBER_FONT_MISMATCH")),
        findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("PAGE_NUMBER_SIZE_MISMATCH")),
        findings.toString());
  }

  @Test
  void ignoresFormattingInDisabledFooterVariants() {
    var chapter = chapter("第一章 引言");
    var bodySection = section(chapter.source(), false, false);
    var pages = List.of(
        page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
            true, "Times New Roman", 21, List.of(0)),
        page("word/footer-first.xml", PageNumberModel.FooterVariant.FIRST,
            true, "Arial", 22, List.of(0)),
        page("word/footer-even.xml", PageNumberModel.FooterVariant.EVEN,
            false, "Arial", 22, List.of(0)));
    var document = new ParsedDocument("source", List.of(chapter), List.of(bodySection), List.of(),
        List.of(), List.of(), List.of(), List.of(), pages);

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("PAGE-05") && result.judgement() == Judgement.FAIL)
        .toList();

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void reportsPageNumberInFrontMatterWhenItsFooterIsReusedByTheBody() {
    var frontSectionSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 0);
    var chapter = chapter("第一章 引言", new SourceAddress("word/document.xml", List.of(0, 1), 0, 7));
    var sections = List.of(
        section(frontSectionSource, false, false),
        section(chapter.source(), false, false));
    var sharedDefaultFooter = page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(0, 1));
    var document = new ParsedDocument("source", List.of(chapter), sections, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(sharedDefaultFooter));

    var finding = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_IN_FRONT_MATTER"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals(frontSectionSource, finding.source());
  }

  @Test
  void tableContentsTitleDoesNotMakeTheFirstAutomaticBodyChapterLookLikeATocEntry() {
    var tableContentsTitle = paragraphAt("\u76ee\u5f55", source(0, 0, 0, 0));
    var firstBodyChapter = automaticNumberedChapter("\u5f15\u8a00  1", source(0, 2));
    var secondBodyChapter = chapter("\u7b2c2\u7ae0 \u6b63\u6587", source(0, 4));
    var sections = List.of(
        section(source(0, 1), false, false),
        section(source(0, 3), false, false),
        section(source(0, 5), false, false));
    var table = new TableModel(source(0, 0), null, null,
        1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source",
        List.of(tableContentsTitle, firstBodyChapter, secondBodyChapter), sections, List.of(),
        List.of(table), List.of(), List.of());

    var missingPageNumbers = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY"))
        .toList();

    assertEquals(2, missingPageNumbers.size());
  }

  private SectionModel section(SourceAddress source, boolean firstEnabled, boolean evenEnabled) {
    return new SectionModel(1474, 1474, 1474, 1474, 0, source, "decimal", false,
        null, false, false, false, firstEnabled, evenEnabled);
  }

  private PageNumberModel page(String partName, PageNumberModel.FooterVariant variant, boolean centered,
      String font, int size, List<Integer> sectionIndexes) {
    return new PageNumberModel(new SourceAddress(partName, List.of(0), 0, 0), true,
        centered, font, size, sectionIndexes,
        List.of(new PageNumberModel.RunFormatting(font, size)), variant);
  }

  private ParagraphModel chapter(String text) {
    return chapter(text, ADDRESS);
  }

  private ParagraphModel chapter(String text, SourceAddress source) {
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "黑体", null,
        32, false, source)), null, null, null, null, null, null, source, null, null, "left");
  }

  private ParagraphModel automaticNumberedChapter(String text, SourceAddress source) {
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "黑体", null,
        32, false, source)), null, null, null, null, null, null, source, null, 0, "left", "1");
  }

  private static ParagraphModel paragraphAt(String text, SourceAddress source) {
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "\u5b8b\u4f53", null,
        24, false, source)), null, null, null, null, null, null, source, null, null, "left");
  }

  private static SourceAddress source(int... path) {
    return new SourceAddress("word/document.xml", java.util.Arrays.stream(path).boxed().toList(), 0, 0);
  }
}
