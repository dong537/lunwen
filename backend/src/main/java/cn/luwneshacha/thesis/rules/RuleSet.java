package cn.luwneshacha.thesis.rules;

public record RuleSet(
    String id,
    int expectedMarginTwips,
    String expectedLatinFont,
    String expectedEastAsiaFont,
    int expectedSizeHalfPoints,
    int expectedFirstLineChars) {
  public RuleSet {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("rule set id is required");
  }

  public static RuleSet schoolV1() {
    return new RuleSet("school-v1", 1474, "Times New Roman", "宋体", 24, 200);
  }
}
