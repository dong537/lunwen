package cn.luwneshacha.thesis.document;

/** Formatting evidence read from the XML run that contains an equation number. */
public record EquationNumberEvidence(
    String text,
    boolean parenthesized,
    String fontName,
    Integer fontSizeHalfPoints,
    SourceAddress source) {}
