package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnnotationFindingsTest {
  @ParameterizedTest
  @EnumSource(AnnotationStatus.class)
  void mergedCharacterRangesShareTheRepresentativeOutcome(AnnotationStatus status) {
    CheckResult first = finding(49, 50);
    CheckResult merged = finding(50, 55);
    AnnotationItem outcome = new AnnotationItem(first.ruleId(), status, "ANCHOR_RESULT", first.source());
    AnnotationFindings selected = AnnotationFindings.select(List.of(first, merged));

    assertEquals(List.of(first), selected.representatives());
    var matches = selected.match(new AnnotationReport(List.of(outcome), true));
    assertEquals(2, matches.size());
    assertEquals(outcome, matches.get(first));
    assertEquals(outcome, matches.get(merged));
  }

  @ParameterizedTest
  @ValueSource(strings = {"expected", "actual", "reason"})
  void distinctFindingIdentityInTheSameParagraphDoesNotBorrowTheWrittenOutcome(String difference) {
    CheckResult first = finding(0, 1);
    CheckResult distinct = new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        first.judgement(), difference.equals("expected") ? "黑体" : first.expected(),
        difference.equals("actual") ? "仿宋" : first.actual(),
        difference.equals("reason") ? "OTHER_FONT_MISMATCH" : first.reasonCode(), source(2, 3));
    AnnotationItem written = written(first);
    AnnotationFindings selected = AnnotationFindings.select(List.of(first, distinct));

    assertEquals(List.of(first, distinct), selected.representatives());
    var matches = selected.match(new AnnotationReport(List.of(written), true));
    assertEquals(written, matches.get(first));
    assertFalse(matches.containsKey(distinct));
  }

  @Test
  void incompleteRecordsAtASharedExactSourceDoNotGuessWhichFindingWasWritten() {
    CheckResult first = finding(0, 1);
    CheckResult distinct = distinctFindingAtSameSource(first);

    var matches = AnnotationFindings.select(List.of(first, distinct))
        .match(new AnnotationReport(List.of(written(first)), true));

    assertTrue(matches.isEmpty());
  }

  @Test
  void conflictingStatusesAtASharedExactSourceDoNotAssignAnArbitraryOutcome() {
    CheckResult first = finding(0, 1);
    CheckResult distinct = distinctFindingAtSameSource(first);

    var matches = AnnotationFindings.select(List.of(first, distinct)).match(new AnnotationReport(List.of(
        written(first), new AnnotationItem(distinct.ruleId(), AnnotationStatus.REJECTED,
            "WRITE_FAILED", distinct.source())), true));

    assertTrue(matches.isEmpty());
  }

  @Test
  void conflictingReasonsAtASharedExactSourceDoNotAssignAnArbitraryOutcome() {
    CheckResult first = finding(0, 1);
    CheckResult distinct = distinctFindingAtSameSource(first);

    var matches = AnnotationFindings.select(List.of(first, distinct)).match(new AnnotationReport(List.of(
        new AnnotationItem(first.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, "EMPTY_ANCHOR", first.source()),
        new AnnotationItem(distinct.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR,
            "SOURCE_RANGE_OUT_OF_BOUNDS", distinct.source())), true));

    assertTrue(matches.isEmpty());
  }

  @Test
  void completeConsistentRecordsAtASharedExactSourceKeepBothOutcomes() {
    CheckResult first = finding(0, 1);
    CheckResult distinct = distinctFindingAtSameSource(first);
    AnnotationItem written = written(first);

    var matches = AnnotationFindings.select(List.of(first, distinct))
        .match(new AnnotationReport(List.of(written, written), true));

    assertEquals(2, matches.size());
    assertEquals(written, matches.get(first));
    assertEquals(written, matches.get(distinct));
  }

  @Test
  void explicitRangeFailureTakesPrecedenceOverTheRepresentativeSuccess() {
    CheckResult first = finding(49, 50);
    CheckResult merged = finding(50, 55);
    AnnotationItem written = written(first);
    AnnotationItem failure = new AnnotationItem(merged.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR,
        "SOURCE_RANGE_OUT_OF_BOUNDS", merged.source());

    var matches = AnnotationFindings.select(List.of(first, merged))
        .match(new AnnotationReport(List.of(written, failure), true));

    assertEquals(written, matches.get(first));
    assertEquals(failure, matches.get(merged));
  }

  @Test
  void missingAnnotationRemainsMissingForEveryMergedFinding() {
    AnnotationFindings findings = AnnotationFindings.select(List.of(finding(49, 50), finding(50, 55)));

    assertTrue(findings.match(new AnnotationReport(List.of(), true)).isEmpty());
    assertTrue(findings.match(null).isEmpty());
  }

  @Test
  void aDifferentDocumentPartCannotBorrowTheOutcomeFromTheMainDocument() {
    CheckResult first = finding(0, 1);
    CheckResult footer = new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        first.judgement(), first.expected(), first.actual(), first.reasonCode(),
        new SourceAddress("word/footer1.xml", first.source().elementPath(), 0, 1));
    var selected = AnnotationFindings.select(List.of(first, footer));

    var matches = selected.match(new AnnotationReport(List.of(written(first)), true));

    assertEquals(List.of(first, footer), selected.representatives());
    assertFalse(matches.containsKey(footer));
  }

  @Test
  void aDifferentParagraphCannotBorrowTheOutcomeFromAnotherParagraph() {
    CheckResult first = finding(0, 1);
    CheckResult otherParagraph = new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        first.judgement(), first.expected(), first.actual(), first.reasonCode(),
        new SourceAddress("word/document.xml", List.of(0, 197), 0, 1));
    var selected = AnnotationFindings.select(List.of(first, otherParagraph));

    var matches = selected.match(new AnnotationReport(List.of(written(first)), true));

    assertEquals(List.of(first, otherParagraph), selected.representatives());
    assertFalse(matches.containsKey(otherParagraph));
  }

  @Test
  void onlyConfirmedProblemsBecomeAnnotationRepresentatives() {
    CheckResult first = finding(0, 1);
    CheckResult pass = new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        Judgement.PASS, first.expected(), first.actual(), first.reasonCode(), first.source());
    CheckResult review = new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        Judgement.REVIEW, first.expected(), first.actual(), first.reasonCode(), first.source());
    var selected = AnnotationFindings.select(List.of(pass, first, review));

    assertEquals(List.of(first), selected.representatives());
    assertEquals(1, selected.match(new AnnotationReport(List.of(written(first)), true)).size());
  }

  private static CheckResult distinctFindingAtSameSource(CheckResult first) {
    return new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        first.judgement(), first.expected(), "仿宋", first.reasonCode(), first.source());
  }

  private static AnnotationItem written(CheckResult result) {
    return new AnnotationItem(result.ruleId(), AnnotationStatus.WRITTEN, "", result.source());
  }

  private static CheckResult finding(int start, int end) {
    return new CheckResult("BODY-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "楷体", "FONT_MISMATCH", source(start, end));
  }

  private static SourceAddress source(int start, int end) {
    return new SourceAddress("word/document.xml", List.of(0, 196), start, end);
  }
}


