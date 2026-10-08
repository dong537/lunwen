package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleEngine {
  private static final Pattern CITATION = Pattern.compile("\\[\\s*(\\d[^\\]]*)\\]");
  private static final Pattern CITATION_NUMBER = Pattern.compile("\\d+");
  private static final Pattern TABLE_OR_FIGURE_CAPTION = Pattern.compile("^(续表|表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern EQUATION_NUMBER = Pattern.compile("^\\(?((?:\\d+[\\-－]\\d+)|(?:[A-Z]\\d+))\\)?$");
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+)\\s*\\d+$");
  private static final Pattern CHAPTER_NUMBER = Pattern.compile("^第([0-9]+|[一二三四五六七八九十百千零〇]+)章");
  private static final Pattern FIGURE_NUMBER = Pattern.compile("图\\s*((?:\\d+[-－]\\d+)|(?:[A-Z]\\d+))[^\\d]?");
  private static final Pattern UNNUMBERED_FIGURE_REFERENCE = Pattern.compile("(?:如|见|参见)图(?!\\s*\\d)(?:所示|如下)?|下图所示");
  private static final Pattern TABLE_NUMBER = Pattern.compile("表\\s*((?:\\d+[-－]\\d+)|(?:[A-Z]\\d+))[^\\d]?");
  private static final Pattern FIGURE_CAPTION_FORMAT = Pattern.compile("^\\s*图\\s*(?:\\d+[-－]\\d+|[A-Z]\\d+)\\s+\\S.*");
  private static final Pattern TABLE_CAPTION_FORMAT = Pattern.compile("^\\s*表\\s*(?:\\d+[-－]\\d+|[A-Z]\\d+)\\s+\\S.*");
  private static final Pattern APPENDIX_HEADING = Pattern.compile("^(?:附录|Appendix)\\s*([A-Z])(?:\\s|$|[:：]).*", Pattern.CASE_INSENSITIVE);
  private static final Pattern COVER_FIELD = Pattern.compile("(学生姓名|姓名|学\\s*号|院\\s*系|学\\s*院|专业年级|专业|年级|指导教师|指导老师)\\s*[:：]?");
  private static final Pattern COVER_TITLE_LABEL = Pattern.compile("^(?:论文)?题\\s*目\\s*[:：]?");
  private static final Pattern COVER_DATE = Pattern.compile("^(?:19|20)\\d{2}(?:年\\d{1,2}月(?:\\d{1,2}日)?|[-/.]\\d{1,2}(?:[-/.]\\d{1,2})?)$");

  private static String normalizeCaption(String text) {
    return text == null ? null : text.replace('\u00a0', ' ').replace('\u3000', ' ').strip();
  }
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    for (SectionModel section : document.sections()) results.add(checkMargins(section, rules));
    checkCoverFormatting(results, document);
    checkAppendixFormatting(results, document);
    for (SectionModel section : document.sections()) {
      if (section.pageNumberFormat() != null && !"decimal".equalsIgnoreCase(section.pageNumberFormat()))
        results.add(review("PAGE-04", "decimal in main text", "PAGE_NUMBER_FORMAT_REVIEW", section.source()));
    }
    for (var chapter : document.chapterBreaks()) {
      if (chapterNumber(chapter.text()) > 1 && !chapter.explicitBreak())
        results.add(review("PAGE-03", "chapter starts on new page", "CHAPTER_PAGE_BREAK_REVIEW", chapter.source()));
    }
    for (var pageNumber : document.pageNumbers()) {
      if (pageNumber.centered() == null) results.add(review("PAGE-05", "center", "PAGE_NUMBER_ALIGNMENT_UNKNOWN", pageNumber.source()));
      else if (!pageNumber.centered()) results.add(result("PAGE-05", "center", "not centered", "PAGE_NUMBER_NOT_CENTERED", Judgement.FAIL, pageNumber.source()));
      if (pageNumber.asciiFont() == null) results.add(review("PAGE-05", "Times New Roman", "PAGE_NUMBER_FONT_UNKNOWN", pageNumber.source()));
      else if (!"Times New Roman".equals(pageNumber.asciiFont())) results.add(result("PAGE-05", "Times New Roman", pageNumber.asciiFont(), "PAGE_NUMBER_FONT_MISMATCH", Judgement.FAIL, pageNumber.source()));
      if (pageNumber.sizeHalfPoints() == null) results.add(review("PAGE-05", "21", "PAGE_NUMBER_SIZE_UNKNOWN", pageNumber.source()));
      else if (pageNumber.sizeHalfPoints() != 21) results.add(result("PAGE-05", "21", String.valueOf(pageNumber.sizeHalfPoints()), "PAGE_NUMBER_SIZE_MISMATCH", Judgement.FAIL, pageNumber.source()));
    }
    boolean inMainBody = false;
    boolean chapterNeedsBlankLine = false;
    boolean tocNeedsBlankLine = false;
    boolean inToc = false;
    int bodyChineseChars = 0;
    int bodyParagraphCount = 0;
    SourceAddress firstBodySource = null;
    String namedSectionBlankRule = null;
    var firstCitationSources = new LinkedHashMap<String, SourceAddress>();
    var unresolvedCitationGroups = new LinkedHashSet<SourceAddress>();
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      String normalizedText = text.replaceAll("\\s+", "");
      if (namedSectionBlankRule != null) {
        if (text.isBlank()) {
          namedSectionBlankRule = null;
        } else {
          results.add(result(namedSectionBlankRule + "-BLANK", "空一行", "未空一行",
              "SECTION_TITLE_BLANK_LINE_MISSING", Judgement.FAIL, paragraph.source()));
          namedSectionBlankRule = null;
        }
      }
      boolean namedSectionTitle = checkNamedSectionTitle(results, paragraph);
      if (namedSectionTitle) {
        if (normalizedText.equals("参考文献") || normalizedText.equalsIgnoreCase("references"))
          namedSectionBlankRule = "REF-01";
        else if (normalizedText.replaceAll("\\s+", "").equals("致谢")
            || normalizedText.equalsIgnoreCase("acknowledgments")
            || normalizedText.equalsIgnoreCase("acknowledgements"))
          namedSectionBlankRule = "END-01";
      }
      if ("目录".equals(normalizedText) || "CONTENTS".equalsIgnoreCase(normalizedText)) {
        inToc = true;
        tocNeedsBlankLine = true;
      }
      if (tocNeedsBlankLine && !text.isBlank() && !"目录".equals(normalizedText)
          && !"CONTENTS".equalsIgnoreCase(normalizedText)) {
        results.add(result("TOC-01-BLANK", "空一行", "未空一行", "TOC_TITLE_BLANK_LINE_MISSING",
            Judgement.FAIL, paragraph.source()));
        tocNeedsBlankLine = false;
      }
      if (tocNeedsBlankLine && text.isBlank()) tocNeedsBlankLine = false;
      if (chapterNeedsBlankLine && !text.isBlank()) {
        results.add(result("HEAD-02-BLANK", "空一行", "未空一行", "CHAPTER_BODY_BLANK_LINE_MISSING",
            Judgement.FAIL, paragraph.source()));
        chapterNeedsBlankLine = false;
      }
      if (chapterNeedsBlankLine && text.isBlank()) chapterNeedsBlankLine = false;
      if (isChapterHeading(text)) inMainBody = true;
      if (isChapterHeading(text)) chapterNeedsBlankLine = true;
      if (text.isBlank()) continue;
      if (inToc && TOC_ENTRY.matcher(text).matches()) {
        checkTocEntrySpacing(results, paragraph, text);
      } else if (inToc && (isChapterHeading(text) || isEndMatterHeading(text))) {
        inToc = false;
      }
      if (inMainBody && isEndMatterHeading(text)) inMainBody = false;
      int headingLevel = headingLevel(paragraph);
      if (headingLevel > 0 && !namedSectionTitle && !TOC_ENTRY.matcher(text).matches()) {
        for (RunModel run : paragraph.runs()) {
          if (run.text().isBlank()) continue;
          results.add(checkHeadingFont(run, headingLevel));
          results.add(checkHeadingSize(run, headingLevel));
        }
        if (headingLevel == 2) results.add(checkLineSpacing("HEADING-LINE", paragraph));
        results.add(checkHeadingNumberSpacing(paragraph, headingLevel, text));
        if (headingLevel == 3) results.add(checkHeadingIndent("HEAD-03-INDENT", paragraph, rules));
      }
      if (isLowerHeading(text) && !TOC_ENTRY.matcher(text).matches()) {
        for (RunModel run : paragraph.runs()) {
          if (run.text().isBlank()) continue;
          results.add(checkFont(run, rules, "HEAD-05-FONT"));
          results.add(checkSize(run, rules, "HEAD-05-SIZE"));
        }
        results.add(checkHeadingIndent("HEAD-05-INDENT", paragraph, rules));
        results.add(checkLineSpacing("HEAD-05-LINE", paragraph));
      }
      if (!inMainBody || !isBodyParagraph(paragraph) || isInsideTable(paragraph, document.tables())) continue;
      checkCitationFormatting(results, paragraph, firstCitationSources, unresolvedCitationGroups);
      results.add(checkIndent(paragraph, rules));
      bodyParagraphCount++;
      bodyChineseChars += text.codePoints().filter(Character::isIdeographic).count();
      if (firstBodySource == null) firstBodySource = paragraph.source();
      for (RunModel run : paragraph.runs()) {
        results.add(checkFont(run, rules));
        results.add(checkSize(run, rules));
      }
    }
    checkAbstractFormatting(results, document, rules);
    for (var reference : document.references()) {
      document.paragraphs().stream()
          .filter(paragraph -> paragraph.source().elementPath().equals(reference.source().elementPath()))
          .findFirst()
          .ifPresent(paragraph -> {
            for (RunModel run : paragraph.runs()) {
              results.add(checkFont(run, rules, "REF-FONT"));
              results.add(checkSize(run, rules, "REF-SIZE"));
            }
            results.add(checkReferenceLineSpacing(paragraph));
          });
    }
    if (bodyParagraphCount >= 10 && bodyChineseChars < 8000) {
      results.add(result("LENGTH-01", "正文不少于 8000 个汉字", String.valueOf(bodyChineseChars),
          "BODY_TOO_SHORT", Judgement.FAIL, firstBodySource));
    }
    for (var table : document.tables()) {
      if (table.captionText() == null) results.add(result("TAB-03", "表号和表题", null, "TABLE_CAPTION_MISSING", Judgement.FAIL, table.source()));
      else if (!table.captionBefore()) results.add(result("TAB-03", "caption above table", null, "TABLE_CAPTION_BELOW", Judgement.FAIL, table.source()));
      else if (table.layoutNeedsReview()) results.add(review("TAB-03", "caption above table", "TABLE_LAYOUT_REVIEW", table.source()));
      String normalizedCaption = normalizeCaption(table.captionText());
      if (normalizedCaption != null && !normalizedCaption.stripLeading().startsWith("续表")
          && !TABLE_CAPTION_FORMAT.matcher(normalizedCaption).matches())
        results.add(result("TAB-03", "表章号-序号 + 1 空格 + 表题", table.captionText(),
            "TABLE_CAPTION_NUMBER_FORMAT", Judgement.FAIL, table.captionSource()));
      results.addAll(new TableRuleChecker().check(document, table, rules));
      if (table.captionText() != null && table.captionText().stripLeading().startsWith("续表"))
        results.add(review("TAB-02", "three-line table", "TABLE_CONTINUATION_BORDER_REVIEW", table.source()));
      else if (!table.topBorder() || !table.middleBorder() || !table.bottomBorder())
        results.add(review("TAB-02", "three-line table", "TABLE_BORDER_REVIEW", table.source()));
      else if (table.verticalBorders())
        results.add(review("TAB-02", "three-line table", "TABLE_AUXILIARY_BORDER_REVIEW", table.source()));
      String tableNumber = tableNumberFromCaption(normalizedCaption);
      if (tableNumber != null && (normalizedCaption == null || !normalizedCaption.startsWith("续表"))) {
        SourceAddress referenceSource = table.captionSource() == null ? table.source() : table.captionSource();
        if (isTableReferencedInBody(document, tableNumber, table.captionSource())) {
          results.add(result("TAB-01", "body reference to table " + tableNumber, tableNumber, "",
              Judgement.PASS, referenceSource));
        } else {
          results.add(result("TAB-01", "body reference to table " + tableNumber, null,
              "TABLE_NOT_CITED_IN_BODY", Judgement.FAIL, referenceSource));
        }
      }
      captionParagraph(document, table.captionSource()).ifPresent(paragraph -> addCaptionResults(results, paragraph, rules));
    }
    for (var image : document.images()) {
      results.addAll(new ImageRuleChecker().check(document, image, rules));
      if (isBeforeMainBody(document, image.source()) || isCodeScreenshot(document, image)) continue;
      if (image.captionText() == null)
        results.add(review("FIG-02", "caption below and centered", "FIGURE_CAPTION_UNRESOLVED", image.source()));
      else {
        if (!FIGURE_CAPTION_FORMAT.matcher(image.captionText()).matches())
          results.add(result("FIG-03", "图章号-序号 + 1 空格 + 图题", image.captionText(),
              "FIGURE_CAPTION_NUMBER_FORMAT", Judgement.FAIL, image.captionSource()));
        Matcher figureNumber = FIGURE_NUMBER.matcher(image.captionText());
        if (appendixLetterAt(document, image.source()) != null) {
          // Appendix references are scoped separately from body references.
        } else if (!figureNumber.find())
          results.add(review("FIG-01", "body reference to figure", "FIGURE_NUMBER_UNKNOWN", image.source()));
        else if (isFigureReferencedInBody(document, figureNumber.group(1), image.captionSource()))
          results.add(result("FIG-01", "body reference to figure", figureNumber.group(1), "", Judgement.PASS, image.captionSource()));
        else if (hasUnnumberedFigureReference(document, image.captionSource()))
          results.add(review("FIG-01", "body reference to figure", "FIGURE_REFERENCE_NEEDS_REVIEW", image.captionSource()));
        else
          results.add(result("FIG-01", "body reference to figure", figureNumber.group(1),
              "FIGURE_NOT_CITED_IN_BODY", Judgement.FAIL, image.captionSource()));
      }
      captionParagraph(document, image.captionSource()).ifPresent(paragraph -> addCaptionResults(results, paragraph, rules));
      if (image.captionText() != null) {
        if (image.placementNeedsReview())
          results.add(review("FIG-02", "inline image and centered caption", "FIGURE_LAYOUT_REVIEW", image.source()));
        else if (!image.imageCentered() || !image.captionCentered())
          results.add(result("FIG-02", "centered image and caption",
              "image centered=" + image.imageCentered() + ", caption centered=" + image.captionCentered(),
              "FIGURE_NOT_CENTERED", Judgement.FAIL, image.captionSource()));
        else
          results.add(result("FIG-02", "inline image and centered caption", "verified from DOCX XML", "",
              Judgement.PASS, image.captionSource()));
      }
    }
    var equationNumbers = new HashSet<String>();
    var nextEquationByChapter = new java.util.HashMap<Integer, Integer>();
    for (var equation : document.equations()) {
      results.addAll(new EquationRuleChecker().check(document, equation, rules));
      if (equation.standalone() && equation.number() == null) results.add(result("EQ-01", "(3-1)", null, "EQUATION_NUMBER_MISSING", Judgement.FAIL, equation.source()));
      if (equation.standalone()) results.add(checkEquationVariables(document, equation));
      if (equation.number() != null) {
        if (!equationNumbers.add(equation.number()))
          results.add(result("EQ-05", "unique", equation.number(), "EQUATION_NUMBER_DUPLICATE", Judgement.FAIL, equation.source()));
        results.add(checkEquationNumberFormat(document, equation));
        Matcher sequence = Pattern.compile("(\\d+)[-－](\\d+)").matcher(equation.number());
        if (sequence.matches()) {
          int chapter = Integer.parseInt(sequence.group(1));
          int number = Integer.parseInt(sequence.group(2));
          int expected = nextEquationByChapter.getOrDefault(chapter, 1);
          if (number != expected)
            results.add(result("EQ-05", chapter + "-" + expected, equation.number(),
                "EQUATION_NUMBER_SEQUENCE_MISMATCH", Judgement.FAIL, equation.source()));
          nextEquationByChapter.put(chapter, Math.max(expected, number) + 1);
        }
        results.add(review("EQ-03", "equation centered; number right aligned", "EQUATION_ALIGNMENT_REVIEW", equation.source()));
        results.add(checkEquationReference(document, equation));
      }
      if (equation.needsReview()) results.add(review("EQ-06", equation.type(), "EQUATION_OBJECT_REVIEW", equation.source()));
    }
    var referenceNumbers = new HashSet<String>();
    if (!document.references().isEmpty() && document.references().size() < 10) {
      results.add(result("REF-05", "10", String.valueOf(document.references().size()), "REFERENCE_COUNT_TOO_LOW", Judgement.FAIL,
          document.references().get(0).source()));
    }
    for (var reference : document.references()) {
      if (!referenceNumbers.add(reference.number())) results.add(result("REF-03", "unique", reference.number(), "REFERENCE_NUMBER_DUPLICATE", Judgement.FAIL, reference.source()));
      if (reference.category() == null) results.add(result("REF-02", "M/J/D/C/N/R/S/P/Z/EB/OL", null, "REFERENCE_CATEGORY_MISSING", Judgement.FAIL, reference.source()));
    }
    results.addAll(new ReferenceRuleChecker().check(document, document.references(), firstCitationSources));
    checkReferenceCitations(results, document, firstCitationSources, unresolvedCitationGroups);
    return List.copyOf(results);
  }

  private void checkAppendixFormatting(List<CheckResult> results, ParsedDocument document) {
    char expectedHeading = 'A';
    for (ParagraphModel paragraph : document.paragraphs()) {
      String actual = appendixHeadingLetter(paragraph);
      if (actual == null) continue;
      results.add(result("APP-01", String.valueOf(expectedHeading), actual,
          expectedHeading == actual.charAt(0) ? "" : "APPENDIX_SEQUENCE_MISMATCH",
          expectedHeading == actual.charAt(0) ? Judgement.PASS : Judgement.FAIL, paragraph.source()));
      expectedHeading++;
    }
    for (TableModel table : document.tables()) {
      String appendix = appendixLetterAt(document,
          table.captionSource() == null ? table.source() : table.captionSource());
      if (appendix == null) continue;
      results.add(checkAppendixObjectNumber("表", appendix, table.captionText(), table.captionSource() == null
          ? table.source() : table.captionSource()));
    }
    for (ImageModel image : document.images()) {
      String appendix = appendixLetterAt(document, image.source());
      if (appendix == null) continue;
      results.add(checkAppendixObjectNumber("图", appendix, image.captionText(),
          image.captionSource() == null ? image.source() : image.captionSource()));
    }
    for (EquationModel equation : document.equations()) {
      if (!equation.standalone()) continue;
      String appendix = appendixLetterAt(document, equation.source());
      if (appendix == null) continue;
      String actualLetter = equation.number() != null && equation.number().matches("[A-Z]\\d+")
          ? equation.number().substring(0, 1) : null;
      results.add(appendixObjectResult("公式", appendix, actualLetter, equation.number(), equation.source()));
    }
  }

  private CheckResult checkAppendixObjectNumber(String label, String appendix, String caption, SourceAddress source) {
    if (caption == null) return appendixObjectResult(label, appendix, null, null, source);
    String prefix = "表".equals(label) ? "(?:续表|表)" : Pattern.quote(label);
    Matcher matcher = Pattern.compile("^\\s*" + prefix + "\\s*([A-Z])\\d+").matcher(caption);
    String actualLetter = matcher.find() ? matcher.group(1) : null;
    return appendixObjectResult(label, appendix, actualLetter, caption, source);
  }

  private CheckResult appendixObjectResult(String label, String expected, String actualLetter,
      String actual, SourceAddress source) {
    boolean matches = expected.equals(actualLetter);
    String reason = matches ? "" : actualLetter == null
        ? "APPENDIX_OBJECT_PREFIX_MISSING" : "APPENDIX_OBJECT_LETTER_MISMATCH";
    return result("APP-01", label + expected, actual, reason,
        matches ? Judgement.PASS : Judgement.FAIL, source);
  }

  private String appendixLetterAt(ParsedDocument document, SourceAddress source) {
    if (source == null) return null;
    List<ParagraphModel> paragraphs = document.paragraphs();
    int targetIndex = -1;
    List<Integer> sourcePath = source.elementPath();
    for (int index = 0; index < paragraphs.size(); index++) {
      List<Integer> paragraphPath = paragraphs.get(index).source().elementPath();
      if (paragraphPath.size() <= sourcePath.size()
          && sourcePath.subList(0, paragraphPath.size()).equals(paragraphPath)) {
        targetIndex = index;
        if (paragraphPath.equals(sourcePath)) break;
      }
    }
    if (targetIndex < 0) return null;
    String current = null;
    for (int index = 0; index <= targetIndex; index++) {
      String heading = appendixHeadingLetter(paragraphs.get(index));
      if (heading != null) current = heading;
    }
    return current;
  }

  private String appendixHeadingLetter(ParagraphModel paragraph) {
    String text = paragraphText(paragraph).strip();
    if (TOC_ENTRY.matcher(text).matches()
        || (paragraph.outlineLevel() != null && paragraph.outlineLevel() != 0)
        || (paragraph.outlineLevel() == null && text.matches(".*\\s+\\d+$"))) return null;
    Matcher matcher = APPENDIX_HEADING.matcher(text);
    return matcher.matches() ? matcher.group(1) : null;
  }

  private void checkCoverFormatting(List<CheckResult> results, ParsedDocument document) {
    boolean inCover = false;
    boolean titleChecked = false;
    boolean inTitleContinuation = false;
    String currentCoverFieldRule = null;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph).strip();
      String compact = text.replaceAll("\\s+", "");
      if (!inCover && compact.contains("本科生毕业设计（论文）")) inCover = true;
      if (!inCover) continue;
      if (compact.equals("摘要") || compact.equalsIgnoreCase("ABSTRACT")) break;
      if (compact.contains("本科生毕业设计（论文）")) {
        for (RunModel run : paragraph.runs()) {
          if (run.text().isBlank()) continue;
          results.add(checkCoverFont(run, "方正小标宋简体", "COVER-01-FONT"));
          results.add(checkExpectedSize(run, 72, "COVER-01-SIZE"));
        }
        results.add(checkCoverAlignment(paragraph, "COVER-01-ALIGNMENT"));
        continue;
      }
      Matcher field = COVER_FIELD.matcher(text);
      if (field.find() && field.start() == 0) {
        inTitleContinuation = false;
        currentCoverFieldRule = "COVER-03";
        int valueStart = field.end();
        if (!text.substring(valueStart).isBlank()) checkCoverFieldRuns(results, paragraph, valueStart, currentCoverFieldRule);
        continue;
      }
      if (COVER_DATE.matcher(compact).matches()) {
        currentCoverFieldRule = null;
        inTitleContinuation = false;
        checkCoverFieldRuns(results, paragraph, 0, "COVER-04");
        continue;
      }
      Matcher titleLabel = COVER_TITLE_LABEL.matcher(text);
      if (titleLabel.find()) {
        checkCoverTitleRuns(results, paragraph, titleLabel.end());
        titleChecked = true;
        inTitleContinuation = true;
        currentCoverFieldRule = null;
        continue;
      }
      if (currentCoverFieldRule != null && !text.isBlank()) {
        checkCoverFieldRuns(results, paragraph, 0, currentCoverFieldRule);
        continue;
      }
      if (!titleChecked && !text.isBlank()) {
        checkCoverTitleRuns(results, paragraph, 0);
        titleChecked = true;
        inTitleContinuation = true;
        continue;
      }
      if (inTitleContinuation && !text.isBlank()) {
        checkCoverTitleRuns(results, paragraph, 0);
      }
    }
  }

  private void checkCoverTitleRuns(List<CheckResult> results, ParagraphModel paragraph, int valueStart) {
    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int start = offset;
      int end = start + run.text().length();
      offset = end;
      if (end <= valueStart || run.text().isBlank()) continue;
      if (start < valueStart) {
        results.add(review("COVER-02-FONT", "黑体", "COVER_TITLE_LABEL_AND_VALUE_SHARE_RUN", paragraph.source()));
        results.add(review("COVER-02-SIZE", "36", "COVER_TITLE_LABEL_AND_VALUE_SHARE_RUN", paragraph.source()));
        return;
      }
      results.add(checkCoverFont(run, "黑体", "COVER-02-FONT"));
      results.add(checkExpectedSize(run, 36, "COVER-02-SIZE"));
    }
  }

  private void checkCoverFieldRuns(List<CheckResult> results, ParagraphModel paragraph, int valueStart, String ruleId) {
    int offset = 0;
    boolean found = false;
    for (RunModel run : paragraph.runs()) {
      int start = offset;
      int end = start + run.text().length();
      offset = end;
      if (end <= valueStart || run.text().isBlank()) continue;
      if (start < valueStart) {
        results.add(review(ruleId + "-FONT", "仿宋", "COVER_FIELD_LABEL_AND_VALUE_SHARE_RUN", paragraph.source()));
        results.add(review(ruleId + "-SIZE", "32", "COVER_FIELD_LABEL_AND_VALUE_SHARE_RUN", paragraph.source()));
        results.add(review(ruleId + "-BOLD", "true", "COVER_FIELD_LABEL_AND_VALUE_SHARE_RUN", paragraph.source()));
        return;
      }
      found = true;
      results.add(checkCoverFont(run, "仿宋", ruleId + "-FONT"));
      results.add(checkExpectedSize(run, 32, ruleId + "-SIZE"));
      if (run.bold() == null) results.add(review(ruleId + "-BOLD", "true", "BOLD_UNKNOWN", run.source()));
      else results.add(result(ruleId + "-BOLD", "true", String.valueOf(run.bold()), "",
          run.bold() ? Judgement.PASS : Judgement.FAIL, run.source()));
    }
    if (!found) results.add(review(ruleId + "-FONT", "仿宋", "COVER_FIELD_VALUE_UNKNOWN", paragraph.source()));
  }

  private CheckResult checkCoverFont(RunModel run, String expected, String ruleId) {
    if (run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
        && run.eastAsiaFont() == null)
      return review(ruleId, expected, "FONT_UNKNOWN", run.source());
    return result(ruleId, expected, run.eastAsiaFont(), "",
        expected.equals(run.eastAsiaFont()) ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private CheckResult checkCoverAlignment(ParagraphModel paragraph, String ruleId) {
    if (paragraph.alignment() == null) return review(ruleId, "center", "ALIGNMENT_UNKNOWN", paragraph.source());
    return result(ruleId, "center", paragraph.alignment(), "",
        "center".equalsIgnoreCase(paragraph.alignment()) ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private boolean checkNamedSectionTitle(List<CheckResult> results, ParagraphModel paragraph) {
    String rawText = paragraphText(paragraph).strip();
    String normalized = rawText.replaceAll("\\s+", "");
    String ruleId = switch (normalized.toUpperCase(java.util.Locale.ROOT)) {
      case "摘要" -> "ZH-01";
      case "ABSTRACT" -> "EN-01";
      case "目录", "CONTENTS" -> "TOC-01";
      case "参考文献", "REFERENCES" -> "REF-01";
      case "致谢", "ACKNOWLEDGMENTS", "ACKNOWLEDGEMENTS" -> "END-01";
      default -> normalized.startsWith("附录") || normalized.toUpperCase(java.util.Locale.ROOT).startsWith("APPENDIX")
          ? "END-01" : null;
    };
    if (ruleId == null) return false;
    if ("TOC-01".equals(ruleId)) {
      if (!rawText.matches("^目 {4}录$"))
        results.add(result("TOC-01-SPACING", "目  录（4 个空格）", rawText,
            "TOC_TITLE_SPACING_MISMATCH", Judgement.FAIL, paragraph.source()));
      results.add(checkLineSpacing("TOC-01-LINE", paragraph));
    }
    if ("END-01".equals(ruleId) && !rawText.matches("^致 {4}谢$")
        && !rawText.equalsIgnoreCase("ACKNOWLEDGMENTS")
        && !rawText.equalsIgnoreCase("ACKNOWLEDGEMENTS")) {
      results.add(result("END-01-SPACING", "致    谢（中间 4 个空格）", rawText,
          "ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH", Judgement.FAIL, paragraph.source()));
    }
    if (paragraph.alignment() == null)
      results.add(review(ruleId + "-ALIGNMENT", "center", "ALIGNMENT_UNKNOWN", paragraph.source()));
    else
      results.add(result(ruleId + "-ALIGNMENT", "center", paragraph.alignment(), "",
          "center".equalsIgnoreCase(paragraph.alignment()) ? Judgement.PASS : Judgement.FAIL, paragraph.source()));
    for (RunModel run : paragraph.runs()) {
      if (run.text().isBlank()) continue;
      results.add(checkHeadingFont(run, 1, ruleId + "-FONT"));
      results.add(checkExpectedSize(run, 32, ruleId + "-SIZE"));
      if ("EN-01".equals(ruleId)) {
        if (run.bold() == null) results.add(review("EN-01-BOLD", "true", "BOLD_UNKNOWN", run.source()));
        else results.add(result("EN-01-BOLD", "true", String.valueOf(run.bold()), "",
            run.bold() ? Judgement.PASS : Judgement.FAIL, run.source()));
      }
    }
    return true;
  }

  private CheckResult checkExpectedSize(RunModel run, int expectedHalfPoints, String ruleId) {
    if (run.fontSizeHalfPoints() == null)
      return review(ruleId, String.valueOf(expectedHalfPoints), "SIZE_UNKNOWN", run.source());
    return result(ruleId, String.valueOf(expectedHalfPoints), String.valueOf(run.fontSizeHalfPoints()), "",
        run.fontSizeHalfPoints() == expectedHalfPoints ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private void checkTocEntrySpacing(List<CheckResult> results, ParagraphModel paragraph, String text) {
    String compact = text.replaceAll("\\s+", "");
    String expected = compact.matches("^第.+章.*") ? "2" : "1";
    String prefix = compact.matches("^第.+章.*")
        ? compact.replaceFirst("^(第.+章).*", "$1")
        : compact.replaceFirst("^(\\d+(?:\\.\\d+){1,2}).*", "$1");
    int rawStart = text.indexOf(prefix);
    int after = rawStart < 0 ? -1 : rawStart + prefix.length();
    int spaces = 0;
    if (after >= 0) while (after + spaces < text.length() && Character.isWhitespace(text.charAt(after + spaces))) spaces++;
    if (spaces != Integer.parseInt(expected))
      results.add(result("TOC-SPACING", "编号后空 " + expected + " 格", String.valueOf(spaces),
          "TOC_NUMBER_SPACING_MISMATCH", Judgement.FAIL, paragraph.source()));
  }

  private CheckResult checkEquationNumberFormat(ParsedDocument document,
      EquationModel equation) {
    var paragraph = document.paragraphs().stream()
        .filter(candidate -> equation.source().elementPath().size() >= candidate.source().elementPath().size()
            && equation.source().elementPath().subList(0, candidate.source().elementPath().size())
                .equals(candidate.source().elementPath()))
        .findFirst().orElse(null);
    if (paragraph == null) return review("EQ-02", "五号 / Times New Roman", "EQUATION_NUMBER_FORMAT_UNKNOWN", equation.source());
    String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
    Matcher matcher = Pattern.compile("\\(" + Pattern.quote(equation.number()) + "\\)").matcher(text);
    if (!matcher.find()) return review("EQ-02", "五号 / Times New Roman", "EQUATION_NUMBER_FORMAT_UNKNOWN", equation.source());
    int start = matcher.start();
    int end = matcher.end();
    int offset = 0;
    boolean foundRun = false;
    for (RunModel run : paragraph.runs()) {
      int runStart = offset;
      int runEnd = offset + run.text().length();
      offset = runEnd;
      if (runEnd <= start || runStart >= end) continue;
      foundRun = true;
      if (run.asciiFont() == null || run.fontSizeHalfPoints() == null)
        return review("EQ-02", "五号 / Times New Roman", "EQUATION_NUMBER_FORMAT_UNKNOWN", run.source());
      if (!"Times New Roman".equalsIgnoreCase(run.asciiFont()) || run.fontSizeHalfPoints() != 20)
        return result("EQ-02", "五号 / Times New Roman",
            run.asciiFont() + " / " + run.fontSizeHalfPoints(), "EQUATION_NUMBER_FORMAT_MISMATCH",
            Judgement.FAIL, run.source());
    }
    if (!foundRun) return review("EQ-02", "五号 / Times New Roman", "EQUATION_NUMBER_FORMAT_UNKNOWN", equation.source());
    return result("EQ-02", "五号 / Times New Roman", "Times New Roman / 20", "", Judgement.PASS, equation.source());
  }

  private CheckResult checkEquationReference(ParsedDocument document, EquationModel equation) {
    String normalizedNumber = equation.number().replace('－', '-');
    Pattern reference = Pattern.compile("(?:公式|式)\\s*[（(]\\s*" + Pattern.quote(normalizedNumber) + "\\s*[）)]");
    String appendix = appendixLetterAt(document, equation.source());
    if (appendix != null) return checkAppendixEquationReference(document, equation, appendix, reference);
    boolean inMainBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph).replace('－', '-').replace('（', '(').replace('）', ')');
      if (isChapterHeading(text)) inMainBody = true;
      if (inMainBody && isEndMatterHeading(text)) inMainBody = false;
      if (!inMainBody || paragraph.source().elementPath().equals(equation.source().elementPath())
          || isInsideTable(paragraph, document.tables()) || TABLE_OR_FIGURE_CAPTION.matcher(text).find()) continue;
      if (reference.matcher(text).find())
        return result("EQ-04", "body reference to equation " + equation.number(), text, "",
            Judgement.PASS, paragraph.source());
    }
    return result("EQ-04", "body reference to equation " + equation.number(), null,
        "EQUATION_NOT_REFERENCED", Judgement.FAIL, equation.source());
  }

  private CheckResult checkEquationVariables(ParsedDocument document, EquationModel equation) {
    int equationParagraph = -1;
    for (int index = 0; index < document.paragraphs().size(); index++) {
      var source = document.paragraphs().get(index).source();
      if (equation.source().elementPath().size() >= source.elementPath().size()
          && equation.source().elementPath().subList(0, source.elementPath().size()).equals(source.elementPath())) {
        equationParagraph = index;
        break;
      }
    }
    if (equationParagraph >= 0 && equationParagraph + 1 < document.paragraphs().size()) {
      String next = paragraphText(document.paragraphs().get(equationParagraph + 1)).strip();
      if (next.matches("^(式中|其中)\\s*[：:].*"))
        return result("EQ-07", "变量含义和单位说明", next, "", Judgement.PASS,
            document.paragraphs().get(equationParagraph + 1).source());
    }
    return review("EQ-07", "variable definitions", "EQUATION_VARIABLES_REVIEW", equation.source());
  }

  private CheckResult checkAppendixEquationReference(ParsedDocument document, EquationModel equation,
      String appendix, Pattern reference) {
    boolean inAppendix = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph).replace('－', '-').replace('（', '(').replace('）', ')');
      String heading = appendixHeadingLetter(paragraph);
      if (heading != null) {
        inAppendix = appendix.equals(heading);
        continue;
      }
      if (!inAppendix || paragraph.source().elementPath().equals(equation.source().elementPath())
          || isInsideTable(paragraph, document.tables()) || TABLE_OR_FIGURE_CAPTION.matcher(text).find()) continue;
      if (reference.matcher(text).find())
        return result("EQ-04", "appendix " + appendix + " reference to equation " + equation.number(),
            text, "", Judgement.PASS, paragraph.source());
    }
    return result("EQ-04", "appendix " + appendix + " reference to equation " + equation.number(), null,
        "EQUATION_NOT_REFERENCED", Judgement.FAIL, equation.source());
  }

  private void checkCitationFormatting(List<CheckResult> results, ParagraphModel paragraph,
      Map<String, SourceAddress> firstCitationSources, Set<SourceAddress> unresolvedCitationGroups) {
    String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
    Matcher matcher = CITATION.matcher(text);
    boolean failed = false;
    boolean unknown = false;
    boolean found = false;
    while (matcher.find()) {
      if (!citationAtSentenceEnd(text, matcher.end())) continue;
      found = true;
      if (!addCitationNumbers(matcher.group(1), paragraph.source(), firstCitationSources)) {
        unresolvedCitationGroups.add(paragraph.source());
        unknown = true;
        continue;
      }
      int offset = 0;
      for (RunModel run : paragraph.runs()) {
        int end = offset + run.text().length();
        if (offset < matcher.end() && end > matcher.start()) {
          if (!"superscript".equalsIgnoreCase(run.verticalAlignment())) {
            failed = true;
          }
          if (run.asciiFont() == null || run.fontSizeHalfPoints() == null) unknown = true;
          else if (!"Times New Roman".equals(run.asciiFont()) || run.fontSizeHalfPoints() != 24) failed = true;
        }
        offset = end;
      }
    }
    if (!found) return;
    if (failed) results.add(result("CITE-01", "24 / Times New Roman / superscript", "format mismatch",
        "CITATION_FORMAT_MISMATCH", Judgement.FAIL, paragraph.source()));
    else if (unknown) results.add(review("CITE-01", "24 / Times New Roman / superscript",
        "CITATION_FORMAT_UNKNOWN", paragraph.source()));
    else results.add(result("CITE-01", "24 / Times New Roman / superscript", "matched",
        "", Judgement.PASS, paragraph.source()));
  }

  private boolean citationAtSentenceEnd(String text, int end) {
    int next = end;
    while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
    if (next == text.length() || text.charAt(next) == '[') return true;
    return "。！？!?；;,.，".indexOf(text.charAt(next)) >= 0;
  }

  private void checkReferenceCitations(List<CheckResult> results, ParsedDocument document,
      Map<String, SourceAddress> firstCitationSources, Set<SourceAddress> unresolvedCitationGroups) {
    if (!unresolvedCitationGroups.isEmpty()) {
      for (SourceAddress source : unresolvedCitationGroups)
        results.add(review("REF-03", "citation numbers and reference order", "CITATION_RANGE_REVIEW", source));
      return;
    }
    var referenceByNumber = new LinkedHashMap<String, ReferenceEntryModel>();
    for (var reference : document.references()) referenceByNumber.putIfAbsent(reference.number(), reference);
    var citedNumbers = new ArrayList<>(firstCitationSources.keySet());
    for (String number : citedNumbers) {
      if (!referenceByNumber.containsKey(number))
        results.add(result("REF-03", "matching reference entry", number, "CITED_REFERENCE_MISSING",
            Judgement.FAIL, firstCitationSources.get(number)));
    }
    for (int index = 0; index < document.references().size(); index++) {
      var reference = document.references().get(index);
      if (!firstCitationSources.containsKey(reference.number()))
        results.add(result("REF-03", "cited in body", reference.number(), "REFERENCE_NOT_CITED",
            Judgement.FAIL, reference.source()));
      if (index < citedNumbers.size() && !reference.number().equals(citedNumbers.get(index)))
        results.add(result("REF-03", citedNumbers.get(index), reference.number(), "REFERENCE_ORDER_MISMATCH",
            Judgement.FAIL, reference.source()));
    }
  }

  private boolean addCitationNumbers(String group, SourceAddress source,
      Map<String, SourceAddress> firstCitationSources) {
    Matcher numbers = CITATION_NUMBER.matcher(group);
    Integer previous = null;
    int previousEnd = 0;
    while (numbers.find()) {
      int current;
      try {
        current = Integer.parseInt(numbers.group());
      } catch (NumberFormatException invalidNumber) {
        return false;
      }
      if (previous == null) {
        firstCitationSources.putIfAbsent(String.valueOf(current), source);
      } else {
        String separator = group.substring(previousEnd, numbers.start());
        if (separator.contains("-") || separator.contains("–")) {
          if (current < previous || current - previous > 1000) return false;
          for (int value = previous + 1; value <= current; value++)
            firstCitationSources.putIfAbsent(String.valueOf(value), source);
        } else {
          if (!separator.matches("\\s*[,\\uFF0C\\u3001]\\s*")) return false;
          firstCitationSources.putIfAbsent(String.valueOf(current), source);
        }
      }
      previous = current;
      previousEnd = numbers.end();
    }
    return previous != null && group.substring(previousEnd).isBlank();
  }

  private static int chapterNumber(String text) {
    Matcher matcher = CHAPTER_NUMBER.matcher(text);
    if (!matcher.find()) return 0;
    String number = matcher.group(1);
    try { return Integer.parseInt(number); }
    catch (NumberFormatException ignored) { }
    int total = 0;
    int section = 0;
    int digit = 0;
    for (int i = 0; i < number.length(); i++) {
      char value = number.charAt(i);
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

  private String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
  }

  private int headingLevel(ParagraphModel paragraph) {
    String text = paragraphText(paragraph);
    if (TOC_ENTRY.matcher(text).matches()) return 0;
    if (paragraph.outlineLevel() != null && paragraph.outlineLevel() >= 0 && paragraph.outlineLevel() < 3)
      return paragraph.outlineLevel() + 1;
    if (isChapterHeading(text)) return 1;
    if (text.matches("^\\d+\\.\\d+\\.\\d+\\s*\\S.*")) return 3;
    if (text.matches("^\\d+\\.\\d+\\s*\\S.*")) return 2;
    return 0;
  }

  private CheckResult checkHeadingFont(RunModel run, int level) {
    return checkHeadingFont(run, level, "HEADING-FONT");
  }

  private CheckResult checkHeadingFont(RunModel run, int level, String ruleId) {
    boolean hasHan = run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    boolean hasLatin = run.text().codePoints().anyMatch(c -> Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    if ((hasHan && run.eastAsiaFont() == null) || (hasLatin && run.asciiFont() == null))
      return review(ruleId, "黑体/Times New Roman", "FONT_UNKNOWN", run.source());
    boolean matches = (!hasHan || "黑体".equals(run.eastAsiaFont())) && (!hasLatin || "Times New Roman".equals(run.asciiFont()));
    return result(ruleId, "黑体/Times New Roman", run.eastAsiaFont() + "/" + run.asciiFont(), "",
        matches ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private CheckResult checkHeadingSize(RunModel run, int level) {
    int expected = level == 1 ? 32 : level == 2 ? 28 : 24;
    if (run.fontSizeHalfPoints() == null) return review("HEADING-SIZE", String.valueOf(expected), "SIZE_UNKNOWN", run.source());
    return result("HEADING-SIZE", String.valueOf(expected), String.valueOf(run.fontSizeHalfPoints()), "",
        run.fontSizeHalfPoints() == expected ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private boolean isBodyParagraph(ParagraphModel paragraph) {
    String text = paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).trim();
    if (text.isBlank()) return false;
    if (TABLE_OR_FIGURE_CAPTION.matcher(text).find()) return false;
    if (EQUATION_NUMBER.matcher(text).matches()) return false;
    if (TOC_ENTRY.matcher(text).matches()) return false;
    return (paragraph.outlineLevel() == null || paragraph.outlineLevel() < 0) && !isSectionHeading(text);
  }

  private CheckResult checkHeadingNumberSpacing(ParagraphModel paragraph, int level, String text) {
    String compact = text.replaceAll("\\s+", "");
    String number = level == 1
        ? compact.replaceFirst("^(第.+章).*", "$1")
        : compact.replaceFirst("^(\\d+(?:\\.\\d+){" + (level - 1) + "}).*", "$1");
    int start = text.indexOf(number);
    int after = start < 0 ? -1 : start + number.length();
    int spaces = 0;
    if (after >= 0) while (after + spaces < text.length() && Character.isWhitespace(text.charAt(after + spaces))) spaces++;
    int expected = level == 1 ? 2 : 1;
    if (spaces != expected)
      return result(level == 1 ? "HEAD-01-SPACING" : "HEAD-0" + level + "-SPACING",
          "编号后空 " + expected + " 格", String.valueOf(spaces), "HEADING_NUMBER_SPACING_MISMATCH",
          Judgement.FAIL, paragraph.source());
    return result(level == 1 ? "HEAD-01-SPACING" : "HEAD-0" + level + "-SPACING",
        "编号后空 " + expected + " 格", String.valueOf(spaces), "", Judgement.PASS, paragraph.source());
  }

  private CheckResult checkHeadingIndent(String ruleId, ParagraphModel paragraph, RuleSet rules) {
    boolean matches = paragraph.firstLineChars() != null
        ? rules.expectedFirstLineChars() == paragraph.firstLineChars()
        : paragraph.firstLineIndentTwips() != null
            && paragraph.firstLineIndentTwips() == rules.expectedFirstLineIndentTwips();
    String actual = paragraph.firstLineChars() != null ? String.valueOf(paragraph.firstLineChars())
        : paragraph.firstLineIndentTwips() == null ? "unknown" : String.valueOf(paragraph.firstLineIndentTwips());
    return result(ruleId, String.valueOf(rules.expectedFirstLineChars()), actual,
        matches ? "" : "HEADING_INDENT_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private boolean isLowerHeading(String text) {
    return text.matches("^（\\d+）\\s+.{1,80}$") || text.matches("^\\(\\d+\\)\\s+.{1,80}$");
  }

  private boolean isChapterHeading(String text) {
    return text.matches("^第[0-9一二三四五六七八九十百千零〇]+章\\s+.*")
        && !TOC_ENTRY.matcher(text).matches();
  }

  private boolean isEndMatterHeading(String text) {
    String normalized = text.replaceAll("\\s+", "");
    if (normalized.equals("参考文献") || normalized.equalsIgnoreCase("References") || normalized.equals("致谢")
        || normalized.equalsIgnoreCase("Acknowledgments") || normalized.equalsIgnoreCase("Acknowledgements")) return true;
    return normalized.matches("^附录(?:[A-Za-z0-9一二三四五六七八九十百千零〇].*)?$")
        || normalized.matches("(?i)^Appendix(?:[A-Z].*)?$");
  }

  private boolean isInsideTable(ParagraphModel paragraph, List<TableModel> tables) {
    List<Integer> paragraphPath = paragraph.source().elementPath();
    return tables.stream().anyMatch(table -> {
      SourceAddress tableSource = table.source();
      List<Integer> tablePath = tableSource.elementPath();
      return tableSource.partName().equals(paragraph.source().partName())
          && paragraphPath.size() > tablePath.size()
          && paragraphPath.subList(0, tablePath.size()).equals(tablePath);
    });
  }

  private boolean isFigureReferencedInBody(ParsedDocument document, String targetNumber, SourceAddress captionSource) {
    return isLabelReferencedInBody(document, targetNumber, captionSource, FIGURE_NUMBER);
  }

  private boolean hasUnnumberedFigureReference(ParsedDocument document, SourceAddress captionSource) {
    boolean inMainBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      if (isChapterHeading(text)) inMainBody = true;
      if (inMainBody && isEndMatterHeading(text)) inMainBody = false;
      if (!inMainBody || (captionSource != null
          && paragraph.source().elementPath().equals(captionSource.elementPath()))) continue;
      if (UNNUMBERED_FIGURE_REFERENCE.matcher(text).find() && !FIGURE_NUMBER.matcher(text).find()) return true;
    }
    return false;
  }

  private boolean isTableReferencedInBody(ParsedDocument document, String targetNumber, SourceAddress captionSource) {
    return isLabelReferencedInBody(document, targetNumber, captionSource, TABLE_NUMBER);
  }

  private String tableNumberFromCaption(String caption) {
    if (caption == null) return null;
    Matcher matcher = Pattern.compile("^\\s*表\\s*((?:\\d+[-－]\\d+)|(?:[A-Z]\\d+))(?=$|[^\\d])").matcher(caption);
    return matcher.find() ? matcher.group(1).replace('－', '-') : null;
  }

  private boolean isCodeScreenshot(ParsedDocument document, ImageModel image) {
    List<Integer> imagePath = image.source().elementPath();
    if (imagePath.isEmpty()) return false;
    int imageIndex = imagePath.get(imagePath.size() - 1);
    List<Integer> parentPath = imagePath.subList(0, imagePath.size() - 1);
    for (ParagraphModel paragraph : document.paragraphs()) {
      List<Integer> path = paragraph.source().elementPath();
      if (!paragraph.source().partName().equals(image.source().partName()) || path.size() != imagePath.size()
          || !path.subList(0, path.size() - 1).equals(parentPath) || path.get(path.size() - 1) != imageIndex - 1)
        continue;
      return paragraphText(paragraph).matches("^核心代码(?:如下所示|如下)[。:：]$");
    }
    return false;
  }

  private boolean isBeforeMainBody(ParsedDocument document, SourceAddress source) {
    boolean inMainBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      if (isChapterHeading(text)) inMainBody = true;
      if (paragraph.source().partName().equals(source.partName())
          && paragraph.source().elementPath().equals(source.elementPath())) return !inMainBody;
    }
    return false;
  }

  private boolean isLabelReferencedInBody(ParsedDocument document, String targetNumber, SourceAddress captionSource,
      Pattern labelPattern) {
    boolean inMainBody = false;
    String normalizedTarget = targetNumber.replace('－', '-');
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      if (isChapterHeading(text)) inMainBody = true;
      if (inMainBody && isEndMatterHeading(text)) inMainBody = false;
      if (!inMainBody || (captionSource != null
          && paragraph.source().elementPath().equals(captionSource.elementPath()))
          || isInsideTable(paragraph, document.tables()) || TABLE_OR_FIGURE_CAPTION.matcher(text).find()) continue;
      Matcher reference = labelPattern.matcher(text);
      while (reference.find())
        if (normalizedTarget.equals(reference.group(1).replace('－', '-'))) return true;
    }
    return false;
  }

  private boolean isSectionHeading(String text) {
    return text.matches("^(摘要|Abstract|关键词|Keywords|目录|Contents|参考文献|References|致谢|Acknowledg(?:e)?ments)$")
        || text.matches("^第[一二三四五六七八九十百千零〇\\d]+章.*$")
        || text.matches("^\\d+(?:\\.\\d+){0,3}\\s*\\S.*$");
  }

  private CheckResult checkMargins(SectionModel section, RuleSet rules) {
    Integer[] values = {section.topMarginTwips(), section.rightMarginTwips(), section.bottomMarginTwips(), section.leftMarginTwips()};
    for (Integer value : values) {
      if (value == null) return review("PAGE-01", "margin", "MARGIN_UNKNOWN", section.source());
      if (value != rules.expectedMarginTwips()) return result("PAGE-01", String.valueOf(rules.expectedMarginTwips()), String.valueOf(value), "MARGIN_MISMATCH", Judgement.FAIL, section.source());
    }
    return result("PAGE-01", String.valueOf(rules.expectedMarginTwips()), "all sides", "", Judgement.PASS, section.source());
  }

  private CheckResult checkIndent(ParagraphModel paragraph, RuleSet rules) {
    return checkIndent(paragraph, rules, "BODY-INDENT");
  }

  private CheckResult checkIndent(ParagraphModel paragraph, RuleSet rules, String ruleId) {
    if (paragraph.firstLineChars() == null) {
      if (paragraph.firstLineIndentTwips() == null) return review(ruleId, "2 chars", "INDENT_UNKNOWN", paragraph.source());
      return result(ruleId, String.valueOf(rules.expectedFirstLineIndentTwips()),
          String.valueOf(paragraph.firstLineIndentTwips()), "",
          paragraph.firstLineIndentTwips() == rules.expectedFirstLineIndentTwips() ? Judgement.PASS : Judgement.FAIL,
          paragraph.source());
    }
    return result(ruleId, String.valueOf(rules.expectedFirstLineChars()), String.valueOf(paragraph.firstLineChars()), "", paragraph.firstLineChars() == rules.expectedFirstLineChars() ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private CheckResult checkFont(RunModel run, RuleSet rules) {
    return checkFont(run, rules, "BODY-FONT");
  }

  private CheckResult checkFont(RunModel run, RuleSet rules, String ruleId) {
    boolean hasHan = run.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    boolean hasLatin = run.text().codePoints().anyMatch(c -> Character.isLetterOrDigit(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    if ((hasHan && run.eastAsiaFont() == null) || (hasLatin && run.asciiFont() == null)) return review(ruleId, "font slots", "FONT_UNKNOWN", run.source());
    if ((hasHan && !rules.expectedEastAsiaFont().equals(run.eastAsiaFont())) || (hasLatin && !rules.expectedLatinFont().equals(run.asciiFont()))) {
      return result(ruleId, rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(), run.eastAsiaFont() + "/" + run.asciiFont(), "FONT_MISMATCH", Judgement.FAIL, run.source());
    }
    return result(ruleId, rules.expectedEastAsiaFont() + "/" + rules.expectedLatinFont(), run.eastAsiaFont() + "/" + run.asciiFont(), "", Judgement.PASS, run.source());
  }

  private CheckResult checkSize(RunModel run, RuleSet rules) {
    return checkSize(run, rules, "BODY-SIZE");
  }

  private CheckResult checkSize(RunModel run, RuleSet rules, String ruleId) {
    if (run.fontSizeHalfPoints() == null) return review(ruleId, String.valueOf(rules.expectedSizeHalfPoints()), "SIZE_UNKNOWN", run.source());
    return result(ruleId, String.valueOf(rules.expectedSizeHalfPoints()), String.valueOf(run.fontSizeHalfPoints()), "", run.fontSizeHalfPoints() == rules.expectedSizeHalfPoints() ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private void checkAbstractFormatting(List<CheckResult> results, ParsedDocument document, RuleSet rules) {
    String language = null;
    boolean needTitleBlank = false;
    boolean previousWasBlank = false;
    int chineseAbstractChars = 0;
    boolean chineseLengthReported = false;
    int englishAbstractWords = 0;
    boolean englishLengthReported = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      String normalized = text.replaceAll("\\s+", "");
      if (normalized.equals("摘要")) {
        language = "zh"; needTitleBlank = true; previousWasBlank = false; chineseAbstractChars = 0;
        if (!text.matches("^摘 {4}要$")) results.add(result("ZH-01-SPACING", "摘  要（4 个空格）", text,
            "ABSTRACT_TITLE_SPACING_MISMATCH", Judgement.FAIL, paragraph.source()));
        continue;
      }
      if (normalized.equalsIgnoreCase("ABSTRACT")) {
        language = "en"; needTitleBlank = true; previousWasBlank = false; englishAbstractWords = 0;
        continue;
      }
      if (language == null) continue;
      if (isEndMatterHeading(text) || isChapterHeading(text) || normalized.equals("目录") || normalized.equalsIgnoreCase("CONTENTS")) {
        language = null;
        continue;
      }
      if (text.isBlank()) { previousWasBlank = true; continue; }
      if (needTitleBlank) {
        results.add(result("zh".equals(language) ? "ZH-02-BLANK" : "EN-01-BLANK", "空一行", "未空一行",
            "ABSTRACT_TITLE_BLANK_LINE_MISSING", Judgement.FAIL, paragraph.source()));
        needTitleBlank = false;
      }
      boolean keywords = normalized.matches("^(关键词|keywords)[:：]?.*");
      if (keywords && !previousWasBlank)
        results.add(result("zh".equals(language) ? "ZH-04-BLANK" : "EN-03-BLANK", "空一行", "未空一行",
            "ABSTRACT_KEYWORD_BLANK_LINE_MISSING", Judgement.FAIL, paragraph.source()));
      for (RunModel run : paragraph.runs()) {
        results.add(checkFont(run, rules, "ABSTRACT-FONT"));
        results.add(checkSize(run, rules, "ABSTRACT-SIZE"));
      }
      results.add(checkIndent(paragraph, rules, "ABSTRACT-INDENT"));
      results.add(checkLineSpacing("ABSTRACT-LINE", paragraph));
      if (keywords) {
        if ("zh".equals(language)) {
          results.add(checkChineseKeywordLabelBold(paragraph));
          results.add(checkChineseKeywordFormat(paragraph, text));
        } else {
          results.add(checkEnglishKeywordFormat(paragraph, text));
        }
        language = null;
      } else if ("zh".equals(language)) {
        chineseAbstractChars += text.codePoints().filter(Character::isIdeographic).count();
        if (chineseAbstractChars > 300 && !chineseLengthReported) {
          results.add(result("ZH-06-LENGTH", "不超过 300 个汉字", String.valueOf(chineseAbstractChars),
              "ABSTRACT_TOO_LONG", Judgement.FAIL, paragraph.source()));
          chineseLengthReported = true;
        }
      } else if ("en".equals(language)) {
        englishAbstractWords += java.util.regex.Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)?").matcher(text)
            .results().mapToInt(match -> 1).sum();
        if (englishAbstractWords > 250 && !englishLengthReported) {
          results.add(result("EN-06-LENGTH", "不超过 250 个实词", String.valueOf(englishAbstractWords),
              "ABSTRACT_TOO_LONG", Judgement.FAIL, paragraph.source()));
          englishLengthReported = true;
        }
      }
      previousWasBlank = false;
    }
  }

  private CheckResult checkChineseKeywordLabelBold(ParagraphModel paragraph) {
    int labelLength = "关键词".length();
    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int end = offset + run.text().length();
      if (offset < labelLength && end > offset && !run.bold())
        return result("ABSTRACT-KEYWORD-BOLD", "true", "false", "KEYWORD_LABEL_NOT_BOLD", Judgement.FAIL, run.source());
      offset = end;
    }
    if (offset < labelLength) return review("ABSTRACT-KEYWORD-BOLD", "true", "KEYWORD_LABEL_UNREADABLE", paragraph.source());
    return result("ABSTRACT-KEYWORD-BOLD", "true", "true", "", Judgement.PASS, paragraph.source());
  }

  private CheckResult checkChineseKeywordFormat(ParagraphModel paragraph, String text) {
    String normalized = text.replaceAll("\\s+", "");
    if (!normalized.startsWith("关键词："))
      return result("ABSTRACT-KEYWORD-FORMAT", "关键词：词1；词2；词3", text,
          "KEYWORD_SEPARATOR_MISMATCH", Judgement.FAIL, paragraph.source());
    String list = normalized.substring("关键词：".length());
    String[] words = list.split("；", -1);
    boolean trailingPunctuation = list.matches(".*[。！？；，,.!?]$");
    boolean valid = words.length >= 3 && words.length <= 5
        && java.util.Arrays.stream(words).allMatch(word -> !word.isBlank())
        && !trailingPunctuation;
    return result("ABSTRACT-KEYWORD-FORMAT", "3-5 个关键词，用中文分号分隔且末尾无标点", text,
        valid ? "" : "KEYWORD_LIST_MISMATCH", valid ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private CheckResult checkEnglishKeywordFormat(ParagraphModel paragraph, String text) {
    String normalized = text.replaceAll("\\s+", " ").strip();
    if (!normalized.matches("^Keywords\\s*:\\s*[^;；]+(?:\\s*[;；]\\s*[^;；]+)+$"))
      return result("EN-03-FORMAT", "Keywords: word1; word2; word3", text,
          "ENGLISH_KEYWORD_SEPARATOR_MISMATCH", Judgement.FAIL, paragraph.source());
    return result("EN-03-FORMAT", "英文分号分隔关键词", text, "", Judgement.PASS, paragraph.source());
  }

  private CheckResult checkReferenceLineSpacing(ParagraphModel paragraph) {
    return checkLineSpacing("REF-LINE", paragraph);
  }

  private CheckResult checkLineSpacing(String ruleId, ParagraphModel paragraph) {
    if (paragraph.lineTwips() == null) return review(ruleId, "360", "LINE_SPACING_UNKNOWN", paragraph.source());
    String lineRule = paragraph.lineRule() == null ? "auto" : paragraph.lineRule();
    boolean matches = paragraph.lineTwips() == 360 && "auto".equalsIgnoreCase(lineRule);
    return result(ruleId, "360 / auto", paragraph.lineTwips() + " / " + lineRule, "",
        matches ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private void addCaptionResults(List<CheckResult> results, ParagraphModel paragraph, RuleSet rules) {
    for (RunModel run : paragraph.runs()) {
      results.add(checkFont(run, rules, "CAPTION-FONT"));
      results.add(checkCaptionSize(run));
    }
  }

  private CheckResult checkCaptionSize(RunModel run) {
    if (run.fontSizeHalfPoints() == null) return review("CAPTION-SIZE", "21", "SIZE_UNKNOWN", run.source());
    return result("CAPTION-SIZE", "21", String.valueOf(run.fontSizeHalfPoints()), "",
        run.fontSizeHalfPoints() == 21 ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private java.util.Optional<ParagraphModel> captionParagraph(ParsedDocument document, SourceAddress source) {
    if (source == null) return java.util.Optional.empty();
    return document.paragraphs().stream()
        .filter(paragraph -> paragraph.source().elementPath().equals(source.elementPath()))
        .findFirst();
  }

  private CheckResult review(String ruleId, String expected, String reason, SourceAddress source) {
    return result(ruleId, expected, null, reason, Judgement.REVIEW, source);
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason, Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED, judgement, expected, actual, reason, source);
  }
}
