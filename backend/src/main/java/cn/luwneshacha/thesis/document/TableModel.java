package cn.luwneshacha.thesis.document;

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
    boolean layoutNeedsReview) {}
