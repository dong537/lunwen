package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceParserTest {
  private static final SourceAddress A = new SourceAddress("word/document.xml", List.of(0), 0, 1);
  private static ParagraphModel p(String text) {
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, 24, false, A)), null, null, null, null, null, A, null);
  }

  private static ParagraphModel autoNumbered(String label, String text) {
    return new ParagraphModel(List.of(new RunModel(text, null, null, null, 24, false, A)),
        null, null, null, null, null, "auto", A, null, null, null, label,
        null, null, false, null, null, true);
  }
  @Test
  void recognizesSupportedReferenceCategories() {
    var entries = new ReferenceParser().parse(List.of(p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("[2] 作者. 文章[J]. 期刊"), p("[3] 未分类")));
    assertEquals(java.util.Arrays.asList("M", "J", null), entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.get(2).needsReview());
  }

  @Test
  void recognizesReferenceHeadingWithInvisibleWordFormattingControls() {
    var entries = new ReferenceParser().parse(List.of(
        p("\uFEFF参\u200C考\u200D文\u2060献"),
        p("[1] 作者. 书名[M]. 出版社")));

    assertEquals(1, entries.size());
    assertEquals("1", entries.get(0).number());
    assertEquals("M", entries.get(0).category());
  }

  @Test
  void stopsReferenceSectionAtInvisibleFormattedAcknowledgementHeading() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("致\u200B谢"),
        p("[2] 这段正文不应计为参考文献")));

    assertEquals(1, entries.size());
    assertEquals("1", entries.get(0).number());
  }

  @Test
  void stopsReferenceSectionAtAppendixTitleWithoutWhitespaceAfterItsLetter() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 北京：出版社，2024."),
        p("附录A名词术语及缩略词"), p("[2] 这段附录文字不应计为参考文献")));

    assertEquals(1, entries.size());
    assertEquals("1", entries.get(0).number());
  }

  @Test
  void rejectsLowercaseReferenceCategoryMarker() {
    var entries = new ReferenceParser().parse(List.of(p("参考文献"), p("[1] 作者. 文章[j]. 期刊")));

    assertNull(entries.get(0).category());
    assertTrue(entries.get(0).needsReview());
  }

  @Test
  void recognizesReferenceCategoryMarkerWithCommonPeriodStyles() {
    var entries = new ReferenceParser().parse(List.of(p("参考文献"),
        p("[1] 作者. 图书[M]"),
        p("[2] 作者. 期刊[J] . 期刊名"),
        p("[3] 作者. 电子文献[EB/OL]。网址"),
        p("[4] 作者. 学位论文[D]. 学校，2024.")));

    assertEquals(java.util.Arrays.asList("M", "J", "EB/OL", "D"),
        entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void recognizesConferenceCategoryBeforeDoubleSlash() {
    var entries = new ReferenceParser().parse(List.of(
        p("\u53c2\u8003\u6587\u732e"),
        p("[1] \u9ad8\u4e39. \u4f1a\u8bae\u8bba\u6587[C]//\u4e2d\u56fd\u751f\u547d\u5173\u6000\u534f\u4f1a, 2026.")));

    assertEquals(1, entries.size());
    assertEquals("C", entries.get(0).category());
    assertFalse(entries.get(0).needsReview());
  }

  @Test
  void recognizesFullWidthPeriodAfterJournalCategory() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[15] 孙媛．面向对象程序设计课程教学模式改革探索与实践 [J]．中国教育技术装备，2025，(06)：65-69."),
        p("[16] 黄婧．“互联网+”背景下Java程序设计课程教学改革策略 [J]．信息与电脑，2025，37 (05)：233-235．")));

    assertEquals(List.of("J", "J"), entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void recognizesJournalCategoryWithAsciiFullWidthAndIdeographicPeriods() {
    var splitMarker = new ParagraphModel(List.of(
        new RunModel("[4] 作者. 拆分文本[J]", null, null, null, 24, false, A),
        new RunModel("。期刊名，2024，1：1-2.", null, null, null, 24, false, A)),
        null, null, null, null, null, A, null);
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 半角句号[J]. 期刊名，2024，1：1-2."),
        p("[2] 作者. 全角句号[J]．期刊名，2024，1：1-2."),
        p("[3] 作者. 中文句号[J]。期刊名，2024，1：1-2."),
        splitMarker));

    assertEquals(List.of("J", "J", "J", "J"),
        entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void doesNotTreatCategoryMarkerBeforeAuthorTitleSeparatorAsReferenceType() {
    var entries = new ReferenceParser().parse(List.of(p("参考文献"),
        p("[1] [M]. 作者. 普通标题. 出版社，2024.")));

    assertNull(entries.get(0).category());
    assertTrue(entries.get(0).needsReview());
  }

  @Test
  void recognizesStandardAndOtherCategories() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] Standard[S]. 2024."),
        p("[2] Regulation[Z]. 2024.")));
    assertEquals(java.util.Arrays.asList("S", "Z"), entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void recognizesElectronicResourceCategoriesWithAndWithoutSubtype() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] Electronic book[EB]. 2024."),
        p("[2] Database[DB]. 2024."),
        p("[3] Online database[DB/OL]. 2024."),
        p("[4] Program[CP]. 2024."),
        p("[5] Online article[EB/OL]. 2024.")));

    assertEquals(java.util.Arrays.asList("EB", "DB", "DB/OL", "CP", "EB/OL"),
        entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void extractsReferenceFieldsNeededForFormatChecks() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 严蔚敏，吴伟民. 数据结构[M]. 北京：清华大学出版社，1997：35-37.")));

    var entry = entries.get(0);
    assertEquals("严蔚敏，吴伟民", entry.authorText());
    assertEquals("数据结构", entry.titleText());
    assertEquals("北京：清华大学出版社", entry.sourceText());
    assertEquals("1997", entry.yearText());
    assertEquals("35-37", entry.pagesText());
  }

  @Test
  void recognizesReferenceHeadingWithChineseSpacing() {
    var entries = new ReferenceParser().parse(List.of(
        p("参 考 文 献"), p("[1] 作者. 书名[M]. 出版社")));

    assertEquals(1, entries.size());
    assertEquals("M", entries.get(0).category());
  }

  @Test
  void extractsUrlAsTheRequiredSourceForElectronicReferences() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 网络资料[EB/OL]. [2024-01-01]. https://example.edu/paper"),
        p("[2] 作者. 网络专著[M/OL]. https://example.edu/book")));

    assertEquals("https://example.edu/paper", entries.get(0).sourceText());
    assertEquals("https://example.edu/book", entries.get(1).sourceText());
    assertEquals("https://example.edu/paper", entries.get(0).urlText());
    assertEquals("2024-01-01", entries.get(0).accessDateText());
    assertNull(entries.get(1).accessDateText());
  }

  @Test
  void parsesOnlineBookAndJournalTypesWithTheirUrlAndAccessDate() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] 作者. 网络专著[M/OL]. https://example.edu/book [2024-01-01]"),
        p("[2] 作者. 网络期刊[J/OL]. https://example.edu/journal [2025-02-03]")));

    assertEquals(List.of("M/OL", "J/OL"), entries.stream().map(ReferenceEntryModel::category).toList());
    assertEquals(java.util.Arrays.asList("https://example.edu/book", null),
        entries.stream().map(ReferenceEntryModel::sourceText).toList());
    assertEquals(java.util.Arrays.asList(null, null), entries.stream().map(ReferenceEntryModel::yearText).toList());
    assertEquals(List.of("https://example.edu/book", "https://example.edu/journal"),
        entries.stream().map(ReferenceEntryModel::urlText).toList());
    assertEquals(List.of("2024-01-01", "2025-02-03"),
        entries.stream().map(ReferenceEntryModel::accessDateText).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void keepsOnlineJournalSourceAndPublicationYearSeparateFromUrlAndAccessDate() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] Author. Article title[J/OL]. Journal of Tests, 2024, 10(2):15-20. [2025-02-03]. https://example.edu/article")));

    var entry = entries.get(0);
    assertEquals("Journal of Tests", entry.sourceText());
    assertEquals("2024", entry.yearText());
    assertEquals("15-20", entry.pagesText());
    assertEquals("2025-02-03", entry.accessDateText());
    assertEquals("https://example.edu/article", entry.urlText());
  }

  @Test
  void extractsOnlineJournalPageRangeAfterCommaBeforeAccessDate() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[10] Author. Article title[J/OL]. Journal of Tests, 1-10[2026-05-27]. "
            + "https://example.edu/article")));

    var entry = entries.get(0);
    assertEquals("1-10", entry.pagesText());
    assertEquals("2026-05-27", entry.accessDateText());
  }

  @Test
  void extractsNewspaperPageFromEditionNumberAfterPublicationDate() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 邓辉. 校训雷同，缺失是什么？[N]. 光明日报，2013-03-06(08).")));

    assertEquals(1, entries.size());
    assertEquals("N", entries.get(0).category());
    assertEquals("光明日报", entries.get(0).sourceText());
    assertEquals("08", entries.get(0).pagesText());
  }

  @Test
  void preservesWhitespaceInPageRangeSoFormattingCanBeCheckedSeparately() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 期刊文章[J]. 期刊名，2024，1(2)：140- 145.")));

    assertEquals(1, entries.size());
    assertEquals("140- 145", entries.get(0).pagesText());
  }

  @Test
  void doesNotUseOnlineAccessDateAsThePublicationYear() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] Author. Article title[J/OL]. [2024-02-01]. https://example.edu/article")));

    assertNull(entries.get(0).yearText());
    assertEquals("2024-02-01", entries.get(0).accessDateText());
  }

  @Test
  void recognizesDocumentTypesWithStandardElectronicCarriers() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        p("[1] Book[M/CD]. Disc publisher"),
        p("[2] Program[CP/DK]. Software archive"),
        p("[3] Database[DB/MT]. Data archive"),
        p("[4] Online journal[J/OL]. https://example.edu/journal")));

    assertEquals(List.of("M/CD", "CP/DK", "DB/MT", "J/OL"),
        entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void recognizesCategoryWithIdeographicOrNoBreakSpaceBeforePunctuation() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 期刊[J]\u3000．期刊名，2024."),
        p("[2] 作者. 期刊[J]\u00A0。期刊名，2024.")));

    assertEquals(List.of("J", "J"), entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().noneMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void doesNotAcceptCarrierCodesAsStandaloneDocumentTypes() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"), p("[1] Example[OL]. Publisher"), p("[2] Example[DL]. Publisher")));

    assertEquals(java.util.Arrays.asList(null, null),
        entries.stream().map(ReferenceEntryModel::category).toList());
    assertTrue(entries.stream().allMatch(ReferenceEntryModel::needsReview));
  }

  @Test
  void excludesSentencePunctuationFromElectronicReferenceUrl() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("[1] 作者. 网络资料[EB/OL]. https://example.edu/paper).")));

    assertEquals("https://example.edu/paper", entries.get(0).sourceText());
  }

  @Test
  void stopsCollectingEntriesAtAcknowledgementsAndAppendix() {
    var afterAcknowledgements = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("致谢"), p("[1] 本文使用的测试数据")));
    var afterAppendix = new ReferenceParser().parse(List.of(
        p("References"), p("[1] Author. Title[J]. Journal"), p("附录 A"), p("[2] 示例条目")));

    assertEquals(1, afterAcknowledgements.size());
    assertEquals(1, afterAppendix.size());
  }

  @Test
  void stopsCollectingReferencesAtAppendixHeadingWithItsTitle() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 出版社"),
        p("附录A 名词术语及缩略词"), p("[1] 附录中的示例编号")));

    assertEquals(1, entries.size());
  }

  @Test
  void stopsCollectingReferencesAtEndHeadingsWithFullWidthSpacing() {
    var afterAcknowledgements = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("致　谢"),
        p("[2] 这段致谢不应计为参考文献")));
    var afterAppendix = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("附录　A 名词术语及缩略词"),
        p("[2] 附录示例不应计为参考文献")));

    assertEquals(1, afterAcknowledgements.size());
    assertEquals(1, afterAppendix.size());
  }

  @Test
  void stopsCollectingReferencesWhenEndHeadingHasTrailingNoBreakSpace() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"), p("[1] 作者. 书名[M]. 出版社"), p("致谢\u00A0"),
        p("[2] 这段文字不应计为参考文献")));

    assertEquals(1, entries.size());
  }

  @Test
  void countsBibliographyEntriesWhenWordDocumentOmitsNumericLabels() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("邵沁怡.流浪动物领养APP界面的情感化设计研究[D].中国计量大学，2024."),
        p("KREMER T. A new web-based tool for RTO-focused animal shelter data analysis[J]. Frontiers in Veterinary Science，2021，8:669428."),
        p("GRINBERG M. Flask Web Development: Developing Web Applications with Python[M]. 2nd ed. Sebastopol: O'Reilly Media，2018.")));

    assertEquals(3, entries.size());
    assertTrue(entries.stream().allMatch(entry -> entry.number() == null));
    assertEquals(List.of("D", "J", "M"), entries.stream().map(ReferenceEntryModel::category).toList());
  }

  @Test
  void countsUnnumberedEntriesSeparatedByFullWidthPeriods() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("作者．中文题名[J]．期刊名，2024，1：1-2.")));

    assertEquals(1, entries.size());
    assertEquals("J", entries.get(0).category());
    assertEquals("作者", entries.get(0).authorText());
    assertEquals("中文题名", entries.get(0).titleText());
    assertEquals("期刊名", entries.get(0).sourceText());
  }

  @Test
  void doesNotCountCategoryMentionInsideNarrativeAsAnUnnumberedReference() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("本文介绍了[M]类图书和[J]类期刊的文献标识。")));

    assertTrue(entries.isEmpty());
  }

  @Test
  void doesNotCountCategoryMarkersWithSentencePunctuationAsReferences() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献"),
        p("常见文献标识包括[M].，期刊标识包括[J].，均按类型区分。")));

    assertTrue(entries.isEmpty());
  }

  @Test
  void doesNotTreatGeneratedTocReferenceLineAsBibliographyHeading() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献……10"),
        p("[1] 作者. 书名[M]. 出版社"),
        p("参考文献"),
        p("[1] 作者. 书名[M]. 出版社")));

    assertEquals(1, entries.size());
  }

  @Test
  void doesNotTreatReferenceReviewHeadingAsTheBibliographySection() {
    var entries = new ReferenceParser().parse(List.of(
        p("参考文献综述"),
        p("[1] 作者. 综述引用示例[M]. 出版社，2024."),
        p("后续正文仍在讨论参考文献格式。")));

    assertTrue(entries.isEmpty());
  }

  @Test
  void usesResolvedWordAutomaticNumberingForReferenceSequence() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        autoNumbered("1.", "作者. 书名[M]. 出版社"),
        autoNumbered("2.", "作者. 文章[J]. 期刊"),
        autoNumbered("3.", "作者. 论文[D]. 学校")));

    assertEquals(List.of("1", "2", "3"), entries.stream().map(ReferenceEntryModel::number).toList());
  }

  @Test
  void doesNotTreatUnresolvedOrChapterAutomaticNumberingAsReferenceNumber() {
    var entries = new ReferenceParser().parse(List.of(
        p("References"),
        new ParagraphModel(List.of(new RunModel("作者. 书名[M]. 出版社", null, null, null, 24, false, A)),
            null, null, null, null, null, "auto", A, null, null, null, "第一章", null, null, false,
            null, null, true)));

    assertEquals(1, entries.size());
    assertNull(entries.get(0).number());
  }
}
