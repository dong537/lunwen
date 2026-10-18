package cn.luwneshacha.thesis.reporting;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Judgement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Selects annotation representatives and maps their write outcomes back to the original findings. */
public final class AnnotationFindings {
  private final List<CheckResult> findings;
  private final Map<Identity, CheckResult> representatives;

  private AnnotationFindings(List<CheckResult> results) {
    findings = results.stream().filter(result -> result.judgement() == Judgement.FAIL).toList();
    representatives = new LinkedHashMap<>();
    for (CheckResult finding : findings) representatives.putIfAbsent(Identity.of(finding), finding);
  }

  public static AnnotationFindings select(List<CheckResult> results) {
    return new AnnotationFindings(results);
  }

  public List<CheckResult> representatives() {
    return List.copyOf(representatives.values());
  }

  public Map<CheckResult, AnnotationItem> match(AnnotationReport report) {
    if (report == null) return Map.of();
    Map<Anchor, List<AnnotationItem>> byAnchor = new LinkedHashMap<>();
    for (AnnotationItem item : report.items()) {
      byAnchor.computeIfAbsent(new Anchor(item.ruleId(), item.source()), ignored -> new ArrayList<>()).add(item);
    }
    Map<Anchor, Set<Identity>> identitiesByAnchor = new LinkedHashMap<>();
    for (CheckResult finding : findings) {
      identitiesByAnchor.computeIfAbsent(new Anchor(finding.ruleId(), finding.source()),
          ignored -> new HashSet<>()).add(Identity.of(finding));
    }
    Map<Anchor, AnnotationItem> resolved = new LinkedHashMap<>();
    for (var entry : identitiesByAnchor.entrySet()) {
      List<AnnotationItem> items = byAnchor.get(entry.getKey());
      if (items == null || items.size() < entry.getValue().size()) continue;
      AnnotationItem first = items.getFirst();
      // Outcomes do not carry finding identities, and CommentWriter reorders them by status.
      // Shared anchors are unambiguous only when every identity has the same recorded outcome.
      if (items.stream().allMatch(item -> item.status() == first.status()
          && Objects.equals(item.reason(), first.reason()))) resolved.put(entry.getKey(), first);
    }
    Map<CheckResult, AnnotationItem> matches = new LinkedHashMap<>();
    for (CheckResult finding : findings) {
      CheckResult representative = representatives.get(Identity.of(finding));
      Anchor exact = new Anchor(finding.ruleId(), finding.source());
      // An explicit range outcome, including a failure, takes precedence over merged coverage.
      AnnotationItem item = byAnchor.containsKey(exact) ? resolved.get(exact)
          : resolved.get(new Anchor(representative.ruleId(), representative.source()));
      if (item != null) matches.put(finding, item);
    }
    return Map.copyOf(matches);
  }

  private record Anchor(String ruleId, SourceAddress source) { }

  private record Identity(String ruleId, String partName, List<Integer> elementPath,
      String expected, String actual, String reasonCode) {
    private static Identity of(CheckResult finding) {
      SourceAddress source = finding.source();
      return new Identity(finding.ruleId(), source == null ? null : source.partName(),
          source == null ? null : source.elementPath(), finding.expected(), finding.actual(), finding.reasonCode());
    }
  }
}
