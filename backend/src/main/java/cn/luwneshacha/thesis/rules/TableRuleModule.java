package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.document.TableFragmentEvidence;
import cn.luwneshacha.thesis.rendering.RenderedLayoutEvidence;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns thesis-table caption, border, citation, sequence, and continuation checks. */
final class TableRuleModule implements RuleModule {
  static final String NO_TABLE_NOTES_REASON =
      "NO_TABLE_NOTES_IN_COMPLETE_INLINE_TABLE_INVENTORY";
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
  private final RenderedLayoutEvidence renderedLayout;

  TableRuleModule() { this(null); }

  TableRuleModule(RenderedLayoutEvidence renderedLayout) {
    this.renderedLayout = renderedLayout;
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    var citationRun = new EvidenceCounter("TAB-01");
    var borderRun = new EvidenceCounter("TAB-02");
    var captionRun = new EvidenceCounter("TAB-03");
    var unitRun = new EvidenceCounter("TAB-04");
    var sequenceRun = new EvidenceCounter("TAB-05");
    var placeholderRun = new EvidenceCounter("TAB-06");
    var noteRun = new EvidenceCounter("TAB-07");
    var continuationRun = new EvidenceCounter("TAB-08");
    var continuationPositionRun = new EvidenceCounter("TAB-09");
    var captionFontRun = new EvidenceCounter("CAPTION-FONT");
    var captionSizeRun = new EvidenceCounter("CAPTION-SIZE");
    boolean bodyBoundaryKnown = document.paragraphs().stream()
        .anyMatch(paragraph -> !paragraph.insideTextBox()
            && regions.isBodyChapterStart(paragraph, document));
    for (TableModel table : document.tables()) {
      if (isBeforeMainBodyTable(document, table)
          || isCodeListingTable(table)
          || isEquationLayoutTable(table, document.equations())) continue;
      var checked = tableChecker.evaluate(document, table, rules);
      for (var evidence : checked.evidence()) {
        EvidenceCounter counter = switch (evidence.ruleId()) {
          case "TAB-04" -> unitRun;
          case "TAB-06" -> placeholderRun;
          case "TAB-07" -> noteRun;
          case "TAB-08" -> continuationRun;
          default -> throw new IllegalStateException("Unsupported table evidence: " + evidence.ruleId());
        };
        counter.recordKnown(bodyBoundaryKnown && evidence.checked());
      }
      if (table.fragmentEvidence() == null && table.captionText() != null
          && table.captionText().stripLeading().startsWith("续表")) {
        CheckResult position = checkContinuationPosition(document, table);
        results.add(position);
        continuationPositionRun.record(position, bodyBoundaryKnown);
      }
      if (table.fragmentEvidence() != null) {
        results.addAll(checked.results());
        continue;
      }
      checkTable(results, document, table, rules, bodyBoundaryKnown, citationRun, borderRun,
          captionRun, captionFontRun, captionSizeRun, checked.results());
    }
    checkTableNumberSequences(results, document, bodyBoundaryKnown, sequenceRun);
    var runs = new ArrayList<RuleRun>();
    for (var evidence : List.of(citationRun, borderRun, captionRun, unitRun, sequenceRun,
        placeholderRun, noteRun, continuationRun, continuationPositionRun,
        captionFontRun, captionSizeRun)) {
      if (evidence.candidates > 0) runs.add(evidence.toRun());
    }
    if (noteRun.candidates == 0 && bodyBoundaryKnown && document.tableInventoryComplete()
        && document.diagnostics().isEmpty()
        && document.paragraphs().stream().noneMatch(ParagraphModel::insideTextBox)
        && document.tables().stream().allMatch(table ->
            table.flowPosition() == TableModel.FlowPosition.INLINE_BODY
                && table.noteText() == null)) {
      // A parsed, reconciled inventory of inline tables has no adjacent table-note
      // candidate. Floating/nested tables and hand-built models cannot prove that.
      runs.add(new RuleRun("TAB-07", "TableRuleModule", 0, 0, 0,
          NO_TABLE_NOTES_REASON));
    }
    return new Evaluation(List.copyOf(results), List.copyOf(runs));
  }

