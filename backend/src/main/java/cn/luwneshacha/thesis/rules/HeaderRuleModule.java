package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.HeaderModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Forbids cover headers and checks required effective headers after the cover. */
final class HeaderRuleModule implements RuleModule {
  private static final String EXPECTED_TEXT = "山东石油化工学院本科毕业设计（论文）";
  private static final String EXPECTED_FONT = "黑体";
  private static final int EXPECTED_SIZE_HALF_POINTS = 21;
  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var findings = new ArrayList<CheckResult>();
    var checks = new HeaderChecks();
    CoverHeaderScope coverScope = CoverHeaderScope.identify(document);
    if (document.sections().isEmpty()) {
      if (document.paragraphs().isEmpty()) {
        checks.scope.unknown();
        findings.add(scopeUnknown(document, null, "section and document content unavailable"));
      } else {
        checks.scope.checked();
      }
      if (!document.paragraphs().isEmpty() && !coverScope.entireDocumentIsCover()) {
        checks.missing.checked();
        findings.add(result("HEADER-01-MISSING", EXPECTED_TEXT, "implicit section has no header reference",
            "HEADER_MISSING", Judgement.FAIL, coverScope.firstRequiredSource(), coverScope.firstRequiredSource()));
      }
      return new Evaluation(findings, checks.runs());
    }

