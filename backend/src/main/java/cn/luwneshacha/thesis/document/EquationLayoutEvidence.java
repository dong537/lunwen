package cn.luwneshacha.thesis.document;

/** XML evidence for the physical arrangement of a formula and its number. */
public record EquationLayoutEvidence(String kind, Boolean formulaCentered, Boolean numberRightAligned,
    boolean borderless, SourceAddress numberSource) {
  public static EquationLayoutEvidence unknown(SourceAddress source) {
    return new EquationLayoutEvidence("UNKNOWN", null, null, false, source);
  }
}