  private CheckResult checkContinuationPosition(ParsedDocument document, TableModel table) {
    SourceAddress source = table.captionSource() == null ? table.source() : table.captionSource();
    String caption = table.captionText();
    List<ParagraphModel> matchingParagraphs = document.paragraphs().stream()
        .filter(paragraph -> canonicalCaption(paragraph.runs().stream().map(RunModel::text)
            .collect(java.util.stream.Collectors.joining())).equals(canonicalCaption(caption)))
        .toList();
    boolean captionSourceMatches = table.captionSource() != null && matchingParagraphs.size() == 1
        && matchingParagraphs.getFirst().source().partName().equals(table.captionSource().partName())
        && matchingParagraphs.getFirst().source().elementPath().equals(table.captionSource().elementPath());
    if (renderedLayout == null || !renderedLayout.matchesSource(document.sourceSha256())
        || !captionSourceMatches)
      return result("TAB-09", "续表编号行位于所在页正文第一行", "无法可靠确认页面位置",
          "TABLE_CONTINUATION_PAGE_POSITION_UNKNOWN", Judgement.REVIEW, source);
    var headerTexts = new ArrayList<String>();
    document.headers().forEach(header -> headerTexts.add(header.text()));
    var position = renderedLayout.continuationPosition(caption, headerTexts);
    return switch (position.status()) {
      case FIRST_BODY_LINE -> result("TAB-09", "续表编号行位于所在页正文第一行",
          "第" + position.page() + "页正文第一行", "", Judgement.PASS, source);
      case LATER_BODY_LINE -> result("TAB-09", "续表编号行位于所在页正文第一行",
          "第" + position.page() + "页正文第" + position.bodyLineNumber() + "行",
          "TABLE_CONTINUATION_NOT_FIRST_BODY_LINE", Judgement.FAIL, source);
      case UNKNOWN -> result("TAB-09", "续表编号行位于所在页正文第一行",
          "无法可靠确认页面位置", "TABLE_CONTINUATION_PAGE_POSITION_UNKNOWN",
          Judgement.REVIEW, source);
    };
  }

