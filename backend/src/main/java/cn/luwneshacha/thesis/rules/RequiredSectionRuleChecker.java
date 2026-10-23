package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Checks that a document with thesis-level structure contains the required sections and cover fields. */
final class RequiredSectionRuleChecker implements RuleModule {
  private static final String SCHOOL_NAME = "山东石油化工学院";
  private static final Pattern CHAPTER = Pattern.compile("^第[0-9一二三四五六七八九十百千零〇]+章");
  private static final Pattern CONCLUSION_CHAPTER =
      Pattern.compile("^第([0-9一二三四五六七八九十百千零〇]+)章结论(?:与展望)?$");
  private static final Pattern COVER_FIELD = Pattern.compile("^(学生姓名|学号|院系|学院|专业年级|指导教师|指导老师)");
  private static final Pattern DATE = Pattern.compile("(?:19|20)\\d{2}(?:年\\d{1,2}月(?:\\d{1,2}日)?|[-/.]\\d{1,2}(?:[-/.]\\d{1,2})?)");
  private static final int MAX_UNCLASSIFIED_SHORT_DOCUMENT_PARAGRAPHS = 3;

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return check(document);
  }

  List<CheckResult> check(ParsedDocument document) {
    List<String> texts = document.paragraphs().stream().map(RequiredSectionRuleChecker::text).toList();
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    List<String> sectionTexts = new ArrayList<>(texts.size());
    for (ParagraphModel paragraph : document.paragraphs()) {
      sectionTexts.add(regions.isInsideTable(paragraph, document.tables()) ? "" : text(paragraph));
    }
    boolean hasChapter = document.paragraphs().stream()
        .anyMatch(paragraph -> isBodyChapterStart(regions, paragraph, document));
    int coverBoundary = firstStructuralBoundary(document, sectionTexts, regions);
    List<String> coverTexts = document.paragraphs().subList(0, coverBoundary).stream()
        .map(RequiredSectionRuleChecker::text)
        .toList();
    boolean hasSchool = coverTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> text.equals(SCHOOL_NAME)
            || text.startsWith("学校名称") && text.contains(SCHOOL_NAME));
    SourceAddress coverImage = coverImageSource(document, coverBoundary, regions);
    boolean hasCoverType = coverTexts.stream().map(RequiredSectionRuleChecker::compact)
        // The cover type is a standalone cover line. A body sentence may quote the
        // same phrase and must not activate cover/required-section validation.
        .anyMatch(text -> text.equals("本科生毕业设计（论文）") || text.equals("本科生毕业设计(论文)"));

    boolean hasChineseAbstract = sectionTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch("摘要"::equals);
    boolean hasChineseKeywords = sectionTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> text.startsWith("关键词：") || text.startsWith("关键词:"));
    boolean hasEnglishAbstract = sectionTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> "ABSTRACT".equalsIgnoreCase(text));
    boolean hasEnglishKeywords = sectionTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> text.matches("(?i)^KEYWORDS[:：].*"));
    boolean hasContents = sectionTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> "目录".equals(text) || "CONTENTS".equalsIgnoreCase(text));
    boolean hasReferences = sectionTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> "参考文献".equals(text) || "REFERENCES".equalsIgnoreCase(text));
    boolean hasAcknowledgements = sectionTexts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> "致谢".equals(text) || "ACKNOWLEDGEMENTS".equalsIgnoreCase(text)
            || "ACKNOWLEDGMENTS".equalsIgnoreCase(text));
    boolean hasAppendix = hasAppendixSection(document, sectionTexts);
    boolean hasAppendixTitle = hasMainAppendixTitle(document, sectionTexts);
    boolean hasConclusion = hasBodyConclusion(document, sectionTexts);

    int sectionSignals = count(hasChineseAbstract, hasChineseKeywords, hasEnglishAbstract,
        hasEnglishKeywords, hasContents, hasReferences, hasAcknowledgements);
    boolean hasCoverMarker = hasSchool || hasCoverType;
    boolean thesisDocument = hasChapter && (hasCoverMarker || sectionSignals >= 3)
        || hasCoverMarker && sectionSignals >= 3;
    if (!thesisDocument) {
      int nonEmptyParagraphs = (int) texts.stream().filter(text -> !text.isBlank()).count();
      boolean hasThesisSignal = hasChapter || hasCoverMarker || sectionSignals > 0;
      if (!hasThesisSignal && nonEmptyParagraphs > MAX_UNCLASSIFIED_SHORT_DOCUMENT_PARAGRAPHS) {
        return List.of();
      }
      SourceAddress reviewAnchor = document.paragraphs().stream()
          .filter(paragraph -> !regions.isInsideTable(paragraph, document.tables()))
          .filter(paragraph -> !text(paragraph).isBlank())
          .map(ParagraphModel::source).findFirst()
          .orElseGet(RequiredSectionRuleChecker::documentAnchor);
      return List.of(new CheckResult("SECTION-REQUIRED", Applicability.APPLICABLE,
          ExecutionStatus.EXECUTED, Judgement.REVIEW, "毕业论文结构识别", "结构线索不足",
          "THESIS_DOCUMENT_CLASSIFICATION_REVIEW", reviewAnchor));
    }

    SourceAddress anchor = document.paragraphs().stream()
        .filter(paragraph -> !regions.isInsideTable(paragraph, document.tables()))
        .filter(paragraph -> isBodyChapterStart(regions, paragraph, document))
        .map(ParagraphModel::source).findFirst()
        .orElseGet(() -> document.paragraphs().stream()
            .filter(paragraph -> !regions.isInsideTable(paragraph, document.tables()))
            .filter(paragraph -> !text(paragraph).isBlank())
            .map(ParagraphModel::source)
            .findFirst()
            .orElseGet(RequiredSectionRuleChecker::documentAnchor));
    var results = new ArrayList<CheckResult>();
    requireSection(results, "中文摘要", hasChineseAbstract, anchor);
    requireSection(results, "中文关键词", hasChineseKeywords, anchor);
    requireSection(results, "英文摘要", hasEnglishAbstract, anchor);
    requireSection(results, "英文关键词", hasEnglishKeywords, anchor);
    requireSection(results, "目录", hasContents, anchor);
    requireSection(results, "正文", hasChapter, anchor);
    requireSection(results, "参考文献", hasReferences, anchor);
    requireSection(results, "致谢", hasAcknowledgements, anchor);
    if (hasAppendixTitle && !hasAppendix) {
      results.add(fail("SECTION-REQUIRED", "附录分项", "未找到", "APPENDIX_SECTION_MISSING", anchor));
    }
    if (hasAppendix && !hasAppendixTitle) {
      results.add(fail("SECTION-REQUIRED", "附录总标题", "未找到", "APPENDIX_TITLE_MISSING", anchor));
    }
    requireSection(results, "结论", hasConclusion, anchor);
    checkCoverFields(results, coverTexts, anchor, hasSchool, hasCoverType, coverImage);
    return List.copyOf(results);
  }

  private static void checkCoverFields(List<CheckResult> results, List<String> texts, SourceAddress anchor,
      boolean hasSchool, boolean hasCoverType, SourceAddress coverImage) {
    if (!hasSchool && !hasCoverType) {
      results.add(fail("COVER-05", "封面信息", "未找到封面", "COVER_MISSING", anchor));
      return;
    }
    boolean hasStudentName = hasCoverValue(texts, "学生姓名");
    boolean hasStudentNumber = hasCoverValue(texts, "学号");
    boolean hasDepartment = hasCoverValue(texts, "院系", "学院");
    boolean hasMajor = hasCoverValue(texts, "专业年级");
    boolean hasAdvisor = hasCoverValue(texts, "指导教师", "指导老师");
    boolean hasTitle = hasCoverValue(texts, "论文题目", "题目");
    boolean hasDate = texts.stream().map(RequiredSectionRuleChecker::compact)
        .anyMatch(text -> DATE.matcher(text).matches());

    if (!hasSchool && coverImage != null) {
      results.add(new CheckResult("COVER-05", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
          Judgement.REVIEW, "学校名称", "封面含图片，图片文字无法从 DOCX XML 读取",
          "COVER_SCHOOL_NAME_IMAGE_UNCONFIRMED", coverImage));
    } else requireCoverField(results, "学校名称", hasSchool, anchor);
    requireCoverField(results, "论文类型", hasCoverType, anchor);
    requireCoverField(results, "学生姓名", hasStudentName, anchor);
    requireCoverField(results, "学号", hasStudentNumber, anchor);
    requireCoverField(results, "院系", hasDepartment, anchor);
    requireCoverField(results, "专业年级", hasMajor, anchor);
    requireCoverField(results, "指导教师", hasAdvisor, anchor);
    requireCoverField(results, "题目", hasTitle, anchor);
    requireCoverField(results, "日期", hasDate, anchor);
  }

  private static SourceAddress coverImageSource(ParsedDocument document, int coverBoundary,
      DocumentRegionClassifier regions) {
    for (int paragraphIndex = 0; paragraphIndex < coverBoundary; paragraphIndex++) {
      ParagraphModel paragraph = document.paragraphs().get(paragraphIndex);
      for (var image : document.images()) {
        SourceAddress source = image.source();
        if (source.partName().equals(paragraph.source().partName())
            && source.elementPath().equals(paragraph.source().elementPath())) return source;
      }
    }
    return null;
  }

  private static void requireSection(List<CheckResult> results, String name, boolean present,
      SourceAddress source) {
    if (!present) results.add(fail("SECTION-REQUIRED", name, "未找到", "REQUIRED_SECTION_MISSING", source));
  }

  private static int firstStructuralBoundary(ParsedDocument document, List<String> texts,
      DocumentRegionClassifier regions) {
    for (int index = 0; index < texts.size(); index++) {
      String value = compact(texts.get(index));
      if (value.equals("摘要") || value.equalsIgnoreCase("ABSTRACT")
          || value.equals("目录") || value.equalsIgnoreCase("CONTENTS")
          || value.equals("参考文献") || value.equalsIgnoreCase("REFERENCES")
          || value.equals("致谢") || value.equalsIgnoreCase("ACKNOWLEDGEMENTS")
          || value.equalsIgnoreCase("ACKNOWLEDGMENTS")
          || isBodyChapterStart(regions, document.paragraphs().get(index), document)) {
        return index;
      }
    }
    return texts.size();
  }

  private static boolean hasBodyConclusion(ParsedDocument document, List<String> texts) {
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    int firstChapter = firstBodyChapterIndex(document, regions);
    if (firstChapter < 0) return false;
    HeadingRuleChecker headingChecker = new HeadingRuleChecker(new ParagraphFormatChecker());
    for (int index = firstChapter + 1; index < texts.size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      if (regions.isInsideTable(paragraph, document.tables())) continue;
      String value = compact(texts.get(index));
      if (value.equals("参考文献") || value.equalsIgnoreCase("REFERENCES")) return false;
      if (CONCLUSION_CHAPTER.matcher(value).matches()) return true;
      if ((value.equals("结论") || value.equals("结论与展望"))
          && (regions.isAutomaticNumberedChapter(paragraph)
              || headingChecker.headingLevel(paragraph) == 1)) return true;
    }
    return false;
  }

  private static int firstBodyChapterIndex(ParsedDocument document, DocumentRegionClassifier regions) {
    for (int index = 0; index < document.paragraphs().size(); index++) {
      if (isBodyChapterStart(regions, document.paragraphs().get(index), document)) return index;
    }
    return -1;
  }

  private static boolean isBodyChapterStart(DocumentRegionClassifier regions, ParagraphModel paragraph,
      ParsedDocument document) {
    if (!regions.isBodyChapterStart(paragraph, document)) return false;
    String value = compact(text(paragraph));
    if ((value.equals("绪论") || value.equals("引言"))
        && !regions.isAutomaticNumberedChapter(paragraph)
        && new HeadingRuleChecker(new ParagraphFormatChecker()).headingLevel(paragraph) != 1) {
      return false;
    }
    return true;
  }

  private static boolean hasAppendixSection(ParsedDocument document, List<String> texts) {
    int lastRequiredEndMatter = -1;
    for (int index = 0; index < texts.size(); index++) {
      String value = compact(texts.get(index));
      if (value.equals("参考文献") || value.equalsIgnoreCase("REFERENCES")
          || value.equals("致谢") || value.equalsIgnoreCase("ACKNOWLEDGEMENTS")
          || value.equalsIgnoreCase("ACKNOWLEDGMENTS")) {
        lastRequiredEndMatter = index;
      }
    }
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    for (int index = lastRequiredEndMatter + 1; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      if (regions.appendixHeadingLetter(paragraph, document) != null) return true;
    }
    return false;
  }

  private static boolean hasMainAppendixTitle(ParsedDocument document, List<String> texts) {
    int lastRequiredEndMatter = -1;
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    for (int index = 0; index < texts.size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      if (regions.isInsideTable(paragraph, document.tables())) continue;
      String value = compact(texts.get(index));
      if (value.equals("参考文献") || value.equalsIgnoreCase("REFERENCES")
          || value.equals("致谢") || value.equalsIgnoreCase("ACKNOWLEDGEMENTS")
          || value.equalsIgnoreCase("ACKNOWLEDGMENTS")) {
        lastRequiredEndMatter = index;
      }
    }
    for (int index = lastRequiredEndMatter + 1; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      if (regions.isMainAppendixTitle(paragraph, document)) return true;
    }
    return false;
  }

  private static void requireCoverField(List<CheckResult> results, String name, boolean present,
      SourceAddress source) {
    if (!present) results.add(fail("COVER-05", name, "未找到", "COVER_FIELD_MISSING", source));
  }

  /** A cover label alone is not a completed field.  Word often stores the label
   * and its value in one run, so this check deliberately works on the compacted
   * visible text rather than relying on run boundaries. */
  private static boolean hasCoverValue(List<String> texts, String... labels) {
    for (String raw : texts) {
      String value = compact(raw);
      for (String label : labels) {
        if (!value.startsWith(label)) continue;
        String remainder = value.substring(label.length()).replaceFirst("^[：:]+", "");
        if (!remainder.isBlank()) return true;
      }
    }
    return false;
  }

  private static CheckResult fail(String ruleId, String expected, String actual, String reason,
      SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }

  private static SourceAddress documentAnchor() {
    return new SourceAddress("word/document.xml", List.of(), 0, 0);
  }

  private static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(run -> run.text()).reduce("", String::concat).strip();
  }

  private static String compact(String text) {
    return text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
            && codePoint != 0x200B && codePoint != 0x200C && codePoint != 0x200D
            && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static int count(boolean... values) {
    int count = 0;
    for (boolean value : values) if (value) count++;
    return count;
  }
}
