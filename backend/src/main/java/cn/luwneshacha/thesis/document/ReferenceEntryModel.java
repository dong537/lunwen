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
    String pagesText) {
  public ReferenceEntryModel(SourceAddress source, String number, String text,
      String category, boolean needsReview) {
    this(source, number, text, category, needsReview, null, null, null, null, null);
  }
}
