package cn.luwneshacha.platform.account;

import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import cn.luwneshacha.platform.common.ApiException;

public enum College {
  INFORMATION_ENGINEERING("信息科学与工程学院"),
  EDUCATION_ARTS("教育与艺术学院"),
  ECONOMICS_MANAGEMENT("经济管理学院");

  public static final String SCHOOL_NAME = "山东石油化工学院";
  private final String displayName;
  College(String displayName) { this.displayName = displayName; }
  public String displayName() { return displayName; }
  public static College fromCode(String code) {
    if (code == null || code.isBlank())
      throw new ApiException(HttpStatus.BAD_REQUEST, "COLLEGE_REQUIRED", "请选择学院");
    try { return College.valueOf(code); }
    catch (IllegalArgumentException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COLLEGE", "学院选项无效");
    }
  }
  public static List<CollegeOption> options() {
    return Arrays.stream(values()).map(value -> new CollegeOption(value.name(), value.displayName)).toList();
  }
  public record CollegeOption(String code, String name) {}
}
