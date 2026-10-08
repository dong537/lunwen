package cn.luwneshacha.thesis.document;

public record SectionModel(
    Integer topMarginTwips,
    Integer rightMarginTwips,
    Integer bottomMarginTwips,
    Integer leftMarginTwips,
    SourceAddress source,
    String pageNumberFormat,
    Integer pageNumberStart) {
  public SectionModel(Integer top, Integer right, Integer bottom, Integer left, SourceAddress source) {
    this(top, right, bottom, left, source, null, null);
  }
}
