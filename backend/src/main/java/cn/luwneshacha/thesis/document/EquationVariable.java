package cn.luwneshacha.thesis.document;

/** A mathematical symbol and its script structure as represented in OMML. */
public record EquationVariable(String base, String subscript, String superscript,
    String identifier, SourceAddress source, Boolean baseItalic) {
  public EquationVariable(String base, String subscript, String superscript,
      String identifier, SourceAddress source) {
    this(base, subscript, superscript, identifier, source, null);
  }
}
