package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the common body paragraph contract only inside the detected thesis body. */
final class BodyFormatRuleChecker implements RuleModule {
  private static final Pattern CITATION_MARKER = Pattern.compile(
      "\\[\\s*\\d+(?:\\s*[,\\uFF0C\\u3001;\\uFF1B\\-\\u2013\\u2014~\\uFF5E]\\s*\\d+)*\\s*\\]");
  private static final Pattern EQUATION = Pattern.compile("^\\(?((?:\\d+[\\-－]\\d+)|(?:[A-Z]\\d+))\\)?$");
  private static final Pattern CONTINUATION_MARKER = Pattern.compile("^续表(?:\\d+-\\d+|[A-Z]\\d+)$");
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$");
  private static final Pattern UNBOUND_CAPTION_NUMBER = Pattern.compile(
      "^(?:图|表|续表)\\s*(?:\\d|[A-Z])", Pattern.CASE_INSENSITIVE);
  private static final Pattern POSSIBLE_EMBEDDED_HEADING_SUFFIX = Pattern.compile(
      "(?<=[。！？!?；;\\r\\n])[\\t\\p{Zs}]*(?:\\d+(?:\\.\\d+){1,2})[\\t\\p{Zs}]*"
          + "[^\\r\\n。！？!?；;]+?[\\t\\p{Zs}]*$");
  private static final String NO_EMBEDDED_HEADING_IN_RESOLVED_BODY =
      "NO_EMBEDDED_HEADING_IN_RESOLVED_BODY";

