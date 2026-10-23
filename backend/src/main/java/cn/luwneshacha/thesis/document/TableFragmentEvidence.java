package cn.luwneshacha.thesis.document;

/** A parser-proven sibling fragment, without changing any physical table facts. */
public record TableFragmentEvidence(SourceAddress originalSource, SourceAddress continuationSource,
    Kind kind) {
  public enum Kind { DATA_ROW, REPEATED_HEADER }
}
