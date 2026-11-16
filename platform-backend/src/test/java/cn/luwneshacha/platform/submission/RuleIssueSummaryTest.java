package cn.luwneshacha.platform.submission;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RuleIssueSummaryTest {
  @Test void groupsOnlyFailuresAndUsesChineseCoverageDescription() {
    byte[] body = """
        {"report":{"results":[
          {"ruleId":"BODY-LINE","judgement":"FAIL"},
          {"ruleId":"BODY-LINE","judgement":"FAIL"},
          {"ruleId":"REF-COUNT","judgement":"FAIL"},
          {"ruleId":"BODY-FONT","judgement":"REVIEW"},
          {"ruleId":"BODY-INDENT","judgement":"PASS"}
        ],"ruleCoverage":[
          {"ruleId":"BODY-LINE","ruleDescription":"正文行距应为1.5倍"},
          {"ruleId":"REF-COUNT","ruleDescription":"参考文献不少于10篇"}
        ]}}
        """.getBytes(StandardCharsets.UTF_8);
    var summary = RuleIssueSummary.parse(body, 3);
    assertEquals(2, summary.issues().size());
    assertEquals(new RuleIssueSummary.Issue("BODY-LINE", "正文行距应为1.5倍", 2), summary.issues().get(0));
    assertEquals(new RuleIssueSummary.Issue("REF-COUNT", "参考文献不少于10篇", 1), summary.issues().get(1));
  }

  @Test void emptySuccessfulReportIsReadyWithNoIssueRows() {
    var summary = RuleIssueSummary.parse("{\"report\":{\"results\":[]}}".getBytes(StandardCharsets.UTF_8), 0);
    assertTrue(summary.issues().isEmpty());
  }

  @Test void malformedOrMismatchedReportsCannotBecomeZeroProblems() {
    assertThrows(IllegalArgumentException.class, () -> RuleIssueSummary.parse("{}".getBytes(StandardCharsets.UTF_8), 0));
    assertThrows(IllegalArgumentException.class, () -> RuleIssueSummary.parse(
        "{\"report\":{\"results\":[{\"ruleId\":\"R1\",\"judgement\":\"FAIL\"}]}}".getBytes(StandardCharsets.UTF_8), 0));
    assertThrows(IllegalArgumentException.class, () -> RuleIssueSummary.parse(
        "{\"report\":{\"results\":[{\"judgement\":\"FAIL\"}]}}".getBytes(StandardCharsets.UTF_8), 1));
  }
}
