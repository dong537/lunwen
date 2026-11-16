package cn.luwneshacha.platform.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A compact, validated index of FAIL findings in one engine response. */
public record RuleIssueSummary(List<Issue> issues) {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> JUDGEMENTS = Set.of("PASS", "FAIL", "REVIEW", "NOT_APPLICABLE");

  public RuleIssueSummary { issues = List.copyOf(issues); }
  public record Issue(String ruleId, String description, int count) {
    public Issue {
      if (ruleId == null || ruleId.isBlank() || ruleId.length() > 128 || count < 1)
        throw new IllegalArgumentException("invalid rule issue");
      if (description == null || description.isBlank() || description.length() > 500)
        throw new IllegalArgumentException("invalid rule description");
    }
  }

  public static RuleIssueSummary parse(byte[] bytes, int expectedFailures) {
    if (bytes == null || expectedFailures < 0) throw new IllegalArgumentException("invalid report");
    final JsonNode root;
    try { root = JSON.readTree(bytes); }
    catch (IOException error) { throw new IllegalArgumentException("invalid report JSON", error); }
    JsonNode report = root == null ? null : root.path("report");
    JsonNode findings = report == null ? null : report.path("results");
    if (findings == null || !findings.isArray()) throw new IllegalArgumentException("missing report findings");

    Map<String, String> descriptions = new LinkedHashMap<>();
    JsonNode coverage = report.path("ruleCoverage");
    if (coverage.isArray()) for (JsonNode entry : coverage) {
      String id = entry.path("ruleId").asText("").strip();
      String description = entry.path("ruleDescription").asText("").strip();
      if (!id.isEmpty() && !description.isEmpty()) descriptions.putIfAbsent(id, description);
    }

    Map<String, Integer> counts = new LinkedHashMap<>();
    int failures = 0;
    for (JsonNode finding : findings) {
      String judgement = finding.path("judgement").asText("");
      if (!JUDGEMENTS.contains(judgement)) throw new IllegalArgumentException("invalid finding judgement");
      if (!"FAIL".equals(judgement)) continue;
      failures++;
      String id = finding.path("ruleId").asText("").strip();
      if (id.isEmpty() || id.length() > 128) throw new IllegalArgumentException("missing finding rule ID");
      counts.merge(id, 1, Integer::sum);
    }
    if (failures != expectedFailures) throw new IllegalArgumentException("report problem count mismatch");
    List<Issue> issues = new ArrayList<>(counts.size());
    counts.forEach((id, count) -> {
      String description = descriptions.getOrDefault(id, "规则 " + id);
      if (description.length() > 500) description = description.substring(0, 500);
      issues.add(new Issue(id, description, count));
    });
    return new RuleIssueSummary(issues);
  }
}
