package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns thesis-table caption, border, citation, sequence, and continuation checks. */
final class TableRuleModule implements RuleModule {
  private static final Pattern TABLE_OR_FIGURE_CAPTION = Pattern.compile(
      "^(续表|表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern TABLE_NUMBER = Pattern.compile(
      "表[\\s\\p{Zs}]*((?:\\d+[\\s\\p{Zs}]*[-－][\\s\\p{Zs}]*\\d+)|(?:[A-Z]\\d+))(?![A-Za-z\\d-])");
  private static final Pattern TABLE_CAPTION_FORMAT = Pattern.compile("^表(?:\\d+[-]\\d+|[A-Z]\\d+) \\S.*");
  private static final Pattern CONTINUATION_CAPTION_FORMAT = Pattern.compile("^续表(\\d+-\\d+|[A-Z]\\d+)$");
  // Sequence validation still sees the number when caption-spacing validation
  // reports extra or missing spaces. Malformed extra numeric suffixes remain excluded.
  private static final Pattern TABLE_SEQUENCE = Pattern.compile(
      "^表(\\d+)-(\\d+)(?=[^\\d-－]|$).*");

  private final TableRuleChecker tableChecker = new TableRuleChecker();
  private final ParagraphFormatChecker paragraphFormatChecker = new ParagraphFormatChecker();
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    for (TableModel table : document.tables()) {
      if (isSplitContinuationHeaderFragment(document, table)
          || isBeforeMainBodyTable(document, table)
          || isCodeListingTable(table)
          || isEquationLayoutTable(table, document.equations())) continue;
      checkTable(results, document, table, rules);
    }
    checkTableNumberSequences(results, document);
    return List.copyOf(results);
  }

  private void checkTable(List<CheckResult> results, ParsedDocument document,
      TableModel table, RuleSet rules) {
    String captionText = table.captionText();
    if (captionText == null) {
      results.add(result("TAB-03", "表号和表题", null, "TABLE_CAPTION_MISSING", Judgement.FAIL,
          table.source()));
    } else if (!table.captionBefore()) {
      results.add(result("TAB-03", "caption above table", null, "TABLE_CAPTION_BELOW", Judgement.FAIL,
          table.source()));
    } else if (table.layoutNeedsReview()) {
      results.add(result("TAB-03", "caption above table", null, "TABLE_LAYOUT_REVIEW", Judgement.REVIEW,
          table.source()));
    }

    String normalizedCaption = normalizeCaption(captionText);
    if (captionText != null && captionText.stripLeading().startsWith("续表")
        && !CONTINUATION_CAPTION_FORMAT.matcher(captionText).matches()) {
      results.add(result("TAB-03", "续表+编号", captionText,
          "TABLE_CONTINUATION_NUMBER_FORMAT", Judgement.FAIL, table.captionSource()));
    } else if (captionText != null && !captionText.stripLeading().startsWith("续表")
        && !TABLE_CAPTION_FORMAT.matcher(captionText).matches()) {
      results.add(result("TAB-03", "表章号-序号 + 1 空格 + 表题", captionText,
          "TABLE_CAPTION_NUMBER_FORMAT", Judgement.FAIL, table.captionSource()));
    }

    results.addAll(tableChecker.check(document, table, rules));
    checkBorders(results, table, captionText);
    checkBodyCitation(results, document, table, captionText, normalizedCaption);
    captionParagraph(document, table.captionSource())
        .ifPresent(paragraph -> checkCaptionParagraph(results, paragraph, captionText, rules));
  }

  private void checkBorders(List<CheckResult> results, TableModel table, String captionText) {
    if (table.hasTableStyle() && !table.tableStyleBorderEvidenceResolved()) {
      results.add(result("TAB-02", "verifiable three-line borders", "table style border evidence is unresolved",
          "TABLE_BORDER_EVIDENCE_UNRESOLVED", Judgement.REVIEW, table.source()));
    } else if (!table.topBorder() || !table.middleBorder() || !table.bottomBorder()) {
      var missingBorders = new ArrayList<String>();
      if (!table.topBorder()) missingBorders.add("顶线");
      if (!table.middleBorder()) missingBorders.add("表头下横线");
      if (!table.bottomBorder()) missingBorders.add("底线");
      String missing = String.join("、", missingBorders);
      boolean continuation = captionText != null && captionText.stripLeading().startsWith("续表");
      results.add(result("TAB-02", missing, "缺少符合规范的" + missing,
          continuation ? "TABLE_CONTINUATION_BORDER_INVALID" : "TABLE_BORDER_INVALID",
          Judgement.FAIL, table.source()));
    }
  }

  private void checkBodyCitation(List<CheckResult> results, ParsedDocument document,
      TableModel table, String captionText, String normalizedCaption) {
    String tableNumber = tableNumberFromCaption(normalizedCaption);
    if (tableNumber == null || (captionText != null && captionText.stripLeading().startsWith("续表"))) return;
    SourceAddress referenceSource = table.captionSource() == null ? table.source() : table.captionSource();
    boolean cited = regions.isLabelReferencedInBody(document, tableNumber,
        table.captionSource(), TABLE_NUMBER);
    results.add(result("TAB-01", "body reference to table " + tableNumber,
        cited ? tableNumber : null, cited ? "" : "TABLE_NOT_CITED_IN_BODY",
        cited ? Judgement.PASS : Judgement.FAIL, referenceSource));
  }

  private void checkCaptionParagraph(List<CheckResult> results, ParagraphModel paragraph,
      String captionText, RuleSet rules) {
    boolean continuation = captionText != null && captionText.stripLeading().startsWith("续表");
    String requiredAlignment = continuation ? "right" : "center";
    String alignmentError = continuation
        ? "TABLE_CONTINUATION_CAPTION_NOT_RIGHT" : "TABLE_CAPTION_NOT_CENTERED";
    String alignmentUnknown = continuation
        ? "TABLE_CONTINUATION_CAPTION_ALIGNMENT_UNKNOWN" : "TABLE_CAPTION_ALIGNMENT_UNKNOWN";
    if (paragraph.alignment() == null) {
      results.add(result("TAB-03", requiredAlignment, "unknown", alignmentUnknown,
          Judgement.FAIL, paragraph.source()));
    } else {
      boolean aligned = requiredAlignment.equalsIgnoreCase(paragraph.alignment());
      results.add(result("TAB-03", requiredAlignment, paragraph.alignment(),
          aligned ? "" : alignmentError, aligned ? Judgement.PASS : Judgement.FAIL,
          paragraph.source()));
    }
    for (RunModel run : paragraph.runs()) {
      if (ParagraphFormatChecker.isLayoutControlOnly(run)) continue;
      results.add(paragraphFormatChecker.checkFont(run, rules, "CAPTION-FONT"));
      results.add(checkCaptionSize(run));
    }
  }

  private void checkTableNumberSequences(List<CheckResult> results, ParsedDocument document) {
    Map<String, BigInteger> nextByChapter = new HashMap<>();
    for (TableModel table : document.tables()) {
      if (isBeforeMainBodyTable(document, table)
          || isCodeListingTable(table)
          || isEquationLayoutTable(table, document.equations())
          || regions.appendixLetterAt(document,
              table.captionSource() == null ? table.source() : table.captionSource()) != null) continue;
      Matcher matcher = TABLE_SEQUENCE.matcher(table.captionText() == null ? "" : table.captionText().stripLeading());
      if (!matcher.matches()) continue;
      checkNumberSequence(results, nextByChapter, matcher.group(1), matcher.group(2),
          table.captionText(), table.captionSource() == null ? table.source() : table.captionSource());
    }
  }

  private void checkNumberSequence(List<CheckResult> results, Map<String, BigInteger> nextByChapter,
      String chapterText, String sequenceText, String actualText, SourceAddress source) {
    BigInteger chapter = new BigInteger(chapterText);
    BigInteger sequence = new BigInteger(sequenceText);
    BigInteger expected = nextByChapter.getOrDefault(chapterText, BigInteger.ONE);
    boolean matches = chapter.toString().equals(chapterText)
        && sequence.toString().equals(sequenceText) && sequence.equals(expected);
    if (!matches) {
      results.add(result("TAB-05", chapter + "-" + expected, chapterText + "-" + sequenceText,
          "TABLE_NUMBER_SEQUENCE_MISMATCH", Judgement.FAIL, source));
    }
    nextByChapter.put(chapterText, expected.max(sequence).add(BigInteger.ONE));
  }

  private boolean isSplitContinuationHeaderFragment(ParsedDocument document, TableModel fragment) {
    if (fragment.rows() != 1 || fragment.captionText() != null) return false;
    List<TableModel> tables = document.tables();
    int index = tables.indexOf(fragment);
    if (index <= 0 || index + 1 >= tables.size()) return false;
    for (int before = index - 1; before >= 0; before--) {
      TableModel candidate = tables.get(before);
      Matcher original = TABLE_NUMBER.matcher(candidate.captionText() == null ? "" : candidate.captionText());
      if (original.find() && candidate.rows() > 1) {
        Matcher continuation = CONTINUATION_CAPTION_FORMAT.matcher(
            tables.get(index + 1).captionText() == null ? "" : tables.get(index + 1).captionText());
        return continuation.matches() && original.group(1).equals(continuation.group(1));
      }
    }
    return false;
  }

  private boolean isBeforeMainBodyTable(ParsedDocument document, TableModel table) {
    return regions.isBeforeMainBody(document, table.source());
  }

  private boolean isCodeListingTable(TableModel table) {
    if (table.columns() != 1) return false;
    if (table.codeLayout()) return true;
    return table.headerTexts().stream().map(String::strip).filter(text -> !text.isEmpty())
        .anyMatch(text -> text.matches("(?s).*(?:@[A-Za-z]+|\\b(?:void|int|String|boolean|return|if|for)\\b|[{};]).*"));
  }

  private boolean isEquationLayoutTable(TableModel table, List<EquationModel> equations) {
    if (table.rows() != 1 || table.columns() != 2 || table.captionText() != null || table.hasTableStyle())
      return false;
    return equations.stream().anyMatch(equation -> {
      SourceAddress numberSource = equation.numberSource() != null ? equation.numberSource()
          : equation.numberEvidence() == null ? null : equation.numberEvidence().source();
      return equation.standalone() && equation.number() != null && numberSource != null
          && equation.source().partName().equals(table.source().partName())
          && numberSource.partName().equals(table.source().partName())
          && isAncestorPath(table.source().elementPath(), equation.source().elementPath())
          && isAncestorPath(table.source().elementPath(), numberSource.elementPath());
    });
  }

  private Optional<ParagraphModel> captionParagraph(ParsedDocument document, SourceAddress source) {
    if (source == null) return Optional.empty();
    return document.paragraphs().stream()
        .filter(paragraph -> paragraph.source().elementPath().equals(source.elementPath()))
        .findFirst();
  }

  private CheckResult checkCaptionSize(RunModel run) {
    if (run.fontSizeHalfPoints() == null)
      return result("CAPTION-SIZE", "21", "unknown", "SIZE_UNKNOWN", Judgement.REVIEW, run.source());
    boolean matches = run.fontSizeHalfPoints() == 21;
    return result("CAPTION-SIZE", "21", String.valueOf(run.fontSizeHalfPoints()),
        "", matches ? Judgement.PASS : Judgement.FAIL, run.source());
  }

  private String tableNumberFromCaption(String caption) {
    if (caption == null) return null;
    Matcher matcher = Pattern.compile("^\\s*表\\s*((?:\\d+[-－]\\d+)|(?:[A-Z]\\d+))(?![\\d-])")
        .matcher(caption);
    return matcher.find() ? matcher.group(1).replace('－', '-') : null;
  }

  private String normalizeCaption(String text) {
    return text == null ? null : text.replace('\u00a0', ' ').replace('\u3000', ' ').strip();
  }

  private static boolean isAncestorPath(List<Integer> ancestor, List<Integer> descendant) {
    return ancestor.size() <= descendant.size()
        && descendant.subList(0, ancestor.size()).equals(ancestor);
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
