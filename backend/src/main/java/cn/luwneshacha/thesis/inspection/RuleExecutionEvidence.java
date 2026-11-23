package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.rules.RuleRun;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;

/**
 * Records which registered check modules completed in this run.
 * The legacy evaluatedRuleIds field means all owner modules finished; it is
 * not proof of a terminal decision for each rule. An absent finding stays
 * absent and must not be interpreted as PASS or NOT_APPLICABLE.
 */
public record RuleExecutionEvidence(int schemaVersion, List<String> catalogRuleIds,
    List<String> evaluatedRuleIds, List<String> completedModuleIds, String catalogSha256,
    String ownerPairsSha256, List<RuleRun> ruleRuns) {
  public RuleExecutionEvidence {
    catalogRuleIds = List.copyOf(catalogRuleIds);
    evaluatedRuleIds = List.copyOf(evaluatedRuleIds);
    completedModuleIds = List.copyOf(completedModuleIds);
    ruleRuns = List.copyOf(ruleRuns);
  }

  public static RuleExecutionEvidence from(List<String> completedModuleIds) {
    return from(completedModuleIds, List.of());
  }

  public static RuleExecutionEvidence from(List<String> completedModuleIds, List<RuleRun> reportedRuns) {
    var completed = new LinkedHashSet<>(completedModuleIds);
    if (completed.size() != completedModuleIds.size())
      throw new IllegalArgumentException("completed module ids must be unique");
    var catalog = RuleCatalog.entries();
    var catalogRuleIds = catalog.stream().map(RuleCatalog.Entry::ruleId).toList();
    var evaluatedRuleIds = new ArrayList<String>();
    var ownerPairs = new ArrayList<String>();
    for (var entry : catalog) {
      var owners = RuleCatalog.ownerModuleIds(entry.ruleId());
      if (owners.isEmpty()) throw new IllegalStateException("catalog rule has no owner: " + entry.ruleId());
      for (String owner : owners) ownerPairs.add(entry.ruleId() + "@" + owner);
      if (completed.containsAll(owners)) evaluatedRuleIds.add(entry.ruleId());
    }
    var byOwner = new LinkedHashMap<String, RuleRun>();
    for (RuleRun run : reportedRuns) {
      String key = run.ruleId() + "@" + run.moduleId();
      if (!ownerPairs.contains(key) || !completed.contains(run.moduleId()))
        throw new IllegalArgumentException("rule run has no completed catalog owner: " + key);
      if (byOwner.putIfAbsent(key, run) != null)
        throw new IllegalArgumentException("duplicate rule run: " + key);
    }
    var orderedRuns = new ArrayList<RuleRun>();
    for (String pair : ownerPairs) {
      RuleRun run = byOwner.get(pair);
      if (run != null) orderedRuns.add(run);
    }
    return new RuleExecutionEvidence(2, catalogRuleIds, evaluatedRuleIds, completedModuleIds,
        sha256(catalogRuleIds), sha256(ownerPairs), orderedRuns);
  }

  /** SHA-256 over the ordered IDs joined with a single LF byte, with no trailing LF. */
  private static String sha256(List<String> ids) {
    try {
      byte[] bytes = String.join("\n", ids).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JVM does not support SHA-256", error);
    }
  }
}
