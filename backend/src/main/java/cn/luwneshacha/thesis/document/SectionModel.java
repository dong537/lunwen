package cn.luwneshacha.thesis.document;

public record SectionModel(
    Integer topMarginTwips,
    Integer rightMarginTwips,
    Integer bottomMarginTwips,
    Integer leftMarginTwips,
    Integer gutterTwips,
    SourceAddress source,
    String pageNumberFormat,
    boolean pageNumberFormatInvalid,
    Integer pageNumberStart,
    boolean pageNumberStartInvalid,
    boolean terminal,
    boolean topMarginEvidenceInvalid,
    boolean rightMarginEvidenceInvalid,
    boolean bottomMarginEvidenceInvalid,
    boolean leftMarginEvidenceInvalid,
    boolean firstPageHeaderEnabled,
    boolean evenPageHeaderEnabled) {
  public SectionModel(Integer top, Integer right, Integer bottom, Integer left, Integer gutter,
      SourceAddress source, String pageNumberFormat, boolean pageNumberFormatInvalid, Integer pageNumberStart,
      boolean pageNumberStartInvalid, boolean terminal, boolean marginEvidenceInvalid) {
    this(top, right, bottom, left, gutter, source, pageNumberFormat, pageNumberFormatInvalid, pageNumberStart,
        pageNumberStartInvalid, terminal, marginEvidenceInvalid, marginEvidenceInvalid,
        marginEvidenceInvalid, marginEvidenceInvalid, false, false);
  }

  public SectionModel(Integer top, Integer right, Integer bottom, Integer left, Integer gutter,
      SourceAddress source, String pageNumberFormat, boolean pageNumberFormatInvalid, Integer pageNumberStart,
      boolean pageNumberStartInvalid, boolean terminal, boolean marginEvidenceInvalid,
      boolean firstPageHeaderEnabled, boolean evenPageHeaderEnabled) {
    this(top, right, bottom, left, gutter, source, pageNumberFormat, pageNumberFormatInvalid, pageNumberStart,
        pageNumberStartInvalid, terminal, marginEvidenceInvalid, marginEvidenceInvalid,
        marginEvidenceInvalid, marginEvidenceInvalid, firstPageHeaderEnabled, evenPageHeaderEnabled);
  }

  public boolean marginEvidenceInvalid() {
    return topMarginEvidenceInvalid || rightMarginEvidenceInvalid
        || bottomMarginEvidenceInvalid || leftMarginEvidenceInvalid;
  }

  public SectionModel(Integer top, Integer right, Integer bottom, Integer left, SourceAddress source,
      String pageNumberFormat, Integer pageNumberStart) {
    this(top, right, bottom, left, 0, source, pageNumberFormat, false, pageNumberStart,
        false, false, false, false, false, false, false, false);
  }

  public SectionModel(Integer top, Integer right, Integer bottom, Integer left, SourceAddress source,
      String pageNumberFormat, Integer pageNumberStart, boolean terminal) {
    this(top, right, bottom, left, 0, source, pageNumberFormat, false, pageNumberStart,
        false, terminal, false, false, false, false, false, false);
  }

  public SectionModel(Integer top, Integer right, Integer bottom, Integer left, SourceAddress source) {
    this(top, right, bottom, left, 0, source, null, false, null, false, false,
        false, false, false, false, false, false);
  }
}
