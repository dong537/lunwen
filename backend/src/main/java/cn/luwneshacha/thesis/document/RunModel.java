package cn.luwneshacha.thesis.document;

public record RunModel(
    String text,
    String asciiFont,
    String eastAsiaFont,
    String complexFont,
    Integer fontSizeHalfPoints,
    Boolean bold,
    String verticalAlignment,
    SourceAddress source) {
  public RunModel(String text, String asciiFont, String eastAsiaFont, String complexFont,
      Integer fontSizeHalfPoints, Boolean bold, SourceAddress source) {
    this(text, asciiFont, eastAsiaFont, complexFont, fontSizeHalfPoints, bold, null, source);
  }
}
