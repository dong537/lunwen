package cn.luwneshacha.thesis.inspection;

/** A document region that was present but not fully covered by inspection or annotation. */
public record UncheckedRegion(String partName, String region, CoverageKind kind, String reason,
    String suggestion) {
  /** Compatibility constructor for callers created before coverage categories existed. */
  public UncheckedRegion(String partName, String region, String reason) {
    this(partName, region, CoverageKind.NOT_INSPECTED, reason, "");
  }

  public UncheckedRegion(String partName, String region, CoverageKind kind, String reason) {
    this(partName, region, kind, reason, "");
  }

  public UncheckedRegion {
    if (partName == null || partName.isBlank()) throw new IllegalArgumentException("partName is required");
    if (region == null || region.isBlank()) throw new IllegalArgumentException("region is required");
    if (kind == null) throw new IllegalArgumentException("kind is required");
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
    suggestion = suggestion == null ? "" : suggestion;
  }
}
