package cn.luwneshacha.thesis.api;

public record ApiError(String requestId, String code, String message) {}
