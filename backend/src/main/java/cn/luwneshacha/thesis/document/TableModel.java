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
    boolean repeatedPlaceholderText) {
  public TableModel(SourceAddress source, SourceAddress captionSource, String captionText,
      int rows, int columns, boolean topBorder, boolean middleBorder, boolean bottomBorder,
      boolean verticalBorders, boolean captionBefore, boolean layoutNeedsReview) {
    this(source, captionSource, captionText, rows, columns, topBorder, middleBorder, bottomBorder,
        verticalBorders, captionBefore, layoutNeedsReview, false, List.of(), List.of(), List.of(), null, null, false);
  }

  public TableModel {
    columnWidthsTwips = List.copyOf(columnWidthsTwips == null ? List.of() : columnWidthsTwips);
    rowHeightsTwips = List.copyOf(rowHeightsTwips == null ? List.of() : rowHeightsTwips);
    headerTexts = List.copyOf(headerTexts == null ? List.of() : headerTexts);
  }
}
