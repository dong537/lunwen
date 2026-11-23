package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.HeadingBreakModel;
import cn.luwneshacha.thesis.document.FooterPageEvidence;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rendering.RenderedLayoutEvidence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layout rules whose evidence is attached to document sections or page
 * breaks. Keeping these checks together prevents RuleEngine's paragraph
 * classifiers from accidentally applying front-matter rules to the body.
 */
final class SectionLayoutRuleChecker implements RuleModule {
  private static final Pattern CHAPTER_NUMBER = Pattern.compile("^第([0-9]+|[一二三四五六七八九十百千零〇]+)章");
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();
  private final boolean untrustedFooterContent;
  private final RenderedLayoutEvidence renderedLayout;

  SectionLayoutRuleChecker() { this(false, null); }

  SectionLayoutRuleChecker(boolean untrustedFooterContent) {
    this(untrustedFooterContent, null);
  }

  SectionLayoutRuleChecker(boolean untrustedFooterContent,
      RenderedLayoutEvidence renderedLayout) {
    this.untrustedFooterContent = untrustedFooterContent;
    this.renderedLayout = renderedLayout;
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var evidence = new PageEvidence();
    var findings = checkWithEvidence(document, rules, evidence);
    if (untrustedFooterContent) {
      // An unreadable active footer can change which PAGE fields actually
      // render. Keep independently proven margins and chapter breaks, but do
      // not publish hard page-number conclusions from a partial footer view.
      findings = findings.stream().filter(result -> !"PAGE-04".equals(result.ruleId())
          && !"PAGE-05".equals(result.ruleId())).toList();
      evidence.page04.markUnknown();
      evidence.page05.markUnknown();
    }
    if (document.sections().isEmpty()) {
      var runs = new ArrayList<RuleRun>();
      runs.add(new RuleRun("PAGE-01", getClass().getSimpleName(), 1, 0, 1, null));
      evidence.appendTo(runs, getClass().getSimpleName());
      return new Evaluation(findings, List.copyOf(runs));
    }
    int unknown = 0;
    for (SectionModel section : document.sections()) {
      if (section.topMarginTwips() == null && !section.topMarginEvidenceInvalid()) unknown++;
      if (section.rightMarginTwips() == null && !section.rightMarginEvidenceInvalid()) unknown++;
      if (section.bottomMarginTwips() == null && !section.bottomMarginEvidenceInvalid()) unknown++;
      if (section.leftMarginTwips() == null && !section.leftMarginEvidenceInvalid()) unknown++;
    }
    int candidates = Math.multiplyExact(document.sections().size(), 4);
    var runs = new ArrayList<RuleRun>();
    runs.add(new RuleRun("PAGE-01", getClass().getSimpleName(),
        candidates, candidates - unknown, unknown, null));
    evidence.appendTo(runs, getClass().getSimpleName());
    return new Evaluation(findings, List.copyOf(runs));
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  private List<CheckResult> checkWithEvidence(ParsedDocument document, RuleSet rules,
      PageEvidence evidence) {
    var results = new ArrayList<CheckResult>();
    if (document.sections().isEmpty()) {
      results.add(result("PAGE-01", String.valueOf(rules.expectedMarginTwips()), "no section margin evidence",
          "MARGIN_UNKNOWN", Judgement.REVIEW, new SourceAddress("word/document.xml", List.of(), 0, 0)));
    }
    for (SectionModel section : document.sections()) results.addAll(checkMargins(section, rules));

    int firstBodySection = firstMainBodySectionIndex(document);
    if (firstBodySection < 0) {
      SourceAddress source = document.paragraphs().isEmpty()
          ? new SourceAddress("word/document.xml", List.of(), 0, 0)
          : document.paragraphs().get(0).source();
      results.add(result("PAGE-04", "可识别正文起始分节", "无法从章节和分节证据确定",
          "PAGE_NUMBER_SCOPE_UNKNOWN", Judgement.REVIEW, source));
      evidence.page04.record(false);
      evidence.page05.record(false);
      checkChapterBreaks(results, document, evidence.page03);
      return results;
    }
    checkPageNumberFormat(results, document, firstBodySection, evidence.page04);
    checkPageNumberSequence(results, document, firstBodySection, evidence.page04);
    checkPageNumberStyle(results, document, firstBodySection, evidence.page04, evidence.page05);
    checkMainBodyPageNumberPresence(results, document, firstBodySection, evidence.page04, evidence.page05);
    checkChapterBreaks(results, document, evidence.page03);
    return results;
  }

  private void checkPageNumberFormat(List<CheckResult> results, ParsedDocument document,
      int firstBodySection, Counter evidence) {
    for (int index = 0; index < document.sections().size(); index++) {
      SectionModel section = document.sections().get(index);
      boolean inBody = firstBodySection < 0 || index >= firstBodySection;
      if (!inBody) continue;
      evidence.record(section.pageNumberFormatInvalid() || section.pageNumberFormat() != null);
      if (section.pageNumberFormatInvalid()) {
        results.add(result("PAGE-04", "有效的页码格式", "invalid", "PAGE_NUMBER_FORMAT_INVALID",
            Judgement.FAIL, section.source()));
      } else if (section.pageNumberFormat() != null && !"decimal".equals(section.pageNumberFormat())) {
        results.add(result("PAGE-04", "decimal in main text", section.pageNumberFormat(),
            "PAGE_NUMBER_FORMAT_MISMATCH", Judgement.FAIL, section.source()));
      }
    }
  }

  private void checkPageNumberStyle(List<CheckResult> results, ParsedDocument document,
      int firstBodySection, Counter page04, Counter page05) {
    ParagraphModel firstChapter = firstMainBodyChapter(document);
    for (int index = 0; index < firstBodySection; index++) {
      int sectionIndex = index;
      SectionModel section = document.sections().get(index);
      boolean bothParityFootersNumbered = section.evenPageHeaderEnabled()
          && !section.firstPageHeaderEnabled()
          && List.of(cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.DEFAULT,
              cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.EVEN).stream()
              .allMatch(variant -> document.pageNumbers().stream().anyMatch(pageNumber ->
                  pageNumber.pageField() && pageNumber.footerVariant() == variant
                      && appliesToSection(pageNumber, sectionIndex)));
      for (var variant : activeFooterVariants(document.sections().get(index))) {
        boolean present = document.pageNumbers().stream().anyMatch(pageNumber -> pageNumber.pageField()
            && pageNumber.footerVariant() == variant && appliesToSection(pageNumber, sectionIndex));
        // A parsed, effective footer inventory can prove a negative. A
        // hand-built document without that inventory must stay unknown. A
        // PAGE field in a footer that may never render is not a proven defect.
        page04.record((present && (footerVisibility(section, variant)
            == FooterVisibility.DEFINITE || bothParityFootersNumbered))
            || footerPresence(document, sectionIndex, variant) == FooterPageEvidence.Presence.ABSENT);
      }
      if (bothParityFootersNumbered) {
        results.add(result("PAGE-04", "封面、摘要和目录不显示页码",
            "默认和偶数页页脚均含 PAGE 域", "PAGE_NUMBER_IN_FRONT_MATTER",
            Judgement.FAIL, section.source()));
      }
    }
    for (var pageNumber : document.pageNumbers()) {
      if (!pageNumber.pageField()) continue;
      int frontSectionIndex = firstApplicableSectionIndex(pageNumber, document, 0, firstBodySection, true);
      if (frontSectionIndex >= 0) {
        results.add(result("PAGE-04", "封面、摘要和目录不显示页码", "页脚中存在页码域",
            "PAGE_NUMBER_IN_FRONT_MATTER", Judgement.FAIL,
            document.sections().get(frontSectionIndex).source()));
      }
      int bodySectionIndex = firstBodySection < 0 ? -1
          : firstApplicableSectionIndex(pageNumber, document, firstBodySection, document.sections().size(), false);
      if (firstBodySection >= 0 && bodySectionIndex < 0) continue;
      if (firstBodySection >= 0 && firstApplicableSectionIndex(pageNumber, document,
          firstBodySection, document.sections().size(), true) < 0) {
        // Without rendered-page evidence this field may not appear anywhere.
        // Its font, size and alignment cannot be hard-checked.
        page05.record(false);
        int runCount = Math.max(1, pageNumber.runFormatting().size());
        for (int run = 0; run < runCount; run++) {
          page05.record(false);
          page05.record(false);
        }
        continue;
      }
      page05.record(pageNumber.centered() != null);
      SourceAddress styleSource = firstBodySection >= 0 && firstChapter != null
          ? firstChapter.source() : pageNumber.source();
      if (pageNumber.centered() == null) {
        results.add(result("PAGE-05", "center", "unknown", "PAGE_NUMBER_ALIGNMENT_UNKNOWN", Judgement.REVIEW, styleSource));
      } else if (!pageNumber.centered()) {
        results.add(result("PAGE-05", "center", "not centered", "PAGE_NUMBER_NOT_CENTERED", Judgement.FAIL, styleSource));
      }
      if (pageNumber.runFormatting().isEmpty()) {
        page05.record(false);
        page05.record(false);
      }
      for (var run : pageNumber.runFormatting()) {
        page05.record(run.asciiFont() != null);
        page05.record(run.sizeHalfPoints() != null);
        if (run.asciiFont() == null) {
          results.add(result("PAGE-05", "Times New Roman", "unknown", "PAGE_NUMBER_FONT_UNKNOWN", Judgement.REVIEW, styleSource));
        } else if (!"Times New Roman".equals(run.asciiFont())) {
          results.add(result("PAGE-05", "Times New Roman", run.asciiFont(), "PAGE_NUMBER_FONT_MISMATCH", Judgement.FAIL, styleSource));
        }
        if (run.sizeHalfPoints() == null) {
          results.add(result("PAGE-05", "21", "unknown", "PAGE_NUMBER_SIZE_UNKNOWN", Judgement.REVIEW, styleSource));
        } else if (run.sizeHalfPoints() != 21) {
          results.add(result("PAGE-05", "21", String.valueOf(run.sizeHalfPoints()), "PAGE_NUMBER_SIZE_MISMATCH", Judgement.FAIL, styleSource));
        }
      }
    }
  }

  private void checkMainBodyPageNumberPresence(List<CheckResult> results, ParsedDocument document,
      int firstBodySection, Counter page04, Counter page05) {
    if (firstBodySection < 0 || firstBodySection >= document.sections().size()) return;
    for (int index = firstBodySection; index < document.sections().size(); index++) {
      int sectionIndex = index;
      SectionModel section = document.sections().get(index);
      boolean bothParityFootersMissing = section.evenPageHeaderEnabled()
          && !section.firstPageHeaderEnabled()
          && List.of(cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.DEFAULT,
              cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.EVEN).stream()
              .allMatch(variant -> footerPresence(document, sectionIndex, variant)
                  == FooterPageEvidence.Presence.ABSENT
                  && document.pageNumbers().stream().noneMatch(pageNumber ->
                      pageNumber.pageField() && pageNumber.footerVariant() == variant
                          && appliesToSection(pageNumber, sectionIndex)));
      for (var variant : activeFooterVariants(section)) {
        boolean hasPageField = document.pageNumbers().stream().anyMatch(pageNumber ->
            pageNumber.pageField() && pageNumber.footerVariant() == variant
                && appliesToSection(pageNumber, sectionIndex));
        FooterPageEvidence.Presence footerPresence = footerPresence(document, sectionIndex, variant);
        boolean definitelyVisible = footerVisibility(section, variant) == FooterVisibility.DEFINITE;
        page04.record(hasPageField || ((definitelyVisible || bothParityFootersMissing)
            && footerPresence == FooterPageEvidence.Presence.ABSENT));
        if (!hasPageField && footerPresence == FooterPageEvidence.Presence.ABSENT) {
          page05.record(false);
          page05.record(false);
          page05.record(false);
          if (definitelyVisible) {
            results.add(result("PAGE-04", pageFieldLabel(variant), "missing",
                "PAGE_NUMBER_MISSING_IN_MAIN_BODY", Judgement.FAIL, section.source()));
          }
        }
      }
      if (bothParityFootersMissing) {
        results.add(result("PAGE-04", "实际显示的页脚含 PAGE 域",
            "默认和偶数页页脚均无 PAGE 域", "PAGE_NUMBER_MISSING_IN_MAIN_BODY",
            Judgement.FAIL, section.source()));
      }
    }
  }

  private void checkPageNumberSequence(List<CheckResult> results, ParsedDocument document,
      int firstBodySection, Counter evidence) {
    if (firstBodySection < 0) return;
    String renderedCandidates = unboundRenderedPageNumbers(document);
    boolean omittedTypeReported = false;
    for (int index = firstBodySection; index < document.sections().size(); index++) {
      SectionModel section = document.sections().get(index);
      // A known or invalid format proves pgNumType exists; without that
      // evidence, a missing start cannot be treated as inherited numbering.
      evidence.record(section.pageNumberStartInvalid()
          || (section.pageNumberStart() != null
              && (index == firstBodySection || section.pageNumberStart() == 1))
          || (index > firstBodySection && section.pageNumberStart() == null
              && (section.pageNumberFormat() != null || section.pageNumberFormatInvalid())));
      if (section.pageNumberStartInvalid()) {
        results.add(result("PAGE-04", "有效的页码起始整数", "invalid", "PAGE_NUMBER_START_INVALID",
            Judgement.FAIL, section.source()));
      } else if (section.pageNumberStart() != null) {
        if (index == firstBodySection && section.pageNumberStart() != 1) {
          results.add(result("PAGE-04", "1", String.valueOf(section.pageNumberStart()),
              "PAGE_NUMBER_START_MISMATCH", Judgement.FAIL, section.source()));
        } else if (index > firstBodySection && section.pageNumberStart() == 1) {
          results.add(result("PAGE-04", "continuous numbering", String.valueOf(section.pageNumberStart()),
              "PAGE_NUMBER_SEQUENCE_RESTARTED", Judgement.FAIL, section.source()));
        } else if (index > firstBodySection) {
          results.add(result("PAGE-04", "前一节页码连续", "起始值 " + section.pageNumberStart()
              + "，无法从 XML 得知前一节末页" + renderedCandidates,
              "PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED",
              Judgement.REVIEW, section.source()));
        }
      } else if (index > firstBodySection && section.pageNumberFormat() == null
          && !section.pageNumberFormatInvalid() && !omittedTypeReported
          && !renderedCandidates.isEmpty()) {
        // The visible digits are useful for manual review but cannot resolve
        // the missing OOXML numbering evidence for this or later sections.
        results.add(result("PAGE-04", "正文到附录页码连续",
            "后续分节没有明确的页码续排设置" + renderedCandidates,
            "PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED", Judgement.REVIEW, section.source()));
        omittedTypeReported = true;
      }
    }
  }

  private String unboundRenderedPageNumbers(ParsedDocument document) {
    if (renderedLayout == null || !renderedLayout.matchesSource(document.sourceSha256())) return "";
    var candidates = renderedLayout.pageBottomNumberCandidates();
    if (candidates.isEmpty()) return "";
    var descriptions = new ArrayList<String>();
    for (var candidate : candidates.stream().limit(3).toList()) {
      descriptions.add(String.format(Locale.ROOT, "PDF 第 %d 页页底数字候选 %s（x=%.1f pt，y=%.1f pt）",
          candidate.page(), candidate.visibleText(), candidate.x(), candidate.y()));
    }
    if (candidates.size() > 3) descriptions.add("另有 " + (candidates.size() - 3) + " 页候选");
    return "；" + String.join("、", descriptions) + "，未绑定到分节或 PAGE 域";
  }

  private void checkChapterBreaks(List<CheckResult> results,
      ParsedDocument document, Counter evidence) {
    for (HeadingBreakModel chapter : document.chapterBreaks()) {
      int number = chapterNumber(chapter.text());
      if (number <= 1 || isContentsChapter(chapter, document)) continue;
      if (chapter.explicitBreak()) {
        evidence.record(true);
        continue;
      }
      if (renderedLayout != null && renderedLayout.matchesSource(document.sourceSha256())) {
        String previous = previousSubstantiveParagraph(chapter, document);
        var layout = renderedLayout.chapterStart(chapter.text(), previous);
        if (layout.status() == RenderedLayoutEvidence.ChapterStatus.NEW_PAGE) {
          evidence.record(true);
          continue;
        }
        if (layout.status() == RenderedLayoutEvidence.ChapterStatus.SAME_PAGE) {
          evidence.record(true);
          results.add(result("PAGE-03", "第二章及以后另起一页",
              "第 " + layout.page() + " 页仍接在上一段之后", "CHAPTER_NOT_NEW_PAGE_RENDERED",
              Judgement.FAIL, chapter.source()));
          continue;
        }
      }
      evidence.record(false);
      // The absence of w:br/pageBreakBefore does not prove that the rendered
      // heading shares a page with the preceding content. Word can introduce
      // the break through styles, section boundaries, keep-with-next and
      // pagination. Keep this as a review item until a layout engine is used.
      results.add(result("PAGE-03", "第二章及以后章节设置分页", "XML 中没有明确的分页属性",
          "CHAPTER_PAGE_BREAK_REVIEW", Judgement.REVIEW, chapter.source()));
    }
  }

  private String previousSubstantiveParagraph(HeadingBreakModel chapter, ParsedDocument document) {
    // Without a complete object inventory, an unparsed table or formula may
    // have consumed the intervening page. The PDF paragraph match alone does
    // not prove that the chapter started immediately after that paragraph.
    if (!document.tableInventoryComplete() || !document.equationInventoryComplete()
        || !document.imageInventoryComplete()) return "";
    int index = -1;
    for (int i = 0; i < document.paragraphs().size(); i++) {
      SourceAddress source = document.paragraphs().get(i).source();
      if (source.partName().equals(chapter.source().partName())
          && source.elementPath().equals(chapter.source().elementPath())) {
        index = i;
        break;
      }
    }
    for (int i = index - 1; i >= 0; i--) {
      ParagraphModel paragraph = document.paragraphs().get(i);
      if (paragraph.insideTextBox() || regions.isInsideTable(paragraph, document.tables())) continue;
      String text = ManualLineBreakSupport.text(paragraph).strip();
      if (!text.isEmpty()) {
        return hasUnmappedContentBetween(paragraph.source(), chapter.source(), document) ? "" : text;
      }
    }
    return "";
  }

  private boolean hasUnmappedContentBetween(SourceAddress previous, SourceAddress chapter,
      ParsedDocument document) {
    // Paragraphs in tables/text boxes and non-paragraph objects can consume the
    // intervening page. Comparing the chapter only with the last ordinary
    // paragraph would then claim a new page even when it follows that object.
    return document.tables().stream().anyMatch(table -> between(table.source(), previous, chapter))
        || document.images().stream().anyMatch(image -> between(image.source(), previous, chapter))
        || document.equations().stream().anyMatch(equation -> between(equation.source(), previous, chapter))
        || document.paragraphs().stream().anyMatch(paragraph -> paragraph.insideTextBox()
            && between(paragraph.source(), previous, chapter));
  }

  private boolean between(SourceAddress object, SourceAddress previous, SourceAddress chapter) {
    return object != null && previous.partName().equals(object.partName())
        && chapter.partName().equals(object.partName())
        && isBeforeOrAt(previous, object) && isBeforeOrAt(object, chapter)
        && !isBeforeOrAt(chapter, object);
  }

  private boolean isContentsChapter(HeadingBreakModel chapter, ParsedDocument document) {
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (paragraph.source().partName().equals(chapter.source().partName())
          && paragraph.source().elementPath().equals(chapter.source().elementPath())) {
        return regions.isContentsEntry(paragraph, document);
      }
    }
    return false;
  }

