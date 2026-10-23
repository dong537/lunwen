package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceParser;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceRuleCheckerTest {
  private static final SourceAddress A = new SourceAddress("word/document.xml", List.of(0), 0, 1);

  @Test
  void checksRequiredBibliographicFieldsByReferenceCategory() {
    var paragraphs = List.of(
        p("参考文献"),
        p("[1] 王伟. 算法设计[M]。"),
        p("[2] 李四. 检测方法[J]．期刊名，2024."),
        p("[3] 张三. 完整期刊论文[J]。期刊名，2024，10(2)：22-30."),
        p("[4] 张美金. 网络资料[EB/OL]. [2024-01-01]. https://example.edu/paper"),
        p("[5] 王五. 缺少访问日期[EB/OL]. https://example.edu/page"));

    var references = new ReferenceParser().parse(paragraphs);
    var results = new ReferenceRuleChecker().check(references);
    var formatFailures = results.stream().filter(result -> result.ruleId().equals("REF-04")).toList();

    assertEquals(List.of("REFERENCE_FIELDS_MISSING", "REFERENCE_FIELDS_MISSING", "REFERENCE_FIELDS_MISSING"),
        formatFailures.stream().map(CheckResult::reasonCode).toList());
    assertEquals(3, formatFailures.size());
    assertTrue(formatFailures.stream().allMatch(result -> result.judgement() == Judgement.FAIL));
    assertFalse(results.stream().anyMatch(result -> result.ruleId().equals("REF-04")
        && result.actual() != null && result.actual().contains("[3]")));
    assertFalse(results.stream().anyMatch(result -> result.ruleId().equals("REF-04")
        && result.actual() != null && result.actual().contains("[4]")));
  }

  @Test
  void acceptsChineseAndFullWidthPeriodsAfterCategoryMarkers() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 文章[J]．期刊名，2024，1(1)：1-2."),
        p("[2] 作者. 文章[J]。期刊名，2024，1(1)：1-2.")));

    assertEquals(List.of("J", "J"), references.stream().map(reference -> reference.category()).toList());
    assertTrue(references.stream().noneMatch(reference -> reference.needsReview()));
    assertTrue(new ReferenceRuleChecker().check(references).stream()
        .noneMatch(result -> result.ruleId().equals("REF-04")));
  }

  @Test
  void reportsOnlineJournalMissingJournalSourceEvenWhenUrlAndAccessDateExist() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 文章题名[J/OL]. 2024：15-20. [2025-02-03]. https://example.edu/article")));

    var results = new ReferenceRuleChecker().check(references);

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("REF-04")
        && result.reasonCode().equals("REFERENCE_FIELDS_MISSING")
        && result.actual().contains("期刊来源")));
  }

  @Test
  void doesNotAcceptAccessDateAsOnlineJournalPublicationYear() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 文章题名[J/OL]. [2024-02-01]. https://example.edu/article")));

    var results = new ReferenceRuleChecker().check(references);

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("REF-04")
        && result.reasonCode().equals("REFERENCE_FIELDS_MISSING")
        && result.actual().contains("出版年份")));
  }

  @Test
  void acceptsCommaSeparatedOnlineJournalPagesButStillRequiresPublicationYear() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[10] 余志锐,程铁栋. 基于邻域优先的多级均值椒盐噪声滤波算法 [J/OL]. "
            + "电子测量技术, 1-10[2026-05-27]. https://example.edu/article")));

    var reference = references.get(0);
    assertEquals("1-10", reference.pagesText());
    assertNull(reference.yearText());

    var fieldFinding = new ReferenceRuleChecker().check(references).stream()
        .filter(result -> result.ruleId().equals("REF-04"))
        .findFirst()
        .orElseThrow();
    assertTrue(fieldFinding.actual().contains("出版年份"));
    assertFalse(fieldFinding.actual().contains("页码"));
  }

  @Test
  void recognizesPlusSeparatedPagesAndStillReportsReferencesWithoutPages() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 文章一[J]. 期刊名，2025，21(08)：83-85+67."),
        p("[2] 作者. 文章二[J]. 期刊名，2025，15(02)：144-146+150."),
        p("[3] 作者. 文章三[J]. 期刊名，2022，36(20)：72-73+62."),
        p("[4] 作者. 缺少页码的文章[J]. 期刊名，2025，1(1).")));

    assertEquals(java.util.Arrays.asList("83-85+67", "144-146+150", "72-73+62", null),
        references.stream().map(ReferenceEntryModel::pagesText).toList());

    var fieldFailures = new ReferenceRuleChecker().check(references).stream()
        .filter(result -> result.ruleId().equals("REF-04"))
        .toList();

    assertEquals(1, fieldFailures.size());
    assertTrue(fieldFailures.get(0).actual().contains("[4]"));
    assertTrue(fieldFailures.get(0).actual().contains("缺少页码"));
  }

  @Test
  void distinguishesMalformedPageRangeSpacingFromMissingPages() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 期刊文章[J]. 期刊名，2024，1(2)：140- 145."),
        p("[2] 作者. 缺少页码[J]. 期刊名，2024，1(3).")));

    var results = new ReferenceRuleChecker().check(references);
    var pageFormatFinding = results.stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_PAGE_RANGE_SPACING"))
        .findFirst()
        .orElseThrow();
    var missingPageFinding = results.stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_FIELDS_MISSING"))
        .findFirst()
        .orElseThrow();

    assertTrue(pageFormatFinding.actual().contains("140- 145"));
    assertFalse(pageFormatFinding.actual().contains("[2]"));
    assertTrue(missingPageFinding.actual().contains("[2]"));
    assertTrue(missingPageFinding.actual().contains("缺少页码"));
    assertFalse(results.stream().anyMatch(result -> result.reasonCode().equals("REFERENCE_FIELDS_MISSING")
        && result.actual().contains("[1]")));
  }

  @Test
  void acceptsNewspaperEditionPageWithoutReportingItMissing() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 邓辉. 校训雷同，缺失是什么？[N]. 光明日报，2013-03-06(08).")));

    assertEquals("08", references.get(0).pagesText());
    assertTrue(new ReferenceRuleChecker().check(references).stream()
        .noneMatch(result -> result.reasonCode().equals("REFERENCE_FIELDS_MISSING")));
  }

  @Test
  void parsesSupplementaryPagesFromAnOnlineJournalReference() {
    var references = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[7] 余志锐,程铁栋. 基于邻域优先的多级均值椒盐噪声滤波算法 [J/OL]. 电子测量技术,2004,(7):83-85+67.")));

    assertEquals(1, references.size());
    assertEquals("83-85+67", references.get(0).pagesText());
  }

  @Test
  void requiresExactlyOneOrdinarySpaceAfterReferenceNumber() {
    var references = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1]Author. Title[J]. Journal, 2024, 1(1): 1-2."),
        p("[2]  Author. Title[J]. Journal, 2024, 1(1): 1-2."),
        p("[3]\u3000Author. Title[J]. Journal, 2024, 1(1): 1-2."),
        p("[4]\u00a0Author. Title[J]. Journal, 2024, 1(1): 1-2."),
        p("[5] Author. Title[J]. Journal, 2024, 1(1): 1-2.")));

    var spacingFindings = new ReferenceRuleChecker().check(references).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH"))
        .toList();

    assertEquals(4, spacingFindings.size());
    assertTrue(spacingFindings.stream().allMatch(result -> result.ruleId().equals("REF-07")));
    assertTrue(new ReferenceRuleChecker().check(references).stream()
        .noneMatch(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH")
            && result.actual() != null && result.actual().contains("[5]")));
  }

  @Test
  void anchorsMissingReferenceNumberSpaceAtTheGapWithoutHighlightingTheEntry() {
    String text = "[1]Author. Title[M]. Publisher, 2024.";
    var reference = numberedReference(text);

    var finding = new ReferenceRuleChecker().check(List.of(reference)).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH"))
        .findFirst()
        .orElseThrow();

    assertEquals(3, finding.source().start());
    assertEquals(3, finding.source().end());
  }

  @Test
  void anchorsOnlyTheExtraReferenceNumberSpaces() {
    String text = "[1]  Author. Title[M]. Publisher, 2024.";
    var reference = numberedReference(text);

    var finding = new ReferenceRuleChecker().check(List.of(reference)).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH"))
        .findFirst()
        .orElseThrow();

    assertEquals(4, finding.source().start());
    assertEquals(5, finding.source().end());
  }

  @Test
  void anchorsOnlyTheNonstandardReferenceNumberWhitespace() {
    String text = "[1]\u00A0Author. Title[M]. Publisher, 2024.";
    var reference = numberedReference(text);

    var finding = new ReferenceRuleChecker().check(List.of(reference)).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH"))
        .findFirst()
        .orElseThrow();

    assertEquals(3, finding.source().start());
    assertEquals(4, finding.source().end());
  }

  @Test
  void keepsTheRequiredOrdinarySpaceUnhighlightedWhenAnotherWhitespaceFollows() {
    String text = "[1] \u00A0Author. Title[M]. Publisher, 2024.";
    var reference = numberedReference(text);

    var finding = new ReferenceRuleChecker().check(List.of(reference)).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH"))
        .findFirst()
        .orElseThrow();

    assertEquals(4, finding.source().start());
    assertEquals(5, finding.source().end());
  }

  @Test
  void keepsReferenceWhitespaceOffsetsAlignedAfterParserTrimsLeadingWhitespace() {
    String rawText = "  [1]\u00A0Author. Title[M]. Publisher, 2024.";
    var paragraph = new ParagraphModel(
        List.of(new RunModel(rawText, null, null, null, 24, false, A)),
        null, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(1), 0, rawText.length()), null);
    var reference = new ReferenceParser().parse(List.of(p("References"), paragraph)).get(0);

    var finding = new ReferenceRuleChecker().check(List.of(reference)).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH"))
        .findFirst()
        .orElseThrow();

    assertEquals(5, finding.source().start());
    assertEquals(6, finding.source().end());
  }

  @Test
  void keepsReferenceWhitespaceOffsetsAlignedWhenBothEndsContainWhitespace() {
    String rawText = "  [1]\u00A0Author. Title[M]. Publisher, 2024.   ";
    var paragraph = new ParagraphModel(
        List.of(new RunModel(rawText, null, null, null, 24, false, A)),
        null, null, null, null, null,
        new SourceAddress("word/document.xml", List.of(1), 0, rawText.length()), null);
    var reference = new ReferenceParser().parse(List.of(p("References"), paragraph)).get(0);

    var finding = new ReferenceRuleChecker().check(List.of(reference)).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SPACING_MISMATCH"))
        .findFirst()
        .orElseThrow();

    assertEquals(5, finding.source().start());
    assertEquals(6, finding.source().end());
  }

  @Test
  void delegatesAutomaticNumberingPolicyWithoutInventingAVisibleNumberRange() {
    var reference = new ReferenceEntryModel(
        new SourceAddress("word/document.xml", List.of(0), 0, 33), "1",
        "Author. Title[M]. Publisher, 2024.", "M", false,
        null, null, null, null, null, null, null, 0, 0, true);

    var findings = new ReferenceRuleChecker().check(List.of(reference));
    assertFalse(findings.stream().anyMatch(result -> result.reasonCode().startsWith("REFERENCE_NUMBER_SPACING")));
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("REF-04")),
        "著录字段检查仍独立执行");
  }

  @Test
  void doesNotDuplicateTheGlobalPolicyWithAnUnknownAutomaticSeparatorFinding() {
    var reference = new ReferenceEntryModel(
        new SourceAddress("word/document.xml", List.of(0), 0, 33), "1",
        "Author. Title[M]. Publisher, 2024.", "M", false,
        null, null, null, null, null, null, null, null, null, true);

    var findings = new ReferenceRuleChecker().check(List.of(reference));
    assertFalse(findings.stream().anyMatch(result -> result.reasonCode().startsWith("REFERENCE_NUMBER_SPACING")));
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("REF-04")));
  }

  @Test
  void checksSequenceIndependentlyOfTheProhibitedAutomaticReferenceNumbering() {
    var references = new ReferenceParser().parse(List.of(
        p("References"),
        automaticReference("Author. Title[M]. Publisher, 2024.", "1.", 0, 0),
        automaticReference("Author. Title[M]. Publisher, 2024.", "3.", 1, 0)));

    var findings = new ReferenceRuleChecker().check(references).stream()
        .filter(result -> result.reasonCode().equals("REFERENCE_NUMBER_SEQUENCE_MISMATCH"))
        .toList();

    assertEquals(1, findings.size());
    assertEquals("2", findings.get(0).expected());
    assertEquals("3", findings.get(0).actual());
  }

  @Test
  void countsUnnumberedUnclassifiedBibliographyEntryWithoutTreatingNearbyProseAsAnEntry() {
    var paragraphs = List.of(
        p("参考文献"),
        p("以下为本章所用资料。"),
        p("普通说明。该文献目录编制于2024年。"),
        p("作者. 题名. 出版社，2024."),
        p("这段说明仍不属于具体文献条目。"),
        p("致谢"),
        p("致谢中的内容不应进入参考文献统计。"));

    var references = new ReferenceParser().parse(paragraphs);

    assertEquals(1, references.size());
    assertEquals("作者. 题名. 出版社，2024.", references.get(0).text());
    assertNull(references.get(0).number());
    assertNull(references.get(0).category());

    var document = new ParsedDocument("source", paragraphs, List.of(), List.of(), List.of(), List.of(), references);
    var collectionFindings = new ReferenceCollectionRuleModule().check(document, RuleSet.schoolV1());
    var formattingFindings = new ReferenceRuleChecker().check(references);

    assertTrue(collectionFindings.stream().anyMatch(finding -> finding.ruleId().equals("REF-05")
        && finding.actual().equals("1")));
    assertTrue(collectionFindings.stream().anyMatch(finding -> finding.ruleId().equals("REF-02")
        && finding.source().equals(references.get(0).source())));
    assertTrue(formattingFindings.stream().anyMatch(finding -> finding.ruleId().equals("REF-07")
        && finding.reasonCode().equals("REFERENCE_NUMBER_INVALID")));
  }

  private static ParagraphModel p(String text) {
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, 24, false, A)),
        null, null, null, null, null, A, null);
  }

  private static ReferenceEntryModel numberedReference(String text) {
    return new ReferenceEntryModel(
        new SourceAddress("word/document.xml", List.of(0), 0, text.length()), "1", text, null, false);
  }

  private static ParagraphModel automaticReference(String text, String label,
      Integer suffixSpaces, Integer formatTrailingSpaces) {
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, 24, false, A)),
        null, null, null, null, null, null, A, null, null, null, label,
        suffixSpaces, formatTrailingSpaces, false, null, null, true);
  }
}
