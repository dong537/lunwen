package cn.luwneshacha.thesis.document;

import java.util.List;

public record TableModel(
    SourceAddress source,
    SourceAddress captionSource,
    String captionText,
    int rows,
    int columns,
    boolean topBorder,
    boolean middleBorder,
    boolean bottomBorder,
    boolean verticalBorders,
    boolean captionBefore,
    boolean layoutNeedsReview,
    boolean mergedCells,
    List<Integer> columnWidthsTwips,
    List<Integer> rowHeightsTwips,
    List<String> headerTexts,
    String noteText,
    SourceAddress noteSource,
    boolean repeatedPlaceholderText,
    boolean hasTableStyle,
    String sharedUnitText,
    boolean sharedUnitRightAligned,
    boolean tableStyleBorderEvidenceResolved,
    boolean xmlNumericValuesValid,
    boolean codeLayout,
    List<List<String>> dataRowTexts,
    List<Integer> repeatingHeaderRowIndexes,
    TableFragmentEvidence fragmentEvidence) {
  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview,
      boolean mergedCells, List<Integer> columnWidthsTwips, List<Integer> rowHeightsTwips,
      List<String> headerTexts, String noteText, SourceAddress noteSource,
      boolean repeatedPlaceholderText, boolean hasTableStyle, String sharedUnitText,
      boolean sharedUnitRightAligned, boolean tableStyleBorderEvidenceResolved,
      boolean xmlNumericValuesValid, boolean codeLayout, List<List<String>> dataRowTexts,
      List<Integer> repeatingHeaderRowIndexes) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, mergedCells, columnWidthsTwips,
        rowHeightsTwips, headerTexts, noteText, noteSource, repeatedPlaceholderText, hasTableStyle,
        sharedUnitText, sharedUnitRightAligned, tableStyleBorderEvidenceResolved,
        xmlNumericValuesValid, codeLayout, dataRowTexts, repeatingHeaderRowIndexes, null);
  }

  public TableModel withFragmentEvidence(TableFragmentEvidence evidence) {
    return new TableModel(source, captionSource, captionText, rows, columns, topBorder, middleBorder,
        bottomBorder, verticalBorders, captionBefore, layoutNeedsReview, mergedCells,
        columnWidthsTwips, rowHeightsTwips, headerTexts, noteText, noteSource,
        repeatedPlaceholderText, hasTableStyle, sharedUnitText, sharedUnitRightAligned,
        tableStyleBorderEvidenceResolved, xmlNumericValuesValid, codeLayout, dataRowTexts,
        repeatingHeaderRowIndexes, evidence);
  }
  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview,
      boolean mergedCells, List<Integer> columnWidthsTwips, List<Integer> rowHeightsTwips,
      List<String> headerTexts, String noteText, SourceAddress noteSource,
      boolean repeatedPlaceholderText, boolean hasTableStyle, String sharedUnitText,
      boolean sharedUnitRightAligned, boolean tableStyleBorderEvidenceResolved,
      boolean xmlNumericValuesValid, boolean codeLayout, List<List<String>> dataRowTexts) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, mergedCells, columnWidthsTwips,
        rowHeightsTwips, headerTexts, noteText, noteSource, repeatedPlaceholderText, hasTableStyle,
        sharedUnitText, sharedUnitRightAligned, tableStyleBorderEvidenceResolved,
        xmlNumericValuesValid, codeLayout, dataRowTexts, List.of());
  }

  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview,
      boolean mergedCells, List<Integer> columnWidthsTwips, List<Integer> rowHeightsTwips,
      List<String> headerTexts, String noteText, SourceAddress noteSource,
      boolean repeatedPlaceholderText, boolean hasTableStyle, String sharedUnitText,
      boolean sharedUnitRightAligned, boolean tableStyleBorderEvidenceResolved,
      boolean xmlNumericValuesValid, boolean codeLayout) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, mergedCells, columnWidthsTwips,
        rowHeightsTwips, headerTexts, noteText, noteSource, repeatedPlaceholderText, hasTableStyle,
        sharedUnitText, sharedUnitRightAligned, tableStyleBorderEvidenceResolved,
        xmlNumericValuesValid, codeLayout, List.of());
  }
  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview,
      boolean mergedCells, List<Integer> columnWidthsTwips, List<Integer> rowHeightsTwips,
      List<String> headerTexts, String noteText, SourceAddress noteSource,
      boolean repeatedPlaceholderText, boolean hasTableStyle) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, mergedCells, columnWidthsTwips,
        rowHeightsTwips, headerTexts, noteText, noteSource, repeatedPlaceholderText, hasTableStyle,
        null, false, !hasTableStyle, true, false);
  }

  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview,
      boolean mergedCells, List<Integer> columnWidthsTwips, List<Integer> rowHeightsTwips,
      List<String> headerTexts, String noteText, SourceAddress noteSource,
      boolean repeatedPlaceholderText, boolean hasTableStyle, String sharedUnitText,
      boolean sharedUnitRightAligned) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, mergedCells, columnWidthsTwips,
        rowHeightsTwips, headerTexts, noteText, noteSource, repeatedPlaceholderText, hasTableStyle,
        sharedUnitText, sharedUnitRightAligned, !hasTableStyle, true, false);
  }

  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview,
      boolean mergedCells, List<Integer> columnWidthsTwips, List<Integer> rowHeightsTwips,
      List<String> headerTexts, String noteText, SourceAddress noteSource,
      boolean repeatedPlaceholderText, boolean hasTableStyle, String sharedUnitText,
      boolean sharedUnitRightAligned, boolean tableStyleBorderEvidenceResolved) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, mergedCells, columnWidthsTwips,
        rowHeightsTwips, headerTexts, noteText, noteSource, repeatedPlaceholderText, hasTableStyle,
        sharedUnitText, sharedUnitRightAligned, tableStyleBorderEvidenceResolved, true, false);
  }

  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview,
      boolean mergedCells, List<Integer> columnWidthsTwips, List<Integer> rowHeightsTwips,
      List<String> headerTexts, String noteText, SourceAddress noteSource,
      boolean repeatedPlaceholderText) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, mergedCells, columnWidthsTwips,
        rowHeightsTwips, headerTexts, noteText, noteSource, repeatedPlaceholderText, false,
        null, false, true, true, false);
  }

  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, false, List.of(), List.of(), List.of(),
        null, null, false, false, null, false, true, true, false);
  }

  public TableModel {
    dataRowTexts = dataRowTexts.stream().map(List::copyOf).toList();
    columnWidthsTwips = List.copyOf(columnWidthsTwips == null ? List.of() : columnWidthsTwips);
    rowHeightsTwips = List.copyOf(rowHeightsTwips == null ? List.of() : rowHeightsTwips);
    headerTexts = List.copyOf(headerTexts == null ? List.of() : headerTexts);
    repeatingHeaderRowIndexes = List.copyOf(
        repeatingHeaderRowIndexes == null ? List.of() : repeatingHeaderRowIndexes);
  }
}
