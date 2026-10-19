package cn.luwneshacha.platform.engine;

import java.nio.file.Path;

public interface AuditEngine {
  record Result(int problemCount, Path marked, byte[] reportJson) {}
  Result inspect(Path original, Path workingDirectory) throws EngineFailure;
}
