package cn.luwneshacha.thesis.reporting;

/** XML-backed comment metadata. parentCommentId is null for a root comment. */
public record CommentThread(
    String commentId,
    String paraId,
    String durableId,
    String parentCommentId,
    String author,
    String text) {}
