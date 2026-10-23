package cn.luwneshacha.thesis.conversion;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversionProcessTest {
  @TempDir Path temp;

  @Test
  void capturesOutputWithoutPipeDeadlock() throws Exception {
    Path log = temp.resolve("conversion.log");
    ConversionProcess.run(command("output"), log, Duration.ofSeconds(15));
    assertTrue(Files.size(log) > 64 * 1024);
  }

  @Test
  void terminatesHungConversionWithinItsDeadline() {
    long start = System.nanoTime();
    var error = assertThrows(java.io.IOException.class,
        () -> ConversionProcess.run(command("hang"), temp.resolve("hung.log"), Duration.ofMillis(500)));
    assertTrue(error.getMessage().contains("timed out"));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(10)) < 0);
  }

  private List<String> command(String mode) {
    String binary = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    return List.of(Path.of(System.getProperty("java.home"), "bin", binary).toString(), "-cp",
        System.getProperty("java.class.path"), Child.class.getName(), mode);
  }

  public static class Child {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("hang")) Thread.sleep(30_000);
      else System.out.print("conversion-output".repeat(10_000));
    }
  }
}
