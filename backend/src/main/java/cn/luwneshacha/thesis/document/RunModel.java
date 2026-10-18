package cn.luwneshacha.thesis.document;

public record RunModel(
    String text,
    String asciiFont,
    String highAnsiFont,
    String eastAsiaFont,
    String complexFont,
    Integer fontSizeHalfPoints,
    Integer complexFontSizeHalfPoints,
    Boolean bold,
    String verticalAlignment,
    SourceAddress source) {
  public RunModel(String text, String asciiFont, String highAnsiFont, String eastAsiaFont,
      String complexFont, Integer fontSizeHalfPoints, Boolean bold,
      String verticalAlignment, SourceAddress source) {
    this(text, asciiFont, highAnsiFont, eastAsiaFont, complexFont, fontSizeHalfPoints, null,
        bold, verticalAlignment, source);
  }

  public RunModel(String text, String asciiFont, String eastAsiaFont, String complexFont,
      Integer fontSizeHalfPoints, Boolean bold, SourceAddress source) {
    this(text, asciiFont, asciiFont, eastAsiaFont, complexFont, fontSizeHalfPoints, null,
        bold, null, source);
  }

  public RunModel(String text, String asciiFont, String eastAsiaFont, String complexFont,
      Integer fontSizeHalfPoints, Boolean bold, String verticalAlignment, SourceAddress source) {
    this(text, asciiFont, asciiFont, eastAsiaFont, complexFont, fontSizeHalfPoints, null, bold,
        verticalAlignment, source);
  }
}