  private final ParagraphFormatChecker formatChecker;
  private final HeadingRuleChecker headingRuleChecker;
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  BodyFormatRuleChecker(ParagraphFormatChecker formatChecker) {
    this.formatChecker = formatChecker;
    this.headingRuleChecker = new HeadingRuleChecker(formatChecker);
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    var embeddedResults = new ArrayList<CheckResult>();
    int paragraphCandidates = 0;
    int lineUnknown = 0;
    int indentUnknown = 0;
    int fontCandidates = 0;
    int fontUnknown = 0;
    int sizeUnknown = 0;
    int unboundCaptionCandidates = 0;
    int unresolvedEmbeddedSuffixes = 0;
    int embeddedCandidates = 0;
    int embeddedRunCandidates = 0;
    int secondarySpacingCandidates = 0;
    int tertiarySpacingCandidates = 0;
    Set<SourceAddress> captionLocations = captionLocations(document);
    var embeddedHeadings = new EmbeddedHeadingDetector(document);
    boolean inMainBody = false;
    boolean inContents = false;
    boolean bodyStartFound = false;
    boolean bodyParagraphChecked = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (paragraph.insideTextBox() || regions.hasDefiniteContentsEvidence(paragraph)) continue;
      String text = text(paragraph);
      String normalized = compact(text);
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      if (isVisualBlank(paragraph)) continue;
      if (!insideTable && (normalized.equals("目录") || normalized.equalsIgnoreCase("CONTENTS")))
        inContents = true;
      boolean tocEntry = inContents && isTocEntry(text);
      if (!insideTable && !tocEntry && regions.isBodyChapterStart(paragraph, document)) {
        inMainBody = true;
        bodyStartFound = true;
      }
      if (tocEntry) continue;
      if (!insideTable && inContents && (regions.isBodyChapterStart(paragraph, document)
          || regions.isEndMatterHeading(paragraph, document))) inContents = false;
      if (!insideTable && inMainBody && regions.isEndMatterHeading(paragraph, document)) inMainBody = false;
      if (!inMainBody || insideTable
          || captionLocations.contains(paragraphLocation(paragraph.source()))) continue;
      // A caption style is not proof that prose is a bound figure/table title.
      // Numbered caption-like text without an object binding has ambiguous identity.
      if (hasCaptionStyle(paragraph) && UNBOUND_CAPTION_NUMBER.matcher(text).find()) {
        unboundCaptionCandidates++;
        paragraphCandidates++;
        lineUnknown++;
        indentUnknown++;
        for (RunModel run : bodyTextRuns(paragraph, null)) {
          fontCandidates++;
          fontUnknown++;
          sizeUnknown++;
        }
        continue;
      }
      boolean possibleEmbeddedHeading = POSSIBLE_EMBEDDED_HEADING_SUFFIX
          .matcher(ManualLineBreakSupport.text(paragraph)).find();
      if (!isBodyParagraph(paragraph)) {
        if (possibleEmbeddedHeading) unresolvedEmbeddedSuffixes++;
        continue;
      }
      bodyParagraphChecked = true;
      paragraphCandidates++;
      CheckResult line = formatChecker.checkLineSpacing("BODY-LINE", paragraph);
      CheckResult indent = formatChecker.checkIndent(paragraph, rules, "BODY-INDENT");
      results.add(line);
      results.add(indent);
      if (isUnknown(line)) lineUnknown++;
      if (isUnknown(indent)) indentUnknown++;
      var embedded = embeddedHeadings.find(paragraph).orElse(null);
      for (RunModel run : bodyTextRuns(paragraph, embedded)) {
        fontCandidates++;
        CheckResult font = formatChecker.checkFont(run, rules, "BODY-FONT");
        CheckResult size = formatChecker.checkSize(run, rules, "BODY-SIZE");
        results.add(font);
        results.add(size);
        if (isUnknown(font)) fontUnknown++;
        if (isUnknown(size)) sizeUnknown++;
      }
      if (embedded != null) {
        embeddedCandidates++;
        CheckResult joined = new CheckResult("HEAD-EMBEDDED", Applicability.APPLICABLE,
            ExecutionStatus.EXECUTED,
            Judgement.FAIL, embedded.level() == 2 ? "二级标题独立成段" : "三级标题独立成段",
            embedded.title(), "HEADING_JOINED_TO_BODY", embedded.source());
        results.add(joined);
        embeddedResults.add(joined);
        List<RunModel> titleRuns = headingRuns(paragraph, embedded);
        embeddedRunCandidates += titleRuns.stream().filter(run -> !run.text().isBlank()).count();
        if (embedded.level() == 2) secondarySpacingCandidates++;
        else tertiarySpacingCandidates++;
        List<CheckResult> headingResults = headingRuleChecker.checkEmbeddedHeading(
            titleRuns, embedded.source(), embedded.level());
        results.addAll(headingResults);
        embeddedResults.addAll(headingResults);
      } else if (possibleEmbeddedHeading) {
        // A suffix that fails title/format corroboration cannot establish absence.
        unresolvedEmbeddedSuffixes++;
      }
    }
    boolean hasThesisSignal = hasFrontMatterSignal(document);
    if (hasThesisSignal && !bodyStartFound) {
      results.add(bodyScopeReview(document, "正文起点未能从章节证据确定"));
    } else if (hasThesisSignal && bodyStartFound && !bodyParagraphChecked) {
      results.add(bodyScopeReview(document, "已找到正文起点，但没有可检查的正文段落"));
    }
    var runs = new ArrayList<RuleRun>();
    addRun(runs, "BODY-LINE", paragraphCandidates, lineUnknown);
    addRun(runs, "BODY-INDENT", paragraphCandidates, indentUnknown);
    addRun(runs, "BODY-FONT", fontCandidates, fontUnknown);
    addRun(runs, "BODY-SIZE", fontCandidates, sizeUnknown);
    boolean embeddedAbsenceProved = bodyStartFound && bodyParagraphChecked
        && unboundCaptionCandidates == 0 && unresolvedEmbeddedSuffixes == 0;
    addEmbeddedRun(runs, "HEAD-EMBEDDED", embeddedCandidates, embeddedResults, embeddedAbsenceProved);
    addEmbeddedRun(runs, "HEADING-FONT", embeddedRunCandidates, embeddedResults,
        embeddedAbsenceProved);
    addEmbeddedRun(runs, "HEADING-SIZE", embeddedRunCandidates, embeddedResults,
        embeddedAbsenceProved);
    addEmbeddedRun(runs, "HEAD-02-SPACING", secondarySpacingCandidates, embeddedResults,
        embeddedAbsenceProved);
    addEmbeddedRun(runs, "HEAD-03-SPACING", tertiarySpacingCandidates, embeddedResults,
        embeddedAbsenceProved);
    // The document is one scope candidate only when chapter/front-matter evidence exists.
    // An unlocated or empty body remains unknown rather than becoming a false pass.
    if (hasThesisSignal || bodyStartFound) {
      runs.add(new RuleRun("BODY-SCOPE", getClass().getSimpleName(), 1,
          bodyStartFound && bodyParagraphChecked && unboundCaptionCandidates == 0 ? 1 : 0,
          bodyStartFound && bodyParagraphChecked && unboundCaptionCandidates == 0 ? 0 : 1, null));
    }
    return new Evaluation(List.copyOf(results), List.copyOf(runs));
  }

  private void addRun(List<RuleRun> runs, String ruleId, int candidates, int unknown) {
    // No body objects is not proof that the rule is inapplicable.
    if (candidates > 0)
      runs.add(new RuleRun(ruleId, getClass().getSimpleName(), candidates,
          candidates - unknown, unknown, null));
  }

  private void addEmbeddedRun(List<RuleRun> runs, String ruleId, int candidates,
      List<CheckResult> findings, boolean absenceProved) {
    if (candidates == 0) {
      if (absenceProved)
        runs.add(new RuleRun(ruleId, getClass().getSimpleName(), 0, 0, 0,
            NO_EMBEDDED_HEADING_IN_RESOLVED_BODY));
      return;
    }
    long checked = findings.stream().filter(item -> ruleId.equals(item.ruleId()))
        .filter(item -> !isUnknown(item)).count();
    if (checked > candidates)
      throw new IllegalStateException("embedded heading rule produced more findings than candidates: " + ruleId);
    runs.add(new RuleRun(ruleId, getClass().getSimpleName(), candidates,
        (int) checked, candidates - (int) checked, null));
  }

  private static boolean isUnknown(CheckResult result) {
    return result.judgement() == Judgement.REVIEW
        || result.applicability() == Applicability.UNKNOWN
        || result.executionStatus() != ExecutionStatus.EXECUTED;
  }

  private CheckResult bodyScopeReview(ParsedDocument document, String actual) {
    SourceAddress anchor = document.paragraphs().stream()
        .filter(paragraph -> !paragraph.insideTextBox())
        .filter(paragraph -> !regions.isInsideTable(paragraph, document.tables()))
        .filter(paragraph -> !text(paragraph).isBlank())
        .map(ParagraphModel::source).findFirst()
        .orElse(new SourceAddress("word/document.xml", List.of(), 0, 0));
    return new CheckResult("BODY-SCOPE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.REVIEW, "正文区域可由章节证据确定", actual, "BODY_REGION_UNDETERMINED", anchor);
  }

  private static List<RunModel> bodyTextRuns(ParagraphModel paragraph, EmbeddedHeadingDetector.Match heading) {
    String text = paragraph.runs().stream().map(run -> run.text() == null ? "" : run.text())
        .reduce("", String::concat);
    Matcher matcher = CITATION_MARKER.matcher(text);
    var citationRanges = new ArrayList<int[]>();
    while (matcher.find()) citationRanges.add(new int[] {matcher.start(), matcher.end()});
    if (heading != null) {
      citationRanges.add(new int[] {heading.start(), text.length()});
      citationRanges.sort(java.util.Comparator.comparingInt(range -> range[0]));
    }
    if (citationRanges.isEmpty()) return paragraph.runs();

    var bodyRuns = new ArrayList<RunModel>();
    int runStart = 0;
    for (RunModel run : paragraph.runs()) {
      String runText = run.text() == null ? "" : run.text();
      int runEnd = runStart + runText.length();
      int cursor = runStart;
      for (int[] range : citationRanges) {
        if (range[1] <= runStart) continue;
        if (range[0] >= runEnd) break;
        int bodyEnd = Math.min(range[0], runEnd);
        if (cursor < bodyEnd) bodyRuns.add(slice(run, cursor - runStart, bodyEnd - runStart));
        cursor = Math.max(cursor, Math.min(range[1], runEnd));
      }
      if (cursor < runEnd) bodyRuns.add(slice(run, cursor - runStart, runText.length()));
      runStart = runEnd;
    }
    return List.copyOf(bodyRuns);
  }

  private static List<RunModel> headingRuns(ParagraphModel paragraph, EmbeddedHeadingDetector.Match heading) {
    var runs = new ArrayList<RunModel>();
    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int length = run.text() == null ? 0 : run.text().length();
      int start = Math.max(heading.start(), offset);
      int end = Math.min(heading.end(), offset + length);
      if (start < end) runs.add(slice(run, start - offset, end - offset));
      offset += length;
    }
    return List.copyOf(runs);
  }

  private static RunModel slice(RunModel run, int start, int end) {
    SourceAddress source = run.source();
    SourceAddress sliceSource = source == null ? null : new SourceAddress(source.partName(),
        source.elementPath(), source.start() + start, source.start() + end);
    return new RunModel(run.text().substring(start, end), run.asciiFont(), run.highAnsiFont(),
        run.eastAsiaFont(), run.complexFont(), run.fontSizeHalfPoints(),
        run.complexFontSizeHalfPoints(), run.bold(), run.verticalAlignment(), sliceSource);
  }

  private boolean hasFrontMatterSignal(ParsedDocument document) {
    return document.paragraphs().stream()
        .filter(paragraph -> !paragraph.insideTextBox())
        .filter(paragraph -> !regions.isInsideTable(paragraph, document.tables()))
        .map(BodyFormatRuleChecker::text)
        .map(BodyFormatRuleChecker::compact)
        .anyMatch(value -> value.equals("摘要") || value.equalsIgnoreCase("ABSTRACT")
            || value.equals("目录") || value.equalsIgnoreCase("CONTENTS")
            || value.equals("参考文献") || value.equalsIgnoreCase("REFERENCES")
            || value.equals("致谢") || value.equalsIgnoreCase("ACKNOWLEDGMENTS")
            || value.equalsIgnoreCase("ACKNOWLEDGEMENTS") || value.equals("附录")
            || value.equalsIgnoreCase("APPENDIX")
            || value.equals("本科生毕业设计（论文）") || value.equals("本科生毕业设计(论文)"));
  }

  private static Set<SourceAddress> captionLocations(ParsedDocument document) {
    var locations = new HashSet<SourceAddress>();
    for (var image : document.images())
      if (image.captionSource() != null) locations.add(paragraphLocation(image.captionSource()));
    for (var table : document.tables())
      if (table.captionSource() != null) locations.add(paragraphLocation(table.captionSource()));
    return locations;
  }

  private static SourceAddress paragraphLocation(SourceAddress source) {
    // Object bindings identify the paragraph; their caption character ranges can differ.
    return new SourceAddress(source.partName(), source.elementPath(), 0, 0);
  }

  private static boolean hasCaptionStyle(ParagraphModel paragraph) {
    return isCaptionStyle(paragraph.paragraphStyleName())
        || isCaptionStyle(paragraph.paragraphStyleId());
  }

  private static boolean isCaptionStyle(String style) {
    return "Caption".equalsIgnoreCase(style) || "题注".equals(style);
  }

  private boolean isBodyParagraph(ParagraphModel paragraph) {
    String text = text(paragraph).strip();
    if (text.isBlank() || isSummaryTitle(text) || CONTINUATION_MARKER.matcher(text).matches()
        || EQUATION.matcher(text).matches() || TOC_ENTRY.matcher(text).matches()
        || regions.isBodyChapterStart(paragraph) || headingRuleChecker.isLowerHeading(text)) return false;
    return headingRuleChecker.headingLevel(paragraph) == 0 && !isNamedSectionHeading(text);
  }

  private static boolean isNamedSectionHeading(String text) {
    return text.matches("^(摘要|Abstract|关键词|Keywords|目录|Contents|参考文献|References|致谢|Acknowledg(?:e)?ments)$")
        || "结论与展望".equals(text);
  }

  private static boolean isTocEntry(String text) {
    return TOC_ENTRY.matcher(text).matches()
        || text.matches("^第[一二三四五六七八九十百千万零〇]+章\\s+.+\\d$")
        || text.matches("^\\d+(?:\\.\\d+){1,2}\\s+.+\\d$");
  }

  private static boolean isSummaryTitle(String text) {
    return compact(text).equals("总结");
  }

  private static boolean isVisualBlank(ParagraphModel paragraph) {
    return text(paragraph).codePoints().allMatch(codePoint -> Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint) || codePoint == 0x200B || codePoint == 0x200C
        || codePoint == 0x200D || codePoint == 0x2060 || codePoint == 0xFEFF);
  }

  private static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).strip();
  }

  private static String compact(String text) {
    return text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
        && !Character.isSpaceChar(codePoint) && codePoint != 0x200B && codePoint != 0x200C
        && codePoint != 0x200D && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
  }
}
