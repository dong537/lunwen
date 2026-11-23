package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Owns the equation-object boundary of the rule pipeline.
 *
 * <p>The parser can expose several equation objects for one document. This
 * module keeps equation object, citation, and sequence checks behind one
 * document-level rule contract.</p>
 */
final class EquationRuleModule implements RuleModule {
  private static final Pattern TEXT_EQUATION = Pattern.compile(
      "(?<![=!<>])=(?!=)[^\\r\\n]*[\\p{L}\\d]");
  private static final String STATE_MAPPING_BOUNDARY = "(^|[，,；;：:。.!?！？\\r\\n])\\s*";
  private static final Pattern NUMERIC_STATE_MAPPING = Pattern.compile(
      STATE_MAPPING_BOUNDARY
          + "\\d+\\s*=\\s*\\p{IsHan}*(?:开启|关闭|正常|异常|成功|失败|启用|禁用)(?=$|[，,；;。.!?！？])");
  private static final Pattern NAMED_OPTION_MAPPING = Pattern.compile(
      STATE_MAPPING_BOUNDARY
          + "(?:模式|档位|开关|状态)\\s*[A-Za-z][A-Za-z0-9]*\\s*=\\s*"
          + "\\d+(?:/\\d+)+\\s*对应\\s*\\p{IsHan}+(?:/\\p{IsHan}+)*"
          + "(?=$|[，,；;。.!?！？])");
  private static final Pattern CATEGORICAL_VALUE_LIST = Pattern.compile(
      "^(?:\\p{IsHan}+[（(]\\s*)?\\d+\\s*=\\s*\\p{IsHan}{1,12}"
          + "(?:\\s*[，,、；;]\\s*\\d+\\s*=\\s*\\p{IsHan}{1,12})+"
          + "\\s*[）)]?\\s*[。.]?$");
  private static final Pattern CODE_BLOCK = Pattern.compile(
      "^\\s*\\}?\\s*(?:(?:else\\s+)?if|for|while)\\s*\\(.*\\)\\s*\\{\\s*$", Pattern.DOTALL);
  private static final Pattern CODE_INLINE_CONTROL = Pattern.compile(
      "^\\s*(?:else\\s+)?if\\s*\\(.*\\)\\s*.+;\\s*$", Pattern.DOTALL);
  private static final Pattern CODE_DECLARATION = Pattern.compile(
      "^\\s*(?:const|let|var|int|long|short|float|double|bool|boolean|String|char|auto)"
          + "\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*=.*;\\s*(?://.*)?$", Pattern.DOTALL);
  private static final Pattern CODE_COMPOUND_ASSIGNMENT = Pattern.compile(
      "^\\s*[A-Za-z_$][A-Za-z0-9_$\\.\\[\\]]*\\s*(?:\\+=|-=|\\*=|/=|%=)\\s*.+;\\s*(?://.*)?$",
      Pattern.DOTALL);
  private static final Pattern CODE_COMPOUND_OPERATOR = Pattern.compile("(?:\\+=|-=|\\*=|/=|%=)");
  private static final Pattern CODE_ASSIGNMENT = Pattern.compile(
      "^\\s*[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*|\\[[^\\]]+\\])*"
          + "\\s*=\\s*(.*?);\\s*(?://.*)?$", Pattern.DOTALL);
  private static final Pattern CODE_METHOD_CALL = Pattern.compile(
      "\\.[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(");
  private static final Pattern CODE_GETTER_CALL = Pattern.compile(
      "^get[A-Z][A-Za-z0-9_$]*\\s*\\(");
  private static final Pattern CODE_LITERAL = Pattern.compile("\\b(?:true|false|null|undefined)\\b");
  private final EquationRuleChecker checker = new EquationRuleChecker();
  private final EquationReferenceRuleChecker referenceChecker = new EquationReferenceRuleChecker();
  private final EquationSequenceRuleChecker sequenceChecker = new EquationSequenceRuleChecker();
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var findings = new ArrayList<CheckResult>();
    Map<String, Count> counts = new LinkedHashMap<>();
    for (String id : List.of("EQ-01", "EQ-02", "EQ-03", "EQ-04", "EQ-05", "EQ-06", "EQ-07"))
      counts.put(id, new Count());
    for (EquationModel equation : document.equations()) {
      List<CheckResult> objectFindings = checker.check(document, equation, rules);
      findings.addAll(objectFindings);
      boolean unreadable = equation.needsReview() || (equation.objectEvidence() != null
          && !equation.objectEvidence().internalStructureReadable());
      counts.get("EQ-06").record(unreadable);
      if (equation.standalone()) {
        boolean numberUnknown = equation.number() == null ? unreadable
            : equation.numberEvidence() == null || equation.numberEvidence().text() == null
                || equation.numberEvidence().text().isBlank();
        counts.get("EQ-01").record(numberUnknown || unknownFinding(objectFindings, "EQ-01"));
        counts.get("EQ-07").record(unreadable || unknownFinding(objectFindings, "EQ-07"));
      }
      if (equation.standalone() && equation.number() != null) {
        counts.get("EQ-02").record(unknownFinding(objectFindings, "EQ-02"));
        counts.get("EQ-03").record(unknownFinding(objectFindings, "EQ-03"));
        CheckResult reference = referenceChecker.check(document, equation);
        findings.add(reference);
        boolean hasCitationRegion = hasCitationRegion(document, equation);
        counts.get("EQ-04").record(reference.judgement() == Judgement.REVIEW
            || (reference.judgement() != Judgement.PASS && !hasCitationRegion));
      }
      if (equation.number() != null)
        counts.get("EQ-05").record(!equation.number().matches("\\d+-\\d+")
            || !hasNumberedChapterAt(document, equation));
    }
    findings.addAll(sequenceChecker.check(document));
    // A typed equation or an uncaptioned image may be a formula outside the
    // structured OMML/OLE models. Record the unresolved candidates explicitly;
    // never turn their absence from the parsed formula list into a pass.
    int unresolved = unresolvedVisualCandidates(document) + unresolvedTextCandidates(document);
    for (int index = 0; index < unresolved; index++)
      for (Count count : counts.values()) count.record(true);
    var runs = new ArrayList<RuleRun>();
    for (var entry : counts.entrySet()) {
      Count count = entry.getValue();
      // No parsed formulas is not proof that all possible formula objects were recognized.
      if (count.candidates > 0) runs.add(new RuleRun(entry.getKey(), getClass().getSimpleName(),
          count.candidates, count.candidates - count.unknown, count.unknown, null));
    }
    return new Evaluation(List.copyOf(findings), List.copyOf(runs));
  }

  private boolean hasCitationRegion(ParsedDocument document, EquationModel equation) {
    return hasCitationRegion(document, equation.source());
  }

  private boolean hasCitationRegion(ParsedDocument document, SourceAddress source) {
    if (regions.appendixLetterAt(document, source) != null) return true;
    boolean inBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (!beforeOrAt(paragraph.source(), source)) continue;
      if (regions.isInsideTable(paragraph, document.tables())) continue;
      if (regions.isBodyChapterStart(paragraph, document)) inBody = true;
      else if (inBody && regions.isEndMatterHeading(paragraph, document)) inBody = false;
    }
    return inBody;
  }

  private int unresolvedVisualCandidates(ParsedDocument document) {
    if (document.equationInventoryComplete()) {
      Map<List<Integer>, Integer> captionedFigures = new LinkedHashMap<>();
      for (var image : document.images()) {
        if (image.source() != null && image.captionText() != null
            && !image.captionFragment() && !image.captionNumberAmbiguous())
          captionedFigures.merge(image.source().elementPath(), 1, Integer::sum);
      }
      int unresolved = 0;
      for (var object : document.possibleEquationObjectSources()) {
        SourceAddress source = object.source();
        if (source == null || !hasCitationRegion(document, source)) continue;
        if (!"OLE".equals(object.kind())) {
          int figures = captionedFigures.getOrDefault(source.elementPath(), 0);
          if (figures > 0) {
            captionedFigures.put(source.elementPath(), figures - 1);
            continue;
          }
        }
        unresolved++;
      }
      return unresolved;
    }
    int count = 0;
    for (var image : document.images()) {
      if (image.captionText() == null && image.source() != null
          && hasCitationRegion(document, image.source())) count++;
    }
    return count;
  }

  private int unresolvedTextCandidates(ParsedDocument document) {
    int count = 0;
    boolean inBody = false;
    boolean inAppendix = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      // Table cells can contain prose and typed equations, but their labels
      // cannot start or end a document region. Keep the region established by
      // surrounding top-level paragraphs while scanning the cell text.
      if (!insideTable) {
        boolean bodyStart = regions.isBodyChapterStart(paragraph);
        boolean endMatter = inBody && regions.isEndMatterHeading(paragraph);
        String appendixLetter = regions.appendixHeadingLetter(paragraph);
        // A manual contents entry may look exactly like a later body or
        // appendix heading, even when its page number and TOC style are absent.
        if ((!bodyStart && !endMatter && appendixLetter == null)
            || !regions.isContentsEntry(paragraph, document)) {
          if (bodyStart) {
            inBody = true;
            inAppendix = false;
          } else if (endMatter) {
            inBody = false;
          }
          if (appendixLetter != null) inAppendix = true;
        }
      }
      if (!inBody && !inAppendix) continue;
      if (document.equations().stream().anyMatch(equation -> equation.source() != null
          && equation.source().partName().equals(paragraph.source().partName())
          && equation.source().elementPath().equals(paragraph.source().elementPath()))) continue;
      // The standalone-formula rules do not apply to calculations written as
      // prose or as explanations in an ordinary data table. A borderless
      // single-row layout can still hold a separately displayed formula.
      String paragraphText = ManualLineBreakSupport.text(paragraph);
      if (insideTable && !isPotentialFormulaLayoutTable(paragraph.source(), document.tables())
          && isStructuredDescriptionCell(paragraph.source(), document.tables())
          && paragraphText.indexOf('\n') < 0 && paragraphText.indexOf('\r') < 0) continue;
      for (String line : paragraphText.split("\\R", -1)) {
        if (isClearlyCodeOrCategory(line)) continue;
        if (looksLikeIndependentEquationLine(line) && hasUnresolvedTextEquation(line)) count++;
      }
    }
    return count;
  }

  private static boolean isPotentialFormulaLayoutTable(SourceAddress source, List<TableModel> tables) {
    TableModel containing = innermostTable(source, tables);
    return containing != null && containing.rows() == 1 && containing.columns() <= 2
        && containing.captionText() == null && !containing.topBorder()
        && !containing.middleBorder() && !containing.bottomBorder()
        && !containing.verticalBorders() && !containing.codeLayout();
  }

  private static boolean isStructuredDescriptionCell(SourceAddress source, List<TableModel> tables) {
    TableModel containing = innermostTable(source, tables);
    if (containing == null) return false;
    for (TableModel.CellEvidence cell : containing.cellEvidence()) {
      List<Integer> cellPath = cell.source().elementPath();
      if (!cell.source().partName().equals(source.partName())
          || source.elementPath().size() <= cellPath.size()
          || !source.elementPath().subList(0, cellPath.size()).equals(cellPath)) continue;
      String header = cell.columnHeader();
      if (header == null || cell.fullWidth() || cell.paragraphCount() != 1) return false;
      String normalized = header.codePoints()
          .filter(code -> !Character.isWhitespace(code) && !Character.isSpaceChar(code))
          .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
          .toString();
      return normalized.equals("说明") || normalized.endsWith("说明")
          || normalized.equals("描述") || normalized.equals("备注");
    }
    return false;
  }

  private static TableModel innermostTable(SourceAddress source, List<TableModel> tables) {
    List<Integer> path = source.elementPath();
    TableModel containing = null;
    for (TableModel table : tables) {
      SourceAddress tableSource = table.source();
      List<Integer> tablePath = tableSource.elementPath();
      if (!tableSource.partName().equals(source.partName()) || path.size() <= tablePath.size()
          || !path.subList(0, tablePath.size()).equals(tablePath)) continue;
      if (containing == null || tablePath.size() > containing.source().elementPath().size())
        containing = table;
    }
    return containing;
  }

  private static boolean looksLikeIndependentEquationLine(String text) {
    String value = text.strip();
    if (value.isEmpty()) return false;
    // A sentence lead-in or another clause before the equality makes the
    // equation inline. Preserve uncertainty for a complete unnumbered line.
    int equals = value.indexOf('=');
    if (equals < 0) return false;
    String beforeEquals = value.substring(0, equals);
    if (beforeEquals.isBlank()) return false;
    return beforeEquals.chars().noneMatch(ch -> "，,；;：:。.!?！？\r\n".indexOf(ch) >= 0);
  }

  private static boolean hasUnresolvedTextEquation(String text) {
    // A value-to-label explanation is not a formula. Remove only complete,
    // punctuation-bounded mappings; any other equality remains a candidate.
    boolean stateContext = text.contains("状态") || text.contains("模式")
        || text.contains("开关") || text.contains("指示");
    String withoutMappings = stateContext ? NUMERIC_STATE_MAPPING.matcher(text).replaceAll("$1") : text;
    withoutMappings = NAMED_OPTION_MAPPING.matcher(withoutMappings).replaceAll("$1");
    return TEXT_EQUATION.matcher(withoutMappings).find();
  }

  private static boolean isClearlyCodeOrCategory(String text) {
    // Whole-cell category maps and explicit programming syntax are not math.
    // Ambiguous expressions, including x=1 and a=b+c;, remain candidates.
    if (CATEGORICAL_VALUE_LIST.matcher(text.strip()).matches()) return true;
    if (CODE_BLOCK.matcher(text).matches() || CODE_DECLARATION.matcher(text).matches()
        || CODE_COMPOUND_ASSIGNMENT.matcher(text).matches()) return true;
    if (CODE_INLINE_CONTROL.matcher(text).matches()
        && (text.contains("this.") || CODE_COMPOUND_OPERATOR.matcher(text).find())) return true;
    var assignment = CODE_ASSIGNMENT.matcher(text);
    if (!assignment.matches()) return false;
    String rightHandSide = assignment.group(1).strip();
    return rightHandSide.contains("this.") || CODE_LITERAL.matcher(rightHandSide).find()
        || CODE_METHOD_CALL.matcher(rightHandSide).find()
        || CODE_GETTER_CALL.matcher(rightHandSide).find()
        || text.contains("//");
  }

  private boolean hasNumberedChapterAt(ParsedDocument document, EquationModel equation) {
    boolean inNumberedChapter = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (!beforeOrAt(paragraph.source(), equation.source())) continue;
      if (regions.isInsideTable(paragraph, document.tables())) continue;
      if (regions.isNumberedChapterHeading(paragraph)) inNumberedChapter = true;
      else if (inNumberedChapter && regions.isEndMatterHeading(paragraph, document))
        inNumberedChapter = false;
    }
    return inNumberedChapter;
  }

  private static boolean beforeOrAt(SourceAddress paragraph, SourceAddress equation) {
    if (paragraph == null || equation == null || !paragraph.partName().equals(equation.partName()))
      return false;
    List<Integer> left = paragraph.elementPath();
    List<Integer> right = equation.elementPath();
    int common = Math.min(left.size(), right.size());
    for (int index = 0; index < common; index++)
      if (!left.get(index).equals(right.get(index))) return left.get(index) < right.get(index);
    return left.size() <= right.size();
  }

  private static boolean unknownFinding(List<CheckResult> findings, String ruleId) {
    return findings.stream().filter(result -> ruleId.equals(result.ruleId())).anyMatch(result ->
        result.judgement() == Judgement.REVIEW
            || result.applicability() == Applicability.UNKNOWN
            || result.executionStatus() != ExecutionStatus.EXECUTED
            || containsUnknownReason(result.reasonCode()));
  }

  private static boolean containsUnknownReason(String reasonCode) {
    return reasonCode != null && (reasonCode.contains("UNKNOWN")
        || reasonCode.contains("UNREADABLE") || reasonCode.contains("UNSUPPORTED"));
  }

  private static final class Count {
    private int candidates;
    private int unknown;

    private void record(boolean unresolved) {
      candidates++;
      if (unresolved) unknown++;
    }
  }
}
