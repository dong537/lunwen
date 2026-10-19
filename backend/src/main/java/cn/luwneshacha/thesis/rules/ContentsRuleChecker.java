package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns contents-page title, entry spacing, and the required title gap. */
final class ContentsRuleChecker implements RuleModule {
  private static final String WHITESPACE = "[\\p{javaWhitespace}\\p{Z}\\u200B-\\u200D\\u2060\\uFEFF]";
  private static final Pattern ENTRY = Pattern.compile(".+(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$", Pattern.DOTALL);
  private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千万零〇]+章");
  private static final Pattern NUMERIC = Pattern.compile("^(\\d+(?:\\.\\d+){1,2})(.*)$", Pattern.DOTALL);
  private static final Pattern ENTRY_WITH_PAGE = Pattern.compile(
      "^(.*?)(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$", Pattern.DOTALL);
  private static final Pattern SIMPLE_ENTRY_WITH_PAGE = Pattern.compile("^(.*?)\\s+\\d+$", Pattern.DOTALL);
  private static final Pattern NUMERIC_PAGE = Pattern.compile(
      "(?s)^.*?(?:\\.{2,}|……+|\\t|[\\p{Zs}]+)\\s*(\\d+)$");

  private final ParagraphFormatChecker formatChecker;
  private final HeadingRuleChecker headingChecker;
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  ContentsRuleChecker(ParagraphFormatChecker formatChecker) {
    this.formatChecker = formatChecker;
    this.headingChecker = new HeadingRuleChecker(formatChecker);
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    var contentsEntries = new ArrayList<RelationItem>();
    var bodyHeadings = new ArrayList<RelationItem>();
    boolean inContents = false;
    boolean contentsSeen = false;
    SourceAddress contentsAnchor = null;
    for (int index = 0; index < document.paragraphs().size(); index++) {
      ParagraphModel paragraph = document.paragraphs().get(index);
      String raw = text(paragraph);
      String normalized = compact(raw);
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      if (!insideTable && (normalized.equals("目录") || normalized.equalsIgnoreCase("CONTENTS"))) {
        inContents = true;
        contentsSeen = true;
        if (contentsAnchor == null) contentsAnchor = paragraph.source();
        checkTitle(results, paragraph, normalized.equalsIgnoreCase("CONTENTS"));
        int next = index + 1;
        int blankLines = ManualLineBreakSupport.blankLinesAfter(paragraph);
        while (next < document.paragraphs().size()
            && isVisualBlank(document.paragraphs().get(next))) {
          blankLines += ManualLineBreakSupport.visualBlankLines(document.paragraphs().get(next));
          next++;
        }
        if (blankLines != 1) {
          SourceAddress source = blankLines == 0 && next < document.paragraphs().size()
              ? document.paragraphs().get(next).source() : paragraph.source();
          results.add(result("TOC-01-BLANK", "空一行",
              blankLines == 0 ? "未空一行" : String.valueOf(blankLines),
              blankLines == 0 ? "TOC_TITLE_BLANK_LINE_MISSING"
                  : "TOC_TITLE_BLANK_LINE_COUNT_MISMATCH",
              Judgement.FAIL, source));
        }
        continue;
      }
      if (isVisualBlank(paragraph)) continue;
      if (inContents && isPotentialEntry(document, index, paragraph, raw)) {
        checkEntryFormatting(results, paragraph, raw);
        relationItem(raw, paragraph, true).ifPresent(contentsEntries::add);
        continue;
      }
      if (inContents && !insideTable && isBodyHeading(paragraph, raw)) {
        inContents = false;
        relationItem(raw, paragraph, false).ifPresent(bodyHeadings::add);
      } else if (!inContents && contentsSeen && !insideTable && isBodyHeading(paragraph, raw)) {
        relationItem(raw, paragraph, false).ifPresent(bodyHeadings::add);
      }
      if (isEntry(paragraph, raw)) checkEntrySpacing(results, paragraph, raw);
    }
    checkHeadingCorrespondence(results, contentsEntries, bodyHeadings);
    if (contentsSeen) checkRequiredEntries(results, contentsEntries, contentsAnchor);
    return List.copyOf(results);
  }

  /** Checks the required landmarks on the contents page independently of body matching. */
  private void checkRequiredEntries(List<CheckResult> results,
      List<RelationItem> entries, SourceAddress anchor) {
    boolean chapter = entries.stream().anyMatch(item -> CHAPTER.matcher(item.key()).find());
    boolean references = entries.stream().anyMatch(item -> isContentsLandmark(item.key(), "参考文献"));
    boolean acknowledgement = entries.stream().anyMatch(item -> isContentsLandmark(item.key(), "致谢"));
    boolean appendix = entries.stream().anyMatch(item -> compact(item.key()).startsWith("附录"));
    checkRequiredEntry(results, entries, chapter, "正文各章", "TOC_REQUIRED_CHAPTER_MISSING", anchor);
    checkRequiredEntry(results, entries, references, "参考文献", "TOC_REQUIRED_REFERENCES_MISSING", anchor);
    checkRequiredEntry(results, entries, acknowledgement, "致谢", "TOC_REQUIRED_ACKNOWLEDGEMENT_MISSING", anchor);
    checkRequiredEntry(results, entries, appendix, "附录", "TOC_REQUIRED_APPENDIX_MISSING", anchor);
  }

  private void checkRequiredEntry(List<CheckResult> results, List<RelationItem> entries,
      boolean present, String expected, String missingReason, SourceAddress anchor) {
    if (!present) {
      results.add(result("TOC-REQUIRED-ENTRY", expected, "未找到", missingReason,
          Judgement.FAIL, anchor));
      return;
    }
    var matchingEntries = entries.stream().filter(item -> landmarkMatches(item.key(), expected)).toList();
    boolean everyEntryHasPage = !matchingEntries.isEmpty()
        && matchingEntries.stream().allMatch(RelationItem::pageNumberPresent);
    if (!everyEntryHasPage) {
      results.add(result("TOC-REQUIRED-ENTRY", expected + "及对应数字页码", "条目存在但没有数字页码",
          "TOC_REQUIRED_ENTRY_PAGE_MISSING", Judgement.FAIL, anchor));
    }
  }

  private boolean landmarkMatches(String key, String expected) {
    String value = compact(key);
    return "正文各章".equals(expected) ? CHAPTER.matcher(value).find()
        : isContentsLandmark(value, expected) || ("附录".equals(expected) && value.startsWith("附录"));
  }

  private boolean isContentsLandmark(String key, String expected) {
    String value = compact(key);
    return value.equals(expected) || value.equalsIgnoreCase(expected)
        || ("参考文献".equals(expected) && value.equalsIgnoreCase("REFERENCES"))
        || ("致谢".equals(expected)
            && (value.equalsIgnoreCase("ACKNOWLEDGMENTS") || value.equalsIgnoreCase("ACKNOWLEDGEMENT")));
  }

  /**
   * Compares the visible titles in the contents page with the headings after
   * the contents page.  Page numbers and formatting whitespace are removed,
   * but the actual title words and numbering remain part of the key.
   */
  private void checkHeadingCorrespondence(List<CheckResult> results,
      List<RelationItem> contentsEntries, List<RelationItem> bodyHeadings) {
    if (contentsEntries.isEmpty() || bodyHeadings.isEmpty()) return;

    var bodyCounts = new java.util.HashMap<String, Integer>();
    var contentsCounts = new java.util.HashMap<String, Integer>();
    bodyHeadings.forEach(item -> bodyCounts.merge(item.key(), 1, Integer::sum));
    contentsEntries.forEach(item -> contentsCounts.merge(item.key(), 1, Integer::sum));

    for (RelationItem item : contentsEntries) {
      int contentsCount = contentsCounts.getOrDefault(item.key(), 0);
      int bodyCount = bodyCounts.getOrDefault(item.key(), 0);
      if (contentsCount > bodyCount
          && contentsEntries.indexOf(item) >= occurrenceIndex(contentsEntries, item, bodyCount)) {
        results.add(result("TOC-02", "目录条目对应正文标题", item.key(),
            "TOC_ENTRY_NOT_FOUND_IN_BODY", Judgement.FAIL, item.source()));
      }
    }
    for (RelationItem item : bodyHeadings) {
      int bodyCount = bodyCounts.getOrDefault(item.key(), 0);
      int contentsCount = contentsCounts.getOrDefault(item.key(), 0);
      if (bodyCount > contentsCount
          && bodyHeadings.indexOf(item) >= occurrenceIndex(bodyHeadings, item, contentsCount)) {
        results.add(result("TOC-02", "正文标题对应目录条目", item.key(),
            "BODY_HEADING_NOT_IN_TOC", Judgement.FAIL, item.source()));
      }
    }

    if (bodyCounts.equals(contentsCounts) && contentsEntries.size() == bodyHeadings.size()) {
      for (int index = 0; index < contentsEntries.size(); index++) {
        RelationItem contents = contentsEntries.get(index);
        RelationItem body = bodyHeadings.get(index);
        if (!contents.key().equals(body.key())) {
          results.add(result("TOC-02-ORDER", "目录与正文标题顺序一致",
              contents.key() + " -> " + body.key(), "TOC_BODY_ORDER_MISMATCH", Judgement.FAIL,
              contents.source()));
          break;
        }
      }
    }
  }

  private static int occurrenceIndex(List<RelationItem> items, RelationItem target,
      int matchedCount) {
    int seen = 0;
    for (int index = 0; index < items.size(); index++) {
      if (!items.get(index).key().equals(target.key())) continue;
      seen++;
      if (seen > matchedCount) return index;
    }
    return items.indexOf(target);
  }

  private java.util.Optional<RelationItem> relationItem(String raw, ParagraphModel paragraph,
      boolean contentsEntry) {
    String title = contentsEntry ? removePageSuffix(raw) : raw.strip();
    String key = compact(title);
    if (key.isEmpty()) return java.util.Optional.empty();
    if (!contentsEntry && !isBodyHeading(paragraph, raw)) return java.util.Optional.empty();
    return java.util.Optional.of(new RelationItem(key, paragraph.source(),
        NUMERIC_PAGE.matcher(matchingText(raw).strip()).matches()));
  }

  private boolean isBodyHeading(ParagraphModel paragraph, String raw) {
    String normalized = compact(raw);
    return headingChecker.headingLevel(paragraph) > 0
        || isEndMatter(raw) || normalized.equals("绪论") || normalized.equals("引言");
  }

  private static String removePageSuffix(String raw) {
    String value = raw.strip();
    Matcher matcher = ENTRY_WITH_PAGE.matcher(value);
    if (matcher.matches()) return matcher.group(1).strip();
    matcher = SIMPLE_ENTRY_WITH_PAGE.matcher(value);
    return matcher.matches() ? matcher.group(1).strip() : value;
  }

  private record RelationItem(String key, SourceAddress source, boolean pageNumberPresent) { }

  private void checkTitle(List<CheckResult> results, ParagraphModel paragraph, boolean english) {
    String raw = ManualLineBreakSupport.stripBoundaryBreaks(text(paragraph));
    String ruleId = english ? "EN-TOC-01" : "TOC-01";
    if (!english && !raw.matches("^目 {4}录$")) {
      results.add(result("TOC-01-SPACING", "目    录（4 个普通空格）", raw,
          "TOC_TITLE_SPACING_MISMATCH", Judgement.FAIL, paragraph.source()));
    }
    if (paragraph.alignment() == null) {
      results.add(result(ruleId + "-ALIGNMENT", "center", "unknown", "ALIGNMENT_UNKNOWN",
          Judgement.REVIEW, paragraph.source()));
    } else {
      boolean matches = "center".equalsIgnoreCase(paragraph.alignment());
      results.add(result(ruleId + "-ALIGNMENT", "center", paragraph.alignment(),
          matches ? "" : "ALIGNMENT_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL,
          paragraph.source()));
    }
    for (RunModel run : paragraph.runs()) {
      if (run.text().isEmpty() || ManualLineBreakSupport.isBreakOnly(run.text())) continue;
      results.add(formatChecker.checkFont(run, "黑体", "Times New Roman", ruleId + "-FONT"));
      results.add(formatChecker.checkExpectedSize(run, 32, ruleId + "-SIZE"));
      if (english) {
        boolean bold = Boolean.TRUE.equals(run.bold());
        results.add(result("EN-TOC-01-BOLD", "true", String.valueOf(run.bold()),
            run.bold() == null ? "BOLD_UNKNOWN" : (bold ? "" : "BOLD_MISMATCH"),
            run.bold() == null ? Judgement.REVIEW : (bold ? Judgement.PASS : Judgement.FAIL), run.source()));
      }
    }
    results.add(formatChecker.checkLineSpacing(ruleId + "-LINE", paragraph));
  }

  private void checkEntrySpacing(List<CheckResult> results, ParagraphModel paragraph, String text) {
    String sourceText = text(paragraph);
    // Match the original characters: a line break must never turn into a valid space.
    String entryText = sourceText.stripLeading();
    int leadingOffset = sourceText.length() - entryText.length();
    Matcher chapter = CHAPTER.matcher(entryText);
    if (chapter.find()) {
      String separator = entryText.substring(chapter.end());
      if (!hasExactlyOrdinarySpaces(separator, 2)) {
        results.add(result("TOC-SPACING", "编号后空 2 格", describeSeparator(separator),
            "TOC_NUMBER_SPACING_MISMATCH", Judgement.FAIL,
            range(paragraph, sourceOffset(paragraph, leadingOffset + chapter.end()
                + invalidSeparatorOffset(separator, 2)), sourceOffset(paragraph, leadingOffset + chapter.end()
                    + invalidSeparatorEnd(separator, 2)))));
      }
      return;
    }
    Matcher malformed = Pattern.compile("^第[一二三四五六七八九十百千万零〇]*" + WHITESPACE
        + "+[一二三四五六七八九十百千万零〇]+章").matcher(entryText);
    if (malformed.find()) {
      int whitespace = firstWhitespaceIndex(malformed.group());
      results.add(result("TOC-SPACING", "章节序号内部不夹空格", malformed.group(),
          "TOC_NUMBER_SPACING_MISMATCH", Judgement.FAIL,
          range(paragraph, sourceOffset(paragraph, leadingOffset + whitespace),
              sourceOffset(paragraph, leadingOffset + whitespace + 1))));
      return;
    }
    Matcher malformedNumeric = DocumentRegionClassifier.NUMERIC_CONTENTS_WITH_INTERNAL_WHITESPACE.matcher(entryText);
    if (malformedNumeric.matches()
        && malformedNumeric.group(1).codePoints().anyMatch(ContentsRuleChecker::isSeparatorCodePoint)) {
      int whitespace = firstWhitespaceIndex(malformedNumeric.group(1));
      results.add(result("TOC-SPACING", "章节序号内部不夹空格", malformedNumeric.group(1),
          "TOC_NUMBER_SPACING_MISMATCH", Judgement.FAIL,
          range(paragraph, sourceOffset(paragraph, leadingOffset + whitespace),
              sourceOffset(paragraph, leadingOffset + whitespace + 1))));
      return;
    }
    Matcher numeric = NUMERIC.matcher(entryText);
    if (numeric.matches()) {
      String separator = numeric.group(2);
      if (!hasExactlyOrdinarySpaces(separator, 1)) {
        results.add(result("TOC-SPACING", "编号后空 1 格", describeSeparator(separator),
            "TOC_NUMBER_SPACING_MISMATCH", Judgement.FAIL,
            range(paragraph, sourceOffset(paragraph, leadingOffset + numeric.group(1).length()
                + invalidSeparatorOffset(separator, 1)), sourceOffset(paragraph, leadingOffset
                    + numeric.group(1).length() + invalidSeparatorEnd(separator, 1)))));
      }
    }
  }

  private static SourceAddress range(ParagraphModel paragraph, int start, int end) {
    SourceAddress source = paragraph.source();
    return new SourceAddress(source.partName(), source.elementPath(),
        source.start() + start, source.start() + end);
  }

  private static int sourceOffset(ParagraphModel paragraph, int matchingOffset) {
    String raw = text(paragraph);
    return Math.min(matchingOffset, raw.length());
  }

  private static int invalidSeparatorOffset(String separator, int expectedCount) {
    int index = 0;
    while (index < expectedCount && index < separator.length() && separator.charAt(index) == ' ') index++;
    return index < separator.length() && isSeparatorCodePoint(separator.codePointAt(index)) ? index : 0;
  }

  private static int invalidSeparatorEnd(String separator, int expectedCount) {
    int index = invalidSeparatorOffset(separator, expectedCount);
    while (index < separator.length()) {
      int codePoint = separator.codePointAt(index);
      if (!isSeparatorCodePoint(codePoint)) break;
      index += Character.charCount(codePoint);
    }
    return index;
  }

  private static int firstWhitespaceIndex(String text) {
    for (int index = 0; index < text.length();) {
      int codePoint = text.codePointAt(index);
      if (isSeparatorCodePoint(codePoint)) return index;
      index += Character.charCount(codePoint);
    }
    return 0;
  }

  private void checkEntryFormatting(List<CheckResult> results, ParagraphModel paragraph, String raw) {
    String matchingText = matchingText(raw);
    checkEntrySpacing(results, paragraph, matchingText);
    int level = tocLevel(paragraph, matchingText);
    for (RunModel run : paragraph.runs()) {
      if (run.text().isEmpty()) continue;
      results.add(formatChecker.checkFont(run, "宋体", "Times New Roman", "TOC-ENTRY-FONT"));
      results.add(formatChecker.checkExpectedSize(run, 24, "TOC-ENTRY-SIZE"));
    }
    results.add(formatChecker.checkLineSpacing("TOC-ENTRY-LINE", paragraph));
    // 二、三级目录允许首行或整段缩进；没有依据强制其中一种方式或数值。
    if (level == 1) results.add(checkFirstLevelEntryIndent(paragraph));
    Matcher page = NUMERIC_PAGE.matcher(matchingText.strip());
    if (!page.matches()) {
      String value = matchingText.strip();
      boolean hasPageDelimiter = value.matches(".*(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,}).+");
      results.add(result("TOC-ENTRY-PAGE", "数字页码", matchingText.strip(),
          hasPageDelimiter
              ? "TOC_PAGE_NUMBER_NON_NUMERIC" : "TOC_PAGE_NUMBER_MISSING",
          Judgement.FAIL, paragraph.source()));
    } else {
      results.add(result("TOC-ENTRY-PAGE", "数字页码", page.group(1), "", Judgement.PASS,
          paragraph.source()));
    }
  }

  private int tocLevel(ParagraphModel paragraph, String raw) {
    int styleLevel = regions.contentsStyleLevel(paragraph);
    if (styleLevel > 0) return styleLevel;
    String value = raw.stripLeading();
    if (CHAPTER.matcher(value).find()) return 1;
    Matcher numeric = DocumentRegionClassifier.NUMERIC_CONTENTS_WITH_INTERNAL_WHITESPACE.matcher(value);
    if (numeric.matches())
      return numeric.group(1).chars().filter(character -> character == '.').count() == 1 ? 2 : 3;
    return isEndMatter(removePageSuffix(value)) ? 1 : 0;
  }

  private CheckResult checkFirstLevelEntryIndent(ParagraphModel paragraph) {
    String expected = "leftChars=0 / left=0 twips";
    if (paragraph.leftEvidenceInvalid()) {
      return result("TOC-ENTRY-INDENT", expected, "左缩进属性冲突或无法读取",
          "INDENT_EVIDENCE_INVALID", Judgement.FAIL, paragraph.source());
    }
    if (paragraph.hangingEvidenceInvalid()) {
      return result("TOC-ENTRY-INDENT", expected, "悬挂缩进属性冲突或无法读取",
          "INDENT_EVIDENCE_INVALID", Judgement.FAIL, paragraph.source());
    }
    if (hasNonZero(paragraph.rightIndentTwips())
        || hasNonZero(paragraph.hangingIndentChars()) || hasNonZero(paragraph.hangingIndentTwips())) {
      return result("TOC-ENTRY-INDENT", expected, "存在额外的右侧或悬挂缩进",
          "INDENT_MISMATCH", Judgement.FAIL, paragraph.source());
    }
    if (hasLeadingTextWhitespace(paragraph)) {
      return result("TOC-ENTRY-INDENT", expected, "条目文字前有空白字符",
          "INDENT_MISMATCH", Judgement.FAIL, paragraph.source());
    }
    // 仅检查一级目录已有的顶格要求，不将首行缩进换算成整体左缩进。
    boolean characterEvidence = paragraph.leftIndentChars() != null;
    int actual = characterEvidence ? paragraph.leftIndentChars()
        : paragraph.leftIndentTwips() == null ? 0 : paragraph.leftIndentTwips();
    boolean matches = actual == 0;
    return result("TOC-ENTRY-INDENT", expected,
        characterEvidence ? "leftChars=" + actual : "left=" + actual + " twips",
        matches ? "" : "INDENT_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL, paragraph.source());
  }

  private boolean hasLeadingTextWhitespace(ParagraphModel paragraph) {
    for (RunModel run : paragraph.runs()) {
      String value = run.text() == null ? "" : run.text();
      for (int offset = 0; offset < value.length();) {
        int codePoint = value.codePointAt(offset);
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
            || codePoint == 0x2060 || codePoint == 0xFEFF;
      }
    }
    return false;
  }

  private boolean hasNonZero(Integer value) {
    return value != null && value != 0;
  }

  /**
   * A missing-page entry cannot be identified from a bare title alone once
   * the body starts.  TOC field paragraphs and paragraphs carrying the
   * standard toc font/size remain deterministic XML evidence; bold heading
   * paragraphs are left to the body-heading state machine.
   */
  private boolean isPotentialEntry(ParsedDocument document, int index, ParagraphModel paragraph,
      String raw) {
    String matchingText = matchingText(raw);
    if (isEntry(paragraph, matchingText)) return true;
    if (paragraph.generatedTocField()) return true;
    if (isContentsStyle(paragraph)) return true;
    String value = matchingText.stripLeading();
    if (regions.looksLikeLowerLevelContentsEntry(paragraph)) return true;
    if (looksLikeTocEntry(paragraph)
        && (CHAPTER.matcher(value).find()
            || isEndMatter(value))) return true;

    // A hand-written entry can be missing its page number and can itself have
    // the wrong font or size.  In that case formatting cannot identify it as a
    // TOC row.  Keep it in the TOC region only when the same visible title is
    // later confirmed as a real heading (style, outline, or automatic numbering
    // evidence).  This preserves the body boundary while still reporting the
    // malformed entry's font, size, indent, and page errors.
    if (CHAPTER.matcher(value).find() || NUMERIC.matcher(value).matches()
        || isEndMatter(value)) {
      return hasBodyHeadingCounterpart(document, index, value);
    }
    return false;
  }

  private boolean hasBodyHeadingCounterpart(ParsedDocument document, int index, String raw) {
    String expected = compact(raw);
    for (int candidateIndex = index + 1; candidateIndex < document.paragraphs().size(); candidateIndex++) {
      ParagraphModel candidate = document.paragraphs().get(candidateIndex);
      if (regions.isInsideTable(candidate, document.tables())) continue;
      if (!expected.equals(compact(text(candidate)))) continue;
      if (headingChecker.headingLevel(candidate) <= 0) continue;
      if (isContentsStyle(candidate) || candidate.generatedTocField()) continue;
      if (candidate.paragraphStyleId() != null
          && candidate.paragraphStyleId().matches("(?i)TOC[1-3]")) continue;
      return true;
    }
    return false;
  }

  private boolean isContentsStyle(ParagraphModel paragraph) {
    return regions.hasDefiniteContentsEvidence(paragraph);
  }

  private boolean looksLikeTocEntry(ParagraphModel paragraph) {
    return paragraph.runs().stream().filter(run -> !run.text().isBlank())
        .allMatch(run -> !Boolean.TRUE.equals(run.bold())
            && run.fontSizeHalfPoints() != null && run.fontSizeHalfPoints() == 24);
  }

  private static boolean isEntry(ParagraphModel paragraph, String text) {
    String matchingText = matchingText(text);
    return paragraph.generatedTocField() || ENTRY.matcher(matchingText).matches()
        || matchingText.matches("^第[一二三四五六七八九十百千万零〇]+章\\s+.+\\d$")
        || matchingText.matches("^\\d+(?:\\.\\d+){1,2}\\s+.+\\d$");
  }

  private static boolean isBodyChapter(String text) {
    String compact = compact(text);
    return CHAPTER.matcher(compact).find() && !ENTRY.matcher(text).matches();
  }

  private static boolean isEndMatter(String text) {
    String compact = compact(text);
    return compact.equals("摘要") || compact.equalsIgnoreCase("ABSTRACT")
        || compact.equals("参考文献") || compact.equals("致谢") || compact.equals("总结")
        || compact.equals("结论") || compact.startsWith("附录")
        || compact.equalsIgnoreCase("REFERENCES") || compact.equalsIgnoreCase("ACKNOWLEDGMENTS")
        || compact.equalsIgnoreCase("ACKNOWLEDGEMENTS");
  }

  private static boolean hasExactlyOrdinarySpaces(String text, int count) {
    if (!text.startsWith(" ".repeat(count))) return false;
    return text.length() == count || !isSeparatorCodePoint(text.codePointAt(count));
  }

  private static boolean isSeparatorCodePoint(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
        || codePoint == 0x2060 || codePoint == 0xFEFF;
  }

  private static String describeSeparator(String text) {
    var types = new ArrayList<String>();
    for (int index = 0; index < text.length();) {
      int codePoint = text.codePointAt(index);
      if (!isSeparatorCodePoint(codePoint)) break;
      String current = codePoint == 0x20 ? "普通空格"
          : codePoint == 0x09 ? "制表符"
          : codePoint == 0x3000 ? "全角空格"
          : codePoint == 0x200B ? "零宽空格"
          : codePoint == 0x200C ? "零宽不连字符"
          : codePoint == 0x200D ? "零宽连字符"
          : codePoint == 0x2060 ? "不可见连接符"
          : codePoint == 0xFEFF ? "零宽不换行字符" : "其他空白";
      types.add(current);
      index += Character.charCount(codePoint);
    }
    return types.isEmpty() ? "没有空格" : String.join("、", types);
  }

  private static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat);
  }

  private static String matchingText(String text) {
    return text.replace('\r', ' ');
  }

  private static String compact(String text) {
    return text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
        && !Character.isSpaceChar(codePoint) && codePoint != 0x200B && codePoint != 0x200C
        && codePoint != 0x200D && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
  }

  private static boolean isVisualBlank(ParagraphModel paragraph) {
    return ManualLineBreakSupport.visualBlankLines(paragraph) > 0;
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
