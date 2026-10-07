package cn.luwneshacha.thesis.document;

import java.util.List;

public record ParsedDocument(
    String sourceSha256,
    List<ParagraphModel> paragraphs,
    List<SectionModel> sections,
    List<String> diagnostics) {
  public ParsedDocument {
    paragraphs = List.copyOf(paragraphs);
    sections = List.copyOf(sections);
    diagnostics = List.copyOf(diagnostics);
  }
}
