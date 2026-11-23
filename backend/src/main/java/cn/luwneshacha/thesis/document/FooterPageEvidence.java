package cn.luwneshacha.thesis.document;

/** Audited PAGE-field inventory for one active footer variant in one section. */
public record FooterPageEvidence(int sectionIndex, PageNumberModel.FooterVariant variant,
    Origin origin, String partName, Presence presence) {
  public FooterPageEvidence {
    if (sectionIndex < 0 || variant == null || origin == null || presence == null)
      throw new IllegalArgumentException("footer evidence requires a section, variant, origin and presence");
    if ((origin == Origin.NONE) != (partName == null))
      throw new IllegalArgumentException("footer part must match its origin");
  }

  public enum Origin { EXPLICIT, INHERITED, NONE }

  public enum Presence { PRESENT, ABSENT, UNKNOWN }
}