  private static final class PageEvidence {
    private final Counter page03 = new Counter("PAGE-03");
    private final Counter page04 = new Counter("PAGE-04");
    private final Counter page05 = new Counter("PAGE-05");

    private void appendTo(List<RuleRun> runs, String moduleId) {
      for (Counter counter : List.of(page03, page04, page05)) {
        if (counter.candidates > 0) runs.add(counter.toRun(moduleId));
      }
    }
  }

  private static final class Counter {
    private final String ruleId;
    private int candidates;
    private int unknown;

    private Counter(String ruleId) { this.ruleId = ruleId; }

    private void record(boolean checked) {
      candidates++;
      if (!checked) unknown++;
    }

    private void markUnknown() {
      candidates = Math.max(candidates, 1);
      unknown = candidates;
    }

    private RuleRun toRun(String moduleId) {
      return new RuleRun(ruleId, moduleId, candidates, candidates - unknown, unknown, null);
    }
  }

  private List<CheckResult> checkMargins(SectionModel section, RuleSet rules) {
    String[] sides = {"上", "右", "下", "左"};
    Integer[] values = {section.topMarginTwips(), section.rightMarginTwips(),
        section.bottomMarginTwips(), section.leftMarginTwips()};
    boolean[] invalid = {section.topMarginEvidenceInvalid(), section.rightMarginEvidenceInvalid(),
        section.bottomMarginEvidenceInvalid(), section.leftMarginEvidenceInvalid()};
    var findings = new ArrayList<CheckResult>();
    for (int index = 0; index < values.length; index++) {
      Integer value = values[index];
      if (invalid[index]) {
        findings.add(result("PAGE-01", sides[index] + "边" + rules.expectedMarginTwips() + " twips",
            "invalid explicit value", "MARGIN_INVALID", Judgement.FAIL, section.source()));
      } else if (value == null) {
        findings.add(result("PAGE-01", sides[index] + "边 " + rules.expectedMarginTwips() + " twips", "unknown",
            "MARGIN_UNKNOWN", Judgement.REVIEW, section.source()));
      } else if (value != rules.expectedMarginTwips()) {
        findings.add(result("PAGE-01", sides[index] + "边 " + rules.expectedMarginTwips() + " twips",
            String.valueOf(value), "MARGIN_MISMATCH", Judgement.FAIL, section.source()));
      }
    }
    if (findings.isEmpty()) {
      findings.add(result("PAGE-01", String.valueOf(rules.expectedMarginTwips()), "all sides", "",
          Judgement.PASS, section.source()));
    }
    return List.copyOf(findings);
  }

