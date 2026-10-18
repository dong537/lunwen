package cn.luwneshacha.thesis.api;

import java.nio.file.Path;

public record StoredAnnotation(String requestId, String sourceSha256, Path path) {}
