package cn.luwneshacha.thesis.document;

/** A parser-proven sibling fragment, without changing any physical table facts.
 * continuationSource is null for adjacent data fragments without an explicit 续表 table. */
public record TableFragmentEvidence(SourceAddress originalSource, SourceAddress continuationSource,
    Kind kind) {
  public enum Kind { DATA_ROW, REPEATED_HEADER }
}
