package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.HeadingBreakModel;
import cn.luwneshacha.thesis.document.FooterPageEvidence;
import cn.luwneshacha.thesis.document.PageNumberModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.rendering.RenderedLayoutEvidence;
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
  void recordsEachSectionMarginSideAsAnIndependentCandidate() {
    var complete = new SectionModel(1474, 1474, 1474, 1474, ADDRESS);
    var partial = new SectionModel(1473, null, 1474, 1474, 0,
        source(0, 1), null, false, null, false, false,
        false, false, true, false, false, false);
    var document = new ParsedDocument("source", List.of(), List.of(complete, partial), List.of());

    var run = new SectionLayoutRuleChecker().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().getFirst();

    assertEquals(new RuleRun("PAGE-01", "SectionLayoutRuleChecker", 8, 7, 1, null), run);
  }

  @Test
  void missingSectionEvidenceStaysUnknownInsteadOfClaimingNoMarginsApply() {
    var document = new ParsedDocument("source", List.of(), List.of(), List.of());

    var run = new SectionLayoutRuleChecker().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().getFirst();

    assertEquals(new RuleRun("PAGE-01", "SectionLayoutRuleChecker", 1, 0, 1, null), run);
  }

  @Test
  void laterChapterBreakEvidenceDistinguishesExplicitBreakFromReview() {
    var chapters = List.of(new HeadingBreakModel(source(0, 0), "第一章 引言", false),
        new HeadingBreakModel(source(0, 1), "第二章 设计", true),
        new HeadingBreakModel(source(0, 2), "第三章 实现", false));
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), chapters);

    assertEquals(new RuleRun("PAGE-03", "SectionLayoutRuleChecker", 2, 1, 1, null),
        ruleRun(document, "PAGE-03"));
  }

  @Test
  void confirmsOrRejectsChapterNewPageOnlyFromUniqueRenderedNeighbors() {
    var previous = paragraphAt("上一章末段", source(0, 1));
    var next = chapter("第二章 设计", source(0, 2));
    var chapters = List.of(new HeadingBreakModel(next.source(), "第二章 设计", false));
    var document = withCompleteObjectInventories(new ParsedDocument("source",
        List.of(previous, next), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), chapters));
    var provenance = new RenderedLayoutEvidence.Provenance("available", "source", "pdf",
        "LibreOffice", "24.2", "2026-11-18T00:00:00Z", null, 2);
    var pageOne = new RenderedLayoutEvidence.TextLine(1, 700,
        List.of(new RenderedLayoutEvidence.Glyph("上一章末段", 40)));
    var pageTwo = new RenderedLayoutEvidence.TextLine(2, 70,
        List.of(new RenderedLayoutEvidence.Glyph("第二章 设计", 40)));
    var samePage = new RenderedLayoutEvidence.TextLine(1, 750,
        List.of(new RenderedLayoutEvidence.Glyph("第二章 设计", 40)));

    var confirmed = new SectionLayoutRuleChecker(false,
        new RenderedLayoutEvidence(provenance, List.of(pageOne, pageTwo)))
        .evaluate(document, RuleSet.schoolV1());
    var rejected = new SectionLayoutRuleChecker(false,
        new RenderedLayoutEvidence(provenance, List.of(pageOne, samePage)))
        .evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("PAGE-03", "SectionLayoutRuleChecker", 1, 1, 0, null),
        confirmed.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-03"))
            .findFirst().orElseThrow());
    assertTrue(confirmed.results().stream().noneMatch(result -> result.ruleId().equals("PAGE-03")));
    assertTrue(rejected.results().stream().anyMatch(result -> result.ruleId().equals("PAGE-03")
        && result.judgement() == Judgement.FAIL));
  }

  @Test
  void tableBetweenParagraphAndChapterPreventsRenderedNewPageConfirmation() {
    var previous = paragraphAt("上一章末段", source(0, 1));
    var tableText = paragraphAt("表内最后一行", source(0, 2, 0, 0));
    var next = chapter("第二章 设计", source(0, 3));
    var table = new TableModel(source(0, 2), null, null,
        1, 1, false, false, false, false, false, false);
    var chapters = List.of(new HeadingBreakModel(next.source(), "第二章 设计", false));
    var document = withCompleteObjectInventories(new ParsedDocument("source",
        List.of(previous, tableText, next), List.of(), List.of(), List.of(table), List.of(),
        List.of(), List.of(), List.of(), chapters));
    var provenance = new RenderedLayoutEvidence.Provenance("available", "source", "pdf",
        "LibreOffice", "24.2", "2026-11-18T00:00:00Z", null, 2);
    var layout = new RenderedLayoutEvidence(provenance, List.of(
        new RenderedLayoutEvidence.TextLine(1, 700,
            List.of(new RenderedLayoutEvidence.Glyph("上一章末段", 40))),
        new RenderedLayoutEvidence.TextLine(2, 400,
            List.of(new RenderedLayoutEvidence.Glyph("表内最后一行", 40))),
        new RenderedLayoutEvidence.TextLine(2, 500,
            List.of(new RenderedLayoutEvidence.Glyph("第二章 设计", 40)))));

    var evaluation = new SectionLayoutRuleChecker(false, layout).evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().anyMatch(result -> result.reasonCode()
        .equals("CHAPTER_PAGE_BREAK_REVIEW") && result.judgement() == Judgement.REVIEW));
    assertEquals(new RuleRun("PAGE-03", "SectionLayoutRuleChecker", 1, 0, 1, null),
        evaluation.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-03"))
            .findFirst().orElseThrow());
  }

  @Test
  void incompleteObjectInventoryPreventsRenderedChapterBreakConfirmation() {
    var previous = paragraphAt("上一章末段", source(0, 1));
    var next = chapter("第二章 设计", source(0, 2));
    var chapters = List.of(new HeadingBreakModel(next.source(), "第二章 设计", false));
    var document = new ParsedDocument("source", List.of(previous, next), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), chapters);
    var provenance = new RenderedLayoutEvidence.Provenance("available", "source", "pdf",
        "LibreOffice", "24.2", "2026-11-18T00:00:00Z", null, 2);
    var layout = new RenderedLayoutEvidence(provenance, List.of(
        new RenderedLayoutEvidence.TextLine(1, 700,
            List.of(new RenderedLayoutEvidence.Glyph("上一章末段", 40))),
        new RenderedLayoutEvidence.TextLine(2, 70,
            List.of(new RenderedLayoutEvidence.Glyph("第二章 设计", 40)))));

    var evaluation = new SectionLayoutRuleChecker(false, layout).evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().anyMatch(result -> result.reasonCode()
        .equals("CHAPTER_PAGE_BREAK_REVIEW") && result.judgement() == Judgement.REVIEW));
    assertEquals(new RuleRun("PAGE-03", "SectionLayoutRuleChecker", 1, 0, 1, null),
        evaluation.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-03"))
            .findFirst().orElseThrow());
  }

  @Test
  void incompleteImageInventoryPreventsRenderedNewPageConfirmation() {
    var previous = paragraphAt("上一章末段", source(0, 1));
    var next = chapter("第二章 设计", source(0, 2));
    var chapters = List.of(new HeadingBreakModel(next.source(), "第二章 设计", false));
    var complete = withCompleteObjectInventories(new ParsedDocument("source",
        List.of(previous, next), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), chapters));
    var document = new ParsedDocument(complete.sourceSha256(), complete.paragraphs(),
        complete.sections(), complete.diagnostics(), complete.tables(), complete.equations(),
        complete.references(), complete.images(), complete.pageNumbers(), complete.chapterBreaks(),
        complete.headers(), complete.automaticNumbering(), true,
        complete.possibleEquationObjectSources(), true, complete.footerPageEvidence(), false);
    var provenance = new RenderedLayoutEvidence.Provenance("available", "source", "pdf",
        "LibreOffice", "24.2", "2026-11-18T00:00:00Z", null, 2);
    var layout = new RenderedLayoutEvidence(provenance, List.of(
        new RenderedLayoutEvidence.TextLine(1, 700,
            List.of(new RenderedLayoutEvidence.Glyph("上一章末段", 40))),
        new RenderedLayoutEvidence.TextLine(2, 70,
            List.of(new RenderedLayoutEvidence.Glyph("第二章 设计", 40)))));

    var evaluation = new SectionLayoutRuleChecker(false, layout).evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().anyMatch(result -> result.reasonCode()
        .equals("CHAPTER_PAGE_BREAK_REVIEW") && result.judgement() == Judgement.REVIEW));
    assertEquals(new RuleRun("PAGE-03", "SectionLayoutRuleChecker", 1, 0, 1, null),
        evaluation.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-03"))
            .findFirst().orElseThrow());
  }

  @Test
  void manualContentsChapterIsNotAChapterBreakCandidate() {
    var contentsTitle = paragraphAt("目 录", source(0, 0));
    var contentsEntry = paragraphAt("第二章 设计\t12", source(0, 1));
    var bodyChapter = chapter("第二章 设计", source(0, 2));
    var chapters = List.of(
        new HeadingBreakModel(contentsEntry.source(), "第二章 设计\t12", false),
        new HeadingBreakModel(bodyChapter.source(), "第二章 设计", false));
    var document = new ParsedDocument("source",
        List.of(contentsTitle, contentsEntry, bodyChapter), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), chapters);

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("PAGE-03")).toList();

    assertEquals(List.of(bodyChapter.source()), findings.stream().map(CheckResult::source).toList());
    assertEquals(new RuleRun("PAGE-03", "SectionLayoutRuleChecker", 1, 0, 1, null),
        ruleRun(document, "PAGE-03"));
  }

  @Test
  void pageNumberEvidenceCountsExplicitSectionPropertiesButLeavesUnverifiedFooterUnknown() {
    var chapter = chapter("第一章 引言");
    var bodySection = new SectionModel(1474, 1474, 1474, 1474, 0,
        chapter.source(), "decimal", false, 1, false, false, false);
    var document = new ParsedDocument("source", List.of(chapter),
        List.of(bodySection), List.of());

    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 3, 2, 1, null),
        ruleRun(document, "PAGE-04"));
    assertTrue(new SectionLayoutRuleChecker().evaluate(document, RuleSet.schoolV1())
        .ruleRuns().stream().noneMatch(run -> "PAGE-05".equals(run.ruleId())));
  }

  @Test
  void uncertainActiveFooterDoesNotProduceHardPageNumberFindings() {
    var chapter = chapter("第一章 引言");
    var section = new SectionModel(1474, 1474, 1474, 1474, 0,
        chapter.source(), "upperRoman", false, 1, false, false, false);
    var document = new ParsedDocument("source", List.of(chapter), List.of(section), List.of());

    var evaluation = new SectionLayoutRuleChecker(true).evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().noneMatch(result -> result.ruleId().equals("PAGE-04")
        || result.ruleId().equals("PAGE-05")), evaluation.results().toString());
    assertTrue(evaluation.results().stream().anyMatch(result -> result.ruleId().equals("PAGE-01")));
    var page04 = evaluation.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-04"))
        .findFirst().orElseThrow();
    assertEquals(0, page04.checkedCount());
    assertEquals(page04.candidateCount(), page04.unknownCount());
  }

  @Test
  void missingPageFormatAndStartDoNotBecomeChecked() {
    var chapter = chapter("第一章 引言");
    var section = new SectionModel(1474, 1474, 1474, 1474, chapter.source());
    var footer = page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(0));
    var document = new ParsedDocument("source", List.of(chapter), List.of(section), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(footer));

    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 3, 1, 2, null),
        ruleRun(document, "PAGE-04"));
    assertEquals(new RuleRun("PAGE-05", "SectionLayoutRuleChecker", 3, 3, 0, null),
        ruleRun(document, "PAGE-05"));
  }

  @Test
  void laterSectionWithPageNumberSettingsAndNoStartContinuesPreviousNumbering() {
    var chapter = chapter("第一章 引言", source(0, 0));
    var sections = List.of(
        new SectionModel(1474, 1474, 1474, 1474, source(0, 1), "decimal", 1),
        new SectionModel(1474, 1474, 1474, 1474, source(0, 2), "decimal", null));
    var document = new ParsedDocument("source", List.of(chapter), sections, List.of());

    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 6, 4, 2, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void firstBodySectionWithoutExplicitStartRemainsUnknown() {
    var chapter = chapter("第一章 引言", source(0, 0));
    var section = new SectionModel(1474, 1474, 1474, 1474,
        source(0, 1), "decimal", null);
    var document = new ParsedDocument("source", List.of(chapter), List.of(section), List.of());

    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 3, 1, 2, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void laterSectionWithoutPageNumberSettingsKeepsFormatAndStartUnknown() {
    var chapter = chapter("第一章 引言", source(0, 0));
    var sections = List.of(
        new SectionModel(1474, 1474, 1474, 1474, source(0, 1), "decimal", 1),
        new SectionModel(1474, 1474, 1474, 1474, source(0, 2)));
    var document = new ParsedDocument("source", List.of(chapter), sections, List.of());

    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 6, 2, 4, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void visibleFooterNumbersWithoutPdfToPageFieldBindingKeepXmlContinuityUnknown() {
    var chapter = chapter("第一章 引言", source(0, 0));
    var sections = List.of(
        new SectionModel(1474, 1474, 1474, 1474, source(0, 1), "decimal", 1),
        new SectionModel(1474, 1474, 1474, 1474, source(0, 2), "decimal", 3));
    var footer = page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(0, 1));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        sections, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(footer)),
        List.of(new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-default.xml",
                FooterPageEvidence.Presence.PRESENT),
            new FooterPageEvidence(1, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-default.xml",
                FooterPageEvidence.Presence.PRESENT)));
    var layout = visiblePageNumbers("source", "2");

    var confirmed = new SectionLayoutRuleChecker(false, layout).evaluate(document, RuleSet.schoolV1());
    var wrongSource = new SectionLayoutRuleChecker(false, visiblePageNumbers("other", "2"))
        .evaluate(document, RuleSet.schoolV1());
    var unreadableFooter = new SectionLayoutRuleChecker(true, layout)
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(confirmed.results().stream().anyMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED")), confirmed.results().toString());
    assertTrue(confirmed.results().stream().anyMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED")
        && result.actual().contains("PDF 第 1 页页底数字候选 1")
        && result.actual().contains("未绑定到分节或 PAGE 域")), confirmed.results().toString());
    assertTrue(wrongSource.results().stream().anyMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED")
        && !result.actual().contains("PDF 第")));
    assertTrue(confirmed.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-04"))
        .findFirst().orElseThrow().unknownCount() > 0);
    assertTrue(unreadableFooter.results().stream().noneMatch(result -> result.ruleId().equals("PAGE-04")));
    assertEquals(unreadableFooter.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-04"))
        .findFirst().orElseThrow().candidateCount(), unreadableFooter.ruleRuns().stream()
            .filter(run -> run.ruleId().equals("PAGE-04")).findFirst().orElseThrow().unknownCount());
  }

  @Test
  void renderedNumbersWithoutAProvenPageFieldDoNotResolveXmlContinuity() {
    var chapter = chapter("第一章 引言", source(0, 0));
    var sections = List.of(
        new SectionModel(1474, 1474, 1474, 1474, source(0, 1), "decimal", 1),
        new SectionModel(1474, 1474, 1474, 1474, source(0, 2), "decimal", 3));
    var document = new ParsedDocument("source", List.of(chapter), sections, List.of());

    var evaluation = new SectionLayoutRuleChecker(false, visiblePageNumbers("source", "2"))
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().anyMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED")));
  }

  @Test
  void omittedPageNumberTypeGetsAnUnboundVisualDiagnosticWithoutResolvingUnknown() {
    var chapter = chapter("第一章 引言", source(0, 0));
    var sections = List.of(
        new SectionModel(1474, 1474, 1474, 1474, source(0, 1), "decimal", 1),
        new SectionModel(1474, 1474, 1474, 1474, source(0, 2)));
    var footer = page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(0, 1));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        sections, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(footer)),
        List.of(new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-default.xml",
                FooterPageEvidence.Presence.PRESENT),
            new FooterPageEvidence(1, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.INHERITED, "word/footer-default.xml",
                FooterPageEvidence.Presence.PRESENT)));

    var evaluated = new SectionLayoutRuleChecker(false, visiblePageNumbers("source", "2"))
        .evaluate(document, RuleSet.schoolV1());
    var mismatched = new SectionLayoutRuleChecker(false, visiblePageNumbers("different", "2"))
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluated.results().stream().anyMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED")
        && result.judgement() == Judgement.REVIEW
        && result.actual().contains("PDF 第 1 页页底数字候选 1")
        && result.actual().contains("未绑定到分节或 PAGE 域")));
    assertTrue(mismatched.results().stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED")));
    assertTrue(evaluated.ruleRuns().stream().filter(run -> run.ruleId().equals("PAGE-04"))
        .findFirst().orElseThrow().unknownCount() > 0);
  }

  @Test
  void unreadablePageFieldFormattingStaysUnknown() {
    var chapter = chapter("第一章 引言");
    var footer = new PageNumberModel(new SourceAddress("word/footer-default.xml", List.of(0), 0, 0),
        true, null, null, null, List.of(0), List.of(), PageNumberModel.FooterVariant.DEFAULT);
    var document = new ParsedDocument("source", List.of(chapter),
        List.of(section(chapter.source(), false, false)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(footer));

    assertEquals(new RuleRun("PAGE-05", "SectionLayoutRuleChecker", 3, 0, 3, null),
        ruleRun(document, "PAGE-05"));
  }

  @Test
  void frontMatterWithoutAnExplicitPageFieldDecisionStaysUnknown() {
    var chapter = chapter("第一章 引言", source(0, 1));
    var sections = List.of(section(source(0, 0), false, false),
        section(chapter.source(), false, false));
    var footer = page("word/footer-body.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(1));
    var document = new ParsedDocument("source", List.of(chapter), sections, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(footer));

    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 4, 2, 2, null),
        ruleRun(document, "PAGE-04"));
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
  void checksOnlyDefinitelyDisplayedFirstFooterForMissingPageField() {
    var chapter = chapter("第一章 引言");
    var bodySection = section(chapter.source(), true, true);
    var defaultFooter = page("word/footer-default.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(0));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(bodySection), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(defaultFooter)), List.of(
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.EXPLICIT, "word/footer-default.xml",
            FooterPageEvidence.Presence.PRESENT),
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.FIRST,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.EVEN,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT)));

    var missing = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY"))
        .toList();

    assertEquals(1, missing.size());
    assertTrue(missing.stream().allMatch(result -> result.judgement() == Judgement.FAIL), missing.toString());
    assertEquals("首页页脚中的 PAGE 域", missing.getFirst().expected());
    assertEquals(new RuleRun("PAGE-05", "SectionLayoutRuleChecker", 9, 0, 9, null),
        ruleRun(document, "PAGE-05"));
  }

  @Test
  void titlePageBodyDoesNotFailForDefaultFooterThatMayNeverDisplay() {
    var chapter = chapter("第一章 引言");
    var firstFooter = page("word/footer-first.xml", PageNumberModel.FooterVariant.FIRST,
        true, "Times New Roman", 21, List.of(0));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(section(chapter.source(), true, false)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(firstFooter)), List.of(
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.FIRST,
            FooterPageEvidence.Origin.EXPLICIT, "word/footer-first.xml",
            FooterPageEvidence.Presence.PRESENT)));

    var evaluation = new SectionLayoutRuleChecker().evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.results().stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY") && result.judgement() == Judgement.FAIL));
    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 4, 2, 2, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void titlePageFrontMatterDoesNotFailForDefaultFooterThatMayNeverDisplay() {
    var frontSection = section(source(0, 0), true, false);
    var chapter = chapter("第一章 引言", source(0, 1));
    var frontDefault = page("word/footer-front-default.xml",
        PageNumberModel.FooterVariant.DEFAULT, true, "Times New Roman", 21, List.of(0));
    var bodyDefault = page("word/footer-body.xml",
        PageNumberModel.FooterVariant.DEFAULT, true, "Times New Roman", 21, List.of(1));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(frontSection, section(chapter.source(), false, false)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(frontDefault, bodyDefault)), List.of(
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.EXPLICIT, "word/footer-front-default.xml",
            FooterPageEvidence.Presence.PRESENT),
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.FIRST,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
        new FooterPageEvidence(1, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.EXPLICIT, "word/footer-body.xml",
            FooterPageEvidence.Presence.PRESENT)));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_IN_FRONT_MATTER") && result.judgement() == Judgement.FAIL),
        findings.toString());
    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 5, 3, 2, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void titlePageFrontMatterFailsWhenItsFirstFooterContainsPageField() {
    var chapter = chapter("第一章 引言", source(0, 1));
    var frontFirst = page("word/footer-front-first.xml",
        PageNumberModel.FooterVariant.FIRST, true, "Times New Roman", 21, List.of(0));
    var bodyDefault = page("word/footer-body.xml",
        PageNumberModel.FooterVariant.DEFAULT, true, "Times New Roman", 21, List.of(1));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(section(source(0, 0), true, false), section(chapter.source(), false, false)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(frontFirst, bodyDefault)),
        List.of(
            new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
            new FooterPageEvidence(0, PageNumberModel.FooterVariant.FIRST,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-front-first.xml",
                FooterPageEvidence.Presence.PRESENT),
            new FooterPageEvidence(1, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-body.xml",
                FooterPageEvidence.Presence.PRESENT)));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_IN_FRONT_MATTER") && result.judgement() == Judgement.FAIL),
        findings.toString());
  }

  @Test
  void frontMatterWithBothParityFootersNumberedFailsOnce() {
    var chapter = chapter("第一章 引言", source(0, 1));
    var frontDefault = page("word/footer-front-default.xml",
        PageNumberModel.FooterVariant.DEFAULT, true, "Times New Roman", 21, List.of(0));
    var frontEven = page("word/footer-front-even.xml",
        PageNumberModel.FooterVariant.EVEN, true, "Times New Roman", 21, List.of(0));
    var bodyDefault = page("word/footer-body.xml",
        PageNumberModel.FooterVariant.DEFAULT, true, "Times New Roman", 21, List.of(1));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(section(source(0, 0), false, true), section(chapter.source(), false, false)),
        List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(frontDefault, frontEven, bodyDefault)), List.of(
            new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-front-default.xml",
                FooterPageEvidence.Presence.PRESENT),
            new FooterPageEvidence(0, PageNumberModel.FooterVariant.EVEN,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-front-even.xml",
                FooterPageEvidence.Presence.PRESENT),
            new FooterPageEvidence(1, PageNumberModel.FooterVariant.DEFAULT,
                FooterPageEvidence.Origin.EXPLICIT, "word/footer-body.xml",
                FooterPageEvidence.Presence.PRESENT)));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_IN_FRONT_MATTER"))
        .toList();

    assertEquals(1, findings.size(), findings.toString());
    assertEquals(Judgement.FAIL, findings.getFirst().judgement());
    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 5, 4, 1, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void evenFooterWithoutProvenEvenPageDoesNotCauseMissingPageNumberFailure() {
    var chapter = chapter("第一章 引言");
    var defaultFooter = page("word/footer-default.xml",
        PageNumberModel.FooterVariant.DEFAULT, true, "Times New Roman", 21, List.of(0));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(section(chapter.source(), false, true)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(defaultFooter)), List.of(
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.EXPLICIT, "word/footer-default.xml",
            FooterPageEvidence.Presence.PRESENT),
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.EVEN,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT)));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY") && result.judgement() == Judgement.FAIL),
        findings.toString());
    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 4, 2, 2, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void missingBothOddAndEvenFootersFailsOnceDespiteUnknownFirstPageParity() {
    var chapter = chapter("第一章 引言");
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(section(chapter.source(), false, true)), List.of()), List.of(
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.EVEN,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT)));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY"))
        .toList();

    assertEquals(1, findings.size(), findings.toString());
    assertEquals(Judgement.FAIL, findings.getFirst().judgement());
    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 4, 3, 1, null),
        ruleRun(document, "PAGE-04"));
  }

  @Test
  void missingOddFooterAloneStaysUnknownWhenEvenFooterHasPageField() {
    var chapter = chapter("第一章 引言");
    var evenFooter = page("word/footer-even.xml", PageNumberModel.FooterVariant.EVEN,
        true, "Times New Roman", 21, List.of(0));
    var document = withFooterEvidence(new ParsedDocument("source", List.of(chapter),
        List.of(section(chapter.source(), false, true)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(evenFooter)), List.of(
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.EVEN,
            FooterPageEvidence.Origin.EXPLICIT, "word/footer-even.xml",
            FooterPageEvidence.Presence.PRESENT)));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY") && result.judgement() == Judgement.FAIL),
        findings.toString());
  }

  @Test
  void checksFooterStyleOnlyWhenVariantDefinitelyDisplays() {
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
    assertTrue(findings.stream().noneMatch(result -> result.reasonCode().equals("PAGE_NUMBER_SIZE_MISMATCH")),
        findings.toString());
    assertEquals(new RuleRun("PAGE-05", "SectionLayoutRuleChecker", 9, 3, 6, null),
        ruleRun(document, "PAGE-05"));
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
    assertEquals(new RuleRun("PAGE-04", "SectionLayoutRuleChecker", 4, 3, 1, null),
        ruleRun(document, "PAGE-04"));
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
    var document = withFooterEvidence(new ParsedDocument("source",
        List.of(tableContentsTitle, firstBodyChapter, secondBodyChapter), sections, List.of(),
        List.of(table), List.of(), List.of()), List.of(
        new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
        new FooterPageEvidence(1, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT),
        new FooterPageEvidence(2, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.NONE, null, FooterPageEvidence.Presence.ABSENT)));

    var missingPageNumbers = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY"))
        .toList();

    assertEquals(2, missingPageNumbers.size());
  }

  @Test
  void contentsChapterWithoutPageNumberDoesNotShiftBodySectionScope() {
    var contentsTitle = paragraphAt("目 录", source(0, 0));
    var contentsEntry = paragraphAt("第二章 设计", source(0, 1));
    var bodyChapter = chapter("第二章 设计", source(0, 3));
    var sections = List.of(section(source(0, 2), false, false),
        section(source(0, 4), false, false));
    var bodyFooter = page("word/footer-body.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(1));
    var document = new ParsedDocument("source",
        List.of(contentsTitle, contentsEntry, bodyChapter), sections, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(bodyFooter));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY")), findings.toString());
    assertTrue(findings.stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_SCOPE_UNKNOWN")), findings.toString());
  }

  @Test
  void unnumberedIntroductionAfterContentsStillStartsBodySection() {
    var contentsTitle = paragraphAt("目 录", source(0, 0));
    var contentsEntry = paragraphAt("第二章 设计", source(0, 1));
    var introduction = chapter("引言", source(0, 3));
    var sections = List.of(section(source(0, 2), false, false),
        section(source(0, 4), false, false));
    var bodyFooter = page("word/footer-body.xml", PageNumberModel.FooterVariant.DEFAULT,
        true, "Times New Roman", 21, List.of(1));
    var document = new ParsedDocument("source",
        List.of(contentsTitle, contentsEntry, introduction), sections, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(bodyFooter));

    var findings = new SectionLayoutRuleChecker().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.reasonCode()
        .equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY")), findings.toString());
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

  private static RenderedLayoutEvidence visiblePageNumbers(String sourceSha, String secondNumber) {
    var provenance = new RenderedLayoutEvidence.Provenance("available", sourceSha, "pdf",
        "LibreOffice", "24.2", "2026-11-18T00:00:00Z", null, 2);
    var lines = List.of(
        new RenderedLayoutEvidence.TextLine(1, 100,
            List.of(new RenderedLayoutEvidence.Glyph("第一章 引言", 40))),
        new RenderedLayoutEvidence.TextLine(1, 800,
            List.of(new RenderedLayoutEvidence.Glyph("1", 303, 6))),
        new RenderedLayoutEvidence.TextLine(2, 100,
            List.of(new RenderedLayoutEvidence.Glyph("正文段落", 40))),
        new RenderedLayoutEvidence.TextLine(2, 800,
            List.of(new RenderedLayoutEvidence.Glyph(secondNumber, 303, 6))));
    var pages = List.of(new RenderedLayoutEvidence.PageBox(1, 612, 842),
        new RenderedLayoutEvidence.PageBox(2, 612, 842));
    return new RenderedLayoutEvidence(provenance, lines, pages);
  }

  private RuleRun ruleRun(ParsedDocument document, String ruleId) {
    return new SectionLayoutRuleChecker().evaluate(document, RuleSet.schoolV1()).ruleRuns().stream()
        .filter(run -> ruleId.equals(run.ruleId())).findFirst().orElseThrow();
  }

  private ParsedDocument withCompleteObjectInventories(ParsedDocument document) {
    return new ParsedDocument(document.sourceSha256(), document.paragraphs(), document.sections(),
        document.diagnostics(), document.tables(), document.equations(), document.references(),
        document.images(), document.pageNumbers(), document.chapterBreaks(), document.headers(),
        document.automaticNumbering(), true, document.possibleEquationObjectSources(), true,
        document.footerPageEvidence(), true);
  }

  private ParsedDocument withFooterEvidence(ParsedDocument document,
      List<FooterPageEvidence> footerPageEvidence) {
    return new ParsedDocument(document.sourceSha256(), document.paragraphs(), document.sections(),
        document.diagnostics(), document.tables(), document.equations(), document.references(),
        document.images(), document.pageNumbers(), document.chapterBreaks(), document.headers(),
        document.automaticNumbering(), document.tableInventoryComplete(),
        document.possibleEquationObjectSources(), document.equationInventoryComplete(),
        footerPageEvidence);
  }
}