  private static String canonicalCaption(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .codePoints().filter(code -> !Character.isWhitespace(code) && !Character.isSpaceChar(code))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static final class EvidenceCounter {
    private final String ruleId;
    private int candidates;
    private int unknown;

    private EvidenceCounter(String ruleId) { this.ruleId = ruleId; }

    private void recordKnown(boolean known) {
      candidates++;
      if (!known) unknown++;
    }

    private void record(CheckResult result, boolean scopeKnown) {
      recordKnown(scopeKnown && result.judgement() != Judgement.REVIEW
          && result.applicability() != Applicability.UNKNOWN
          && result.executionStatus() == ExecutionStatus.EXECUTED);
    }

    private void unknown() { candidates++; unknown++; }

    private RuleRun toRun() {
      return new RuleRun(ruleId, "TableRuleModule", candidates, candidates - unknown, unknown, null);
    }
  }

  private void checkTable(List<CheckResult> results, ParsedDocument document,
      TableModel table, RuleSet rules, boolean bodyBoundaryKnown, EvidenceCounter citationRun,
      EvidenceCounter borderRun, EvidenceCounter captionRun, EvidenceCounter captionFontRun,
      EvidenceCounter captionSizeRun, List<CheckResult> tableFindings) {
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

    results.addAll(tableFindings);
    TableModel tail = document.tables().stream().filter(candidate -> candidate.fragmentEvidence() != null
        && candidate.fragmentEvidence().kind() == TableFragmentEvidence.Kind.DATA_ROW
        && candidate.fragmentEvidence().originalSource().equals(table.source()))
        .findFirst().orElse(table);
    checkBorders(results, table, tail, captionText);
    borderRun.recordKnown(bodyBoundaryKnown
        && (!table.hasTableStyle() || table.tableStyleBorderEvidenceResolved())
        && (!tail.hasTableStyle() || tail.tableStyleBorderEvidenceResolved()));
    checkBodyCitation(results, document, table, captionText, normalizedCaption,
        bodyBoundaryKnown, citationRun);
    var caption = captionParagraph(document, table.captionSource());
    captionRun.recordKnown(bodyBoundaryKnown && !table.layoutNeedsReview()
        && (captionText == null || (caption.isPresent() && caption.orElseThrow().alignment() != null)));
    if (caption.isPresent()) {
      int candidatesBefore = captionFontRun.candidates;
      checkCaptionParagraph(results, caption.orElseThrow(), captionText, rules,
          bodyBoundaryKnown, captionFontRun, captionSizeRun);
      if (captionFontRun.candidates == candidatesBefore) {
        captionFontRun.unknown();
        captionSizeRun.unknown();
      }
    } else {
      captionFontRun.unknown();
      captionSizeRun.unknown();
    }
  }

  private void checkBorders(List<CheckResult> results, TableModel table, TableModel tail, String captionText) {
    if (table.hasTableStyle() && !table.tableStyleBorderEvidenceResolved()) {
      results.add(result("TAB-02", "verifiable three-line borders", "table style border evidence is unresolved",
          "TABLE_BORDER_EVIDENCE_UNRESOLVED", Judgement.REVIEW, table.source()));
    } else if (!table.topBorder() || !table.middleBorder() || !tail.bottomBorder()) {
      var missingBorders = new ArrayList<String>();
      if (!table.topBorder()) missingBorders.add("顶线");
      if (!table.middleBorder()) missingBorders.add("表头下横线");
      if (tail == table && !table.bottomBorder()) missingBorders.add("底线");
      boolean continuation = captionText != null && captionText.stripLeading().startsWith("续表");
      if (!missingBorders.isEmpty()) {
        String missing = String.join("、", missingBorders);
        results.add(result("TAB-02", missing, "缺少符合规范的" + missing,
            continuation ? "TABLE_CONTINUATION_BORDER_INVALID" : "TABLE_BORDER_INVALID",
            Judgement.FAIL, table.source()));
      }
    }
    if (tail != table) {
      if (tail.hasTableStyle() && !tail.tableStyleBorderEvidenceResolved()) {
        results.add(result("TAB-02", "verifiable three-line borders", "tail table style border evidence is unresolved",
            "TABLE_BORDER_EVIDENCE_UNRESOLVED", Judgement.REVIEW, tail.source()));
      } else if (!tail.bottomBorder()) {
        results.add(result("TAB-02", "底线", "缺少符合规范的底线", "TABLE_BORDER_INVALID",
            Judgement.FAIL, tail.source()));
      }
    }
  }

  private void checkBodyCitation(List<CheckResult> results, ParsedDocument document,
      TableModel table, String captionText, String normalizedCaption,
      boolean bodyBoundaryKnown, EvidenceCounter citationRun) {
    String tableNumber = tableNumberFromCaption(normalizedCaption);
    if (captionText != null && captionText.stripLeading().startsWith("续表")) return;
    if (tableNumber == null) {
      citationRun.unknown();
      return;
    }
    SourceAddress referenceSource = table.captionSource() == null ? table.source() : table.captionSource();
    boolean cited = regions.isLabelReferencedInBody(document, tableNumber,
        table.captionSource(), TABLE_NUMBER);
    results.add(result("TAB-01", "body reference to table " + tableNumber,
        cited ? tableNumber : null, cited ? "" : "TABLE_NOT_CITED_IN_BODY",
        cited ? Judgement.PASS : Judgement.FAIL, referenceSource));
    citationRun.recordKnown(bodyBoundaryKnown);
  }

  private void checkCaptionParagraph(List<CheckResult> results, ParagraphModel paragraph,
      String captionText, RuleSet rules, boolean bodyBoundaryKnown,
      EvidenceCounter captionFontRun, EvidenceCounter captionSizeRun) {
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
      captionFontRun.record(results.getLast(), bodyBoundaryKnown);
      results.add(checkCaptionSize(run));
      captionSizeRun.record(results.getLast(), bodyBoundaryKnown);
    }
  }

  private void checkTableNumberSequences(List<CheckResult> results, ParsedDocument document,
      boolean bodyBoundaryKnown, EvidenceCounter sequenceRun) {
    Map<String, BigInteger> nextByChapter = new HashMap<>();
    for (TableModel table : document.tables()) {
      if (table.fragmentEvidence() != null || isBeforeMainBodyTable(document, table)
          || isCodeListingTable(table)
          || isEquationLayoutTable(table, document.equations())
          || regions.appendixLetterAt(document,
              table.captionSource() == null ? table.source() : table.captionSource()) != null) continue;
      if (table.captionText() != null && table.captionText().stripLeading().startsWith("续表")) continue;
      Matcher matcher = TABLE_SEQUENCE.matcher(table.captionText() == null ? "" : table.captionText().stripLeading());
      if (!matcher.matches()) {
        sequenceRun.unknown();
        continue;
      }
      sequenceRun.recordKnown(bodyBoundaryKnown);
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
