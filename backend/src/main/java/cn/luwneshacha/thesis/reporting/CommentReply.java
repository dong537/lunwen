package cn.luwneshacha.thesis.reporting;

import java.util.Objects;

/** A reply to an existing Word comment. The parent is the w:id in comments.xml. */
public record CommentReply(String author, String text, String parentCommentId) {
  public CommentReply {
    author = Objects.requireNonNull(author, "author").strip();
    text = Objects.requireNonNull(text, "text");
    parentCommentId = Objects.requireNonNull(parentCommentId, "parentCommentId").strip();
    if (author.isEmpty()) throw new IllegalArgumentException("author must not be blank");
    if (text.isBlank()) throw new IllegalArgumentException("text must not be blank");
    if (parentCommentId.isEmpty()) throw new IllegalArgumentException("parentCommentId must not be blank");
  }
}
