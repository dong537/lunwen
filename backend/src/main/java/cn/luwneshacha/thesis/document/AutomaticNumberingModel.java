package cn.luwneshacha.thesis.document;

import java.util.Objects;

/** Numbering evidence anchored to an actual paragraph, independent of its rendered label. */
public record AutomaticNumberingModel(SourceAddress source, Kind kind) {
  public enum Kind { NUMBERED_LIST, SEQUENCE_FIELD, LISTNUM_FIELD, UNKNOWN_LIST }

  public AutomaticNumberingModel {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(kind, "kind");
  }
}
