package cn.luwneshacha.thesis.document;

import java.util.List;

public record ParsedDocument(
    String sourceSha256,
    List<ParagraphModel> paragraphs,
    List<SectionModel> sections,
    List<String> diagnostics,
    List<TableModel> tables,
    List<EquationModel> equations,
    List<ReferenceEntryModel> references,
    List<ImageModel> images,
    List<PageNumberModel> pageNumbers) {
  public ParsedDocument {
    paragraphs = List.copyOf(paragraphs);
    sections = List.copyOf(sections);
    diagnostics = List.copyOf(diagnostics);
    tables = List.copyOf(tables);
    equations = List.copyOf(equations);
    references = List.copyOf(references);
    images = List.copyOf(images);
    pageNumbers = List.copyOf(pageNumbers);
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics) {
    this(sourceSha256, paragraphs, sections, diagnostics, List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs, List<SectionModel> sections,
      List<String> diagnostics, List<TableModel> tables, List<EquationModel> equations,
      List<ReferenceEntryModel> references) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references, List.of(), List.of());
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs, List<SectionModel> sections,
      List<String> diagnostics, List<TableModel> tables, List<EquationModel> equations,
      List<ReferenceEntryModel> references, List<ImageModel> images) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references, images, List.of());
  }
}
