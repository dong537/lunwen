package cn.luwneshacha.thesis.document;

public record ReferenceEntryModel(
    SourceAddress source,
    String number,
    String text,
    String category,
    boolean needsReview,
    String authorText,
    String titleText,
    String sourceText,
    String yearText,
    String pagesText,
    String accessDateText,
    String urlText,
    Integer numberingSeparatorSpaces,
    Integer numberingFormatTrailingSpaces,
    boolean automaticNumbering) {
  public ReferenceEntryModel(SourceAddress source, String number, String text,
      String category, boolean needsReview, String authorText, String titleText,
      String sourceText, String yearText, String pagesText) {
    this(source, number, text, category, needsReview, authorText, titleText, sourceText,
        yearText, pagesText, null, null, null, null, false);
  }

  public ReferenceEntryModel(SourceAddress source, String number, String text,
      String category, boolean needsReview) {
    this(source, number, text, category, needsReview, null, null, null, null, null, null, null,
        null, null, false);
  }
}
