package cn.luwneshacha.platform.teacher;

import cn.luwneshacha.platform.common.ApiException;
import org.springframework.http.HttpStatus;

/** An explicit cohort selection; missing historical values must not be guessed from student IDs. */
record GradeFilter(Integer year, boolean unset) {
  static GradeFilter parse(String supplied) {
    if (supplied == null || supplied.isBlank()) return new GradeFilter(null, false);
    if ("UNSET".equals(supplied)) return new GradeFilter(null, true);
    try {
      int year = Integer.parseInt(supplied);
      if (year >= 2000 && year <= 2100) return new GradeFilter(year, false);
    } catch (NumberFormatException ignored) {
      // Converted into the same public validation error as an out-of-range year.
    }
    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "年级须为2000至2100年，或选择未填写年级");
  }
}
