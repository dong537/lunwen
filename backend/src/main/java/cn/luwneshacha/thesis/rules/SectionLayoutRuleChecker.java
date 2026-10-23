package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.HeadingBreakModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$");
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
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
      checkChapterBreaks(results, document.chapterBreaks());
      return results;
    }
    checkPageNumberFormat(results, document, firstBodySection);
    checkPageNumberSequence(results, document, firstBodySection);
    checkPageNumberStyle(results, document, firstBodySection);
    checkMainBodyPageNumberPresence(results, document, firstBodySection);
    checkChapterBreaks(results, document.chapterBreaks());
    return results;
  }

  private void checkPageNumberFormat(List<CheckResult> results, ParsedDocument document, int firstBodySection) {
    for (int index = 0; index < document.sections().size(); index++) {
      SectionModel section = document.sections().get(index);
      boolean inBody = firstBodySection < 0 || index >= firstBodySection;
      if (!inBody) continue;
      if (section.pageNumberFormatInvalid()) {
        results.add(result("PAGE-04", "有效的页码格式", "invalid", "PAGE_NUMBER_FORMAT_INVALID",
            Judgement.FAIL, section.source()));
      } else if (section.pageNumberFormat() != null && !"decimal".equals(section.pageNumberFormat())) {
        results.add(result("PAGE-04", "decimal in main text", section.pageNumberFormat(),
            "PAGE_NUMBER_FORMAT_MISMATCH", Judgement.FAIL, section.source()));
      }
    }
  }

  private void checkPageNumberStyle(List<CheckResult> results, ParsedDocument document, int firstBodySection) {
    ParagraphModel firstChapter = firstMainBodyChapter(document);
    for (var pageNumber : document.pageNumbers()) {
      if (!pageNumber.pageField()) continue;
      int frontSectionIndex = firstApplicableSectionIndex(pageNumber, document, 0, firstBodySection);
      if (frontSectionIndex >= 0) {
        results.add(result("PAGE-04", "封面、摘要和目录不显示页码", "页脚中存在页码域",
            "PAGE_NUMBER_IN_FRONT_MATTER", Judgement.FAIL,
            document.sections().get(frontSectionIndex).source()));
      }
      int bodySectionIndex = firstBodySection < 0 ? -1
          : firstApplicableSectionIndex(pageNumber, document, firstBodySection, document.sections().size());
      if (firstBodySection >= 0 && bodySectionIndex < 0) continue;
      SourceAddress styleSource = firstBodySection >= 0 && firstChapter != null
          ? firstChapter.source() : pageNumber.source();
      if (pageNumber.centered() == null) {
        results.add(result("PAGE-05", "center", "unknown", "PAGE_NUMBER_ALIGNMENT_UNKNOWN", Judgement.REVIEW, styleSource));
      } else if (!pageNumber.centered()) {
        results.add(result("PAGE-05", "center", "not centered", "PAGE_NUMBER_NOT_CENTERED", Judgement.FAIL, styleSource));
      }
      for (var run : pageNumber.runFormatting()) {
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

  private void checkMainBodyPageNumberPresence(List<CheckResult> results, ParsedDocument document, int firstBodySection) {
    if (firstBodySection < 0 || firstBodySection >= document.sections().size()) return;
    for (int index = firstBodySection; index < document.sections().size(); index++) {
      int sectionIndex = index;
      SectionModel section = document.sections().get(index);
      for (var variant : activeFooterVariants(section)) {
        boolean hasPageField = document.pageNumbers().stream().anyMatch(pageNumber ->
            pageNumber.pageField() && pageNumber.footerVariant() == variant
                && appliesToSection(pageNumber, sectionIndex));
        if (!hasPageField) {
          results.add(result("PAGE-04", pageFieldLabel(variant), "missing",
              "PAGE_NUMBER_MISSING_IN_MAIN_BODY", Judgement.FAIL, section.source()));
        }
      }
    }
  }

  private void checkPageNumberSequence(List<CheckResult> results, ParsedDocument document, int firstBodySection) {
    if (firstBodySection < 0) return;
    for (int index = firstBodySection; index < document.sections().size(); index++) {
      SectionModel section = document.sections().get(index);
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
              + "，无法从 XML 得知前一节末页", "PAGE_NUMBER_SEQUENCE_CONTINUITY_UNCONFIRMED",
              Judgement.REVIEW, section.source()));
        }
      }
    }
  }

  private void checkChapterBreaks(List<CheckResult> results, List<HeadingBreakModel> chapters) {
    for (HeadingBreakModel chapter : chapters) {
      if (chapterNumber(chapter.text()) <= 1 || chapter.explicitBreak()) continue;
      // The absence of w:br/pageBreakBefore does not prove that the rendered
      // heading shares a page with the preceding content. Word can introduce
      // the break through styles, section boundaries, keep-with-next and
      // pagination. Keep this as a review item until a layout engine is used.
      results.add(result("PAGE-03", "第二章及以后章节设置分页", "XML 中没有明确的分页属性",
          "CHAPTER_PAGE_BREAK_REVIEW", Judgement.REVIEW, chapter.source()));
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
    boolean contentsSeen = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      String normalized = text.replaceAll("\\s+", "");
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      if (!insideTable && (normalized.equals("目录") || normalized.equalsIgnoreCase("CONTENTS"))) {
        contentsSeen = true;
        continue;
      }
      if (insideTable || !regions.isBodyChapterStart(paragraph)
          || paragraph.generatedTocField()) continue;
      if (contentsSeen && isTocChapterEntry(text)) continue;
      return paragraph;
    }
    return null;
  }

  private boolean isTocChapterEntry(String text) {
    return TOC_ENTRY.matcher(text).matches() || text.matches(".*(?:\\t|\\s{2,})\\s*\\d+$");
  }

  private int firstApplicableSectionIndex(cn.luwneshacha.thesis.document.PageNumberModel pageNumber,
      ParsedDocument document, int startInclusive, int endExclusive) {
    for (int index = Math.max(0, startInclusive); index < endExclusive && index < document.sections().size(); index++) {
      if (appliesToSection(pageNumber, index)
          && activeFooterVariants(document.sections().get(index)).contains(pageNumber.footerVariant())) return index;
    }
    return -1;
  }

  private boolean appliesToSection(cn.luwneshacha.thesis.document.PageNumberModel pageNumber, int sectionIndex) {
    return pageNumber.applicableSectionIndexes().isEmpty()
        || pageNumber.applicableSectionIndexes().contains(sectionIndex);
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

  private String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(run -> run.text()).reduce("", String::concat).trim();
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
