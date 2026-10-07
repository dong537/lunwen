package cn.luwneshacha.thesis.inspection;

public record RuleSet(String id) {
  public RuleSet {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("rule set id is required");
  }

  public static RuleSet schoolV1() { return new RuleSet("school-v1"); }
}