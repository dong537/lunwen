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
    List<PageNumberModel> pageNumbers,
    List<HeadingBreakModel> chapterBreaks,
    List<HeaderModel> headers,
    List<AutomaticNumberingModel> automaticNumbering,
    boolean tableInventoryComplete,
    List<EquationParser.PossibleObject> possibleEquationObjectSources,
    boolean equationInventoryComplete,
    List<FooterPageEvidence> footerPageEvidence,
    boolean imageInventoryComplete) {
  public ParsedDocument {
    paragraphs = List.copyOf(paragraphs);
    sections = List.copyOf(sections);
    diagnostics = List.copyOf(diagnostics);
    tables = List.copyOf(tables);
    equations = List.copyOf(equations);
    references = List.copyOf(references);
    images = List.copyOf(images);
    pageNumbers = List.copyOf(pageNumbers);
    chapterBreaks = List.copyOf(chapterBreaks);
    headers = List.copyOf(headers);
    automaticNumbering = List.copyOf(automaticNumbering);
    possibleEquationObjectSources = List.copyOf(possibleEquationObjectSources);
    footerPageEvidence = List.copyOf(footerPageEvidence);
  }

  /** Older callers have no audited graphical-object inventory. */
  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics, List<TableModel> tables,
      List<EquationModel> equations, List<ReferenceEntryModel> references,
      List<ImageModel> images, List<PageNumberModel> pageNumbers,
      List<HeadingBreakModel> chapterBreaks, List<HeaderModel> headers,
      List<AutomaticNumberingModel> automaticNumbering, boolean tableInventoryComplete,
      List<EquationParser.PossibleObject> possibleEquationObjectSources,
      boolean equationInventoryComplete, List<FooterPageEvidence> footerPageEvidence) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references,
        images, pageNumbers, chapterBreaks, headers, automaticNumbering,
        tableInventoryComplete, possibleEquationObjectSources, equationInventoryComplete,
        footerPageEvidence, false);
  }

  /** Hand-built documents and older call sites have no audited footer inventory. */
  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics, List<TableModel> tables,
      List<EquationModel> equations, List<ReferenceEntryModel> references,
      List<ImageModel> images, List<PageNumberModel> pageNumbers,
      List<HeadingBreakModel> chapterBreaks, List<HeaderModel> headers,
      List<AutomaticNumberingModel> automaticNumbering, boolean tableInventoryComplete,
      List<EquationParser.PossibleObject> possibleEquationObjectSources,
      boolean equationInventoryComplete) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references,
        images, pageNumbers, chapterBreaks, headers, automaticNumbering,
        tableInventoryComplete, possibleEquationObjectSources, equationInventoryComplete, List.of());
  }

  /** Existing hand-built documents do not carry an audited formula-object inventory. */
  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics, List<TableModel> tables,
      List<EquationModel> equations, List<ReferenceEntryModel> references,
      List<ImageModel> images, List<PageNumberModel> pageNumbers,
      List<HeadingBreakModel> chapterBreaks, List<HeaderModel> headers,
      List<AutomaticNumberingModel> automaticNumbering, boolean tableInventoryComplete) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references,
        images, pageNumbers, chapterBreaks, headers, automaticNumbering,
        tableInventoryComplete, List.of(), false);
  }

  /** Hand-built documents have no raw XML table inventory to reconcile. */
  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics, List<TableModel> tables,
      List<EquationModel> equations, List<ReferenceEntryModel> references,
      List<ImageModel> images, List<PageNumberModel> pageNumbers,
      List<HeadingBreakModel> chapterBreaks, List<HeaderModel> headers,
      List<AutomaticNumberingModel> automaticNumbering) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references,
        images, pageNumbers, chapterBreaks, headers, automaticNumbering, false);
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics, List<TableModel> tables,
      List<EquationModel> equations, List<ReferenceEntryModel> references,
      List<ImageModel> images, List<PageNumberModel> pageNumbers,
      List<HeadingBreakModel> chapterBreaks, List<HeaderModel> headers) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references,
        images, pageNumbers, chapterBreaks, headers, List.of());
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics) {
    this(sourceSha256, paragraphs, sections, diagnostics, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs, List<SectionModel> sections,
      List<String> diagnostics, List<TableModel> tables, List<EquationModel> equations,
      List<ReferenceEntryModel> references) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references, List.of(), List.of(), List.of(), List.of());
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs, List<SectionModel> sections,
      List<String> diagnostics, List<TableModel> tables, List<EquationModel> equations,
      List<ReferenceEntryModel> references, List<ImageModel> images) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references, images, List.of(), List.of(), List.of());
  }

  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs, List<SectionModel> sections,
      List<String> diagnostics, List<TableModel> tables, List<EquationModel> equations,
      List<ReferenceEntryModel> references, List<ImageModel> images, List<PageNumberModel> pageNumbers) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references, images, pageNumbers, List.of(), List.of());
  }

  /** Compatibility overload retaining the pre-header model constructor. */
  public ParsedDocument(String sourceSha256, List<ParagraphModel> paragraphs,
      List<SectionModel> sections, List<String> diagnostics, List<TableModel> tables,
      List<EquationModel> equations, List<ReferenceEntryModel> references,
      List<ImageModel> images, List<PageNumberModel> pageNumbers,
      List<HeadingBreakModel> chapterBreaks) {
    this(sourceSha256, paragraphs, sections, diagnostics, tables, equations, references,
        images, pageNumbers, chapterBreaks, List.of());
  }
}