  private int firstMainBodySectionIndex(ParsedDocument document) {
    ParagraphModel firstChapterParagraph = firstMainBodyChapter(document);
    SourceAddress firstChapter = firstChapterParagraph == null ? null : firstChapterParagraph.source();
    if (firstChapter == null || document.sections().isEmpty()) return -1;
    int index = 0;
    for (SectionModel section : document.sections()) {
      if (!section.terminal() && isBeforeOrAt(section.source(), firstChapter)
          && !isBeforeOrAt(firstChapter, section.source())) index++;
      else break;
    }
    return index < document.sections().size() ? index : -1;
  }

  private ParagraphModel firstMainBodyChapter(ParsedDocument document) {
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (regions.isInsideTable(paragraph, document.tables())
          || !regions.isBodyChapterStart(paragraph)
          || paragraph.generatedTocField()
          || regions.isContentsEntry(paragraph, document)) continue;
      return paragraph;
    }
    return null;
  }

  private int firstApplicableSectionIndex(cn.luwneshacha.thesis.document.PageNumberModel pageNumber,
      ParsedDocument document, int startInclusive, int endExclusive, boolean requireDefinite) {
    for (int index = Math.max(0, startInclusive); index < endExclusive && index < document.sections().size(); index++) {
      if (appliesToSection(pageNumber, index)
          && activeFooterVariants(document.sections().get(index)).contains(pageNumber.footerVariant())
          && (!requireDefinite || footerVisibility(document.sections().get(index),
              pageNumber.footerVariant()) == FooterVisibility.DEFINITE)) return index;
    }
    return -1;
  }

  private boolean appliesToSection(cn.luwneshacha.thesis.document.PageNumberModel pageNumber, int sectionIndex) {
    return pageNumber.applicableSectionIndexes().isEmpty()
        || pageNumber.applicableSectionIndexes().contains(sectionIndex);
  }

  private FooterPageEvidence.Presence footerPresence(ParsedDocument document, int sectionIndex,
      cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant variant) {
    return document.footerPageEvidence().stream()
        .filter(footer -> footer.sectionIndex() == sectionIndex && footer.variant() == variant)
        .map(FooterPageEvidence::presence).findFirst().orElse(FooterPageEvidence.Presence.UNKNOWN);
  }

  private List<cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant> activeFooterVariants(
      SectionModel section) {
    var variants = new ArrayList<cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant>();
    variants.add(cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.DEFAULT);
    if (section.firstPageHeaderEnabled())
      variants.add(cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.FIRST);
    if (section.evenPageHeaderEnabled())
      variants.add(cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.EVEN);
    return variants;
  }

  private enum FooterVisibility { DEFINITE, POSSIBLE }

  private FooterVisibility footerVisibility(SectionModel section,
      cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant variant) {
    if (section.firstPageHeaderEnabled())
      return variant == cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant.FIRST
          ? FooterVisibility.DEFINITE : FooterVisibility.POSSIBLE;
    // The first rendered page can use either parity after section starts and
    // pagination. XML does not prove which odd/even footer is displayed.
    if (section.evenPageHeaderEnabled()) return FooterVisibility.POSSIBLE;
    return FooterVisibility.DEFINITE;
  }

  private String pageFieldLabel(cn.luwneshacha.thesis.document.PageNumberModel.FooterVariant variant) {
    return switch (variant) {
      case DEFAULT -> "默认页脚中的 PAGE 域";
      case FIRST -> "首页页脚中的 PAGE 域";
      case EVEN -> "偶数页页脚中的 PAGE 域";
    };
  }

  private boolean isBeforeOrAt(SourceAddress leftAddress, SourceAddress rightAddress) {
    if (!leftAddress.partName().equals(rightAddress.partName())) return false;
    List<Integer> left = leftAddress.elementPath();
    List<Integer> right = rightAddress.elementPath();
    int common = Math.min(left.size(), right.size());
    for (int index = 0; index < common; index++) {
      if (!left.get(index).equals(right.get(index))) return left.get(index) < right.get(index);
    }
    return left.size() <= right.size();
  }

  private static int chapterNumber(String text) {
    Matcher matcher = CHAPTER_NUMBER.matcher(text == null ? "" : text);
    if (!matcher.find()) return 0;
    String number = matcher.group(1);
    try { return Integer.parseInt(number); } catch (NumberFormatException ignored) { }
    int total = 0, section = 0, digit = 0;
    for (int index = 0; index < number.length(); index++) {
      char value = number.charAt(index);
      int numeric = value == '〇' ? 0 : "零一二三四五六七八九".indexOf(value);
      if (numeric >= 0) { digit = numeric; continue; }
      int unit = value == '十' ? 10 : value == '百' ? 100 : value == '千' ? 1000 : 0;
      if (unit == 0) return 0;
      section += (digit == 0 ? 1 : digit) * unit;
      digit = 0;
      if (unit >= 100) { total += section; section = 0; }
    }
    return total + section + digit;
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