    Set<String> checkedParts = new HashSet<>();
    for (int sectionIndex = 0; sectionIndex < document.sections().size(); sectionIndex++) {
      int currentSectionIndex = sectionIndex;
      SectionModel section = document.sections().get(sectionIndex);
      if (section.source() == null || !"word/document.xml".equals(section.source().partName())) {
        checks.scope.unknown();
        findings.add(scopeUnknown(document, section.source(), "section XML evidence unavailable"));
        continue;
      }
      checks.scope.checked();
      if (coverScope.firstPageIsCover(sectionIndex))
        checkCoverHeader(findings, checks, document, coverScope, sectionIndex, section);
      if (coverScope.sectionIsCoverOnly(sectionIndex)) continue;
      SourceAddress contextSource = sectionContext(document, coverScope, sectionIndex);
      List<HeaderModel.Type> requiredTypes = new ArrayList<>();
      requiredTypes.add(HeaderModel.Type.DEFAULT);
      if (section.firstPageHeaderEnabled() && !coverScope.firstPageIsCover(sectionIndex))
        requiredTypes.add(HeaderModel.Type.FIRST);
      if (section.evenPageHeaderEnabled()) requiredTypes.add(HeaderModel.Type.EVEN);
      for (HeaderModel.Type type : requiredTypes) {
        checks.missing.checked();
        HeaderModel header = document.headers().stream()
            .filter(candidate -> candidate.applicableSectionIndexesByType()
                .getOrDefault(type, List.of()).contains(currentSectionIndex))
            .findFirst().orElse(null);
        if (header == null) {
          findings.add(result("HEADER-01-MISSING", EXPECTED_TEXT, "missing " + type.xmlValue() + " header",
              "HEADER_MISSING", Judgement.FAIL, section.source(), contextSource));
        } else if (checkedParts.add(header.source().partName())) {
          checks.recordHeader(header);
          checkHeader(findings, header, contextSource);
        }
      }
    }
    return new Evaluation(findings, checks.runs());
  }

  private void checkCoverHeader(List<CheckResult> findings, HeaderChecks checks, ParsedDocument document,
      CoverHeaderScope coverScope, int sectionIndex, SectionModel section) {
    var types = new ArrayList<HeaderModel.Type>();
    if (section.firstPageHeaderEnabled()) types.add(HeaderModel.Type.FIRST);
    if (!section.firstPageHeaderEnabled() || coverScope.coverFirstPageVariantUncertain(sectionIndex)) {
      types.add(HeaderModel.Type.DEFAULT);
      if (section.evenPageHeaderEnabled()) types.add(HeaderModel.Type.EVEN);
    }
    List<HeaderModel.DisplayContent> content = types.stream().map(type -> document.headers().stream()
        .filter(header -> header.applicableSectionIndexesByType().getOrDefault(type, List.of()).contains(sectionIndex))
        .findFirst().map(HeaderModel::displayContent).orElse(HeaderModel.DisplayContent.EMPTY)).toList();
    boolean empty = content.stream().allMatch(value -> value == HeaderModel.DisplayContent.EMPTY);
    boolean present = content.stream().allMatch(value -> value == HeaderModel.DisplayContent.PRESENT);
    // A cover found after another section has no proven physical first-page variant.
    if (empty) checks.cover.checked();
    else if (present && sectionIndex == 0) {
      checks.cover.checked();
      findings.add(result("HEADER-01-COVER", "封面不显示页眉", "封面适用的页眉含有文字",
          "COVER_HEADER_FORBIDDEN", Judgement.FAIL, coverScope.firstCoverSource(), coverScope.firstCoverSource()));
    } else {
      checks.cover.unknown();
      findings.add(result("HEADER-01-COVER", "封面不显示页眉", "无法确认封面最终显示的页眉内容",
          "COVER_HEADER_UNCONFIRMED", Judgement.REVIEW, coverScope.firstCoverSource(), coverScope.firstCoverSource()));
    }
  }

  /** Records the exact candidates traversed above; a partial section mapping cannot certify later rules. */
  private static final class HeaderChecks {
    private final RunCounter scope = new RunCounter();
    private final RunCounter cover = new RunCounter();
    private final RunCounter missing = new RunCounter();
    private final RunCounter text = new RunCounter();
    private final RunCounter alignment = new RunCounter();
    private final RunCounter font = new RunCounter();
    private final RunCounter size = new RunCounter();

    private void recordHeader(HeaderModel header) {
      text.checked();
      if (header.centered() == null) alignment.unknown();
      else alignment.checked();
      if (header.runFormatting().isEmpty()) {
        font.unknown();
        size.unknown();
      } else {
        for (HeaderModel.RunFormatting run : header.runFormatting()) {
          if (run.asciiFont() == null || run.eastAsiaFont() == null) font.unknown();
          else font.checked();
          if (run.sizeHalfPoints() == null) size.unknown();
          else size.checked();
        }
      }
    }

    private List<RuleRun> runs() {
      var runs = new ArrayList<RuleRun>();
      scope.addRun(runs, "HEADER-01-SCOPE");
      cover.addRun(runs, "HEADER-01-COVER");
      if (scope.unknownCount > 0) return List.copyOf(runs);
      missing.addRun(runs, "HEADER-01-MISSING");
      text.addRun(runs, "HEADER-01-TEXT");
      alignment.addRun(runs, "HEADER-01-ALIGNMENT");
      font.addRun(runs, "HEADER-01-FONT");
      size.addRun(runs, "HEADER-01-SIZE");
      return List.copyOf(runs);
    }
  }

  private static final class RunCounter {
    private int candidateCount;
    private int checkedCount;
    private int unknownCount;

    private void checked() { candidateCount++; checkedCount++; }
    private void unknown() { candidateCount++; unknownCount++; }
    private void addRun(List<RuleRun> runs, String ruleId) {
      if (candidateCount > 0) runs.add(new RuleRun(ruleId, "HeaderRuleModule", candidateCount,
          checkedCount, unknownCount, null));
    }
  }

  private void checkHeader(List<CheckResult> findings, HeaderModel header, SourceAddress contextSource) {
    if (!EXPECTED_TEXT.equals(header.text())) {
      findings.add(result("HEADER-01-TEXT", EXPECTED_TEXT, header.text(),
          "HEADER_TEXT_MISMATCH", Judgement.FAIL, header.source(), contextSource));
    }
    if (header.centered() == null) {
      findings.add(result("HEADER-01-ALIGNMENT", "center", "unknown",
          "HEADER_ALIGNMENT_UNKNOWN", Judgement.REVIEW, header.source(), contextSource));
    } else if (!header.centered()) {
      findings.add(result("HEADER-01-ALIGNMENT", "center", "not centered",
          "HEADER_NOT_CENTERED", Judgement.FAIL, header.source(), contextSource));
    }
    if (header.runFormatting().isEmpty()) {
      findings.add(result("HEADER-01-FONT", EXPECTED_FONT, "unknown",
          "HEADER_FONT_UNKNOWN", Judgement.REVIEW, header.source(), contextSource));
      findings.add(result("HEADER-01-SIZE", String.valueOf(EXPECTED_SIZE_HALF_POINTS), "unknown",
          "HEADER_SIZE_UNKNOWN", Judgement.REVIEW, header.source(), contextSource));
    }
    for (HeaderModel.RunFormatting run : header.runFormatting()) {
      boolean fontMismatch = run.asciiFont() != null && !EXPECTED_FONT.equals(run.asciiFont())
          || run.eastAsiaFont() != null && !EXPECTED_FONT.equals(run.eastAsiaFont());
      if (fontMismatch) {
        findings.add(result("HEADER-01-FONT", EXPECTED_FONT,
            String.valueOf(run.asciiFont()) + " / " + String.valueOf(run.eastAsiaFont()),
            "HEADER_FONT_MISMATCH", Judgement.FAIL, run.source(), contextSource));
      }
      if (run.asciiFont() == null || run.eastAsiaFont() == null) {
        findings.add(result("HEADER-01-FONT", EXPECTED_FONT, "unknown",
            "HEADER_FONT_UNKNOWN", Judgement.REVIEW, run.source(), contextSource));
      }
      if (run.sizeHalfPoints() == null) {
        findings.add(result("HEADER-01-SIZE", String.valueOf(EXPECTED_SIZE_HALF_POINTS), "unknown",
            "HEADER_SIZE_UNKNOWN", Judgement.REVIEW, run.source(), contextSource));
      } else if (run.sizeHalfPoints() != EXPECTED_SIZE_HALF_POINTS) {
        findings.add(result("HEADER-01-SIZE", String.valueOf(EXPECTED_SIZE_HALF_POINTS),
            String.valueOf(run.sizeHalfPoints()), "HEADER_SIZE_MISMATCH", Judgement.FAIL, run.source(), contextSource));
      }
    }
  }

  /** Carries the checked section's scope; it does not assert a physical page or header variant. */
  private SourceAddress sectionContext(ParsedDocument document, CoverHeaderScope coverScope, int sectionIndex) {
    SourceAddress previousEnd = sectionIndex == 0 ? null : document.sections().get(sectionIndex - 1).source();
    SourceAddress sectionEnd = document.sections().get(sectionIndex).source();
    SourceAddress requiredStart = coverScope.firstRequiredSource();
    for (var paragraph : document.paragraphs()) {
      SourceAddress source = paragraph.source();
      if (source == null || !"word/document.xml".equals(source.partName()) || paragraph.insideTextBox()) continue;
      if (previousEnd != null && comparePath(source, previousEnd) <= 0) continue;
      if (comparePath(source, sectionEnd) > 0) continue;
      if (requiredStart != null && comparePath(source, requiredStart) < 0) continue;
      // An empty section keeps its own empty paragraph instead of borrowing prior text.
      return source;
    }
    return null;
  }

  private int comparePath(SourceAddress left, SourceAddress right) {
    int count = Math.min(left.elementPath().size(), right.elementPath().size());
    for (int index = 0; index < count; index++) {
      int comparison = Integer.compare(left.elementPath().get(index), right.elementPath().get(index));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(left.elementPath().size(), right.elementPath().size());
  }

  private CheckResult scopeUnknown(ParsedDocument document, SourceAddress sectionSource, String detail) {
    SourceAddress source = sectionSource != null ? sectionSource
        : document.paragraphs().isEmpty()
            ? new SourceAddress("word/document.xml", List.of(), 0, 0)
            : document.paragraphs().get(0).source();
    return result("HEADER-01-SCOPE", "可读取全部文档分节及页眉映射", detail,
        "HEADER_SCOPE_UNKNOWN", Judgement.REVIEW, source);
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return result(ruleId, expected, actual, reason, judgement, source, null);
  }

  private CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source, SourceAddress contextSource) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source, contextSource);
  }
}
