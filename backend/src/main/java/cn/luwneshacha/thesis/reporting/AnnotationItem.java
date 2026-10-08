package cn.luwneshacha.thesis.reporting;

import cn.luwneshacha.thesis.document.SourceAddress;

public record AnnotationItem(String ruleId, AnnotationStatus status, String reason, SourceAddress source) {}
