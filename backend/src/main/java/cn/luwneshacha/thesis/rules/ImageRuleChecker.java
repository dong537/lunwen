package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.List;

final class ImageRuleChecker {
  List<CheckResult> check(ParsedDocument document, ImageModel image, RuleSet rules) {
    if (image.widthEmu() <= 0 || image.heightEmu() <= 0)
      return List.of(fail("FIG-04", "图片尺寸大于 0", image.widthEmu() + "x" + image.heightEmu(),
          "IMAGE_SIZE_MISSING", image.source()));
    if (image.hasTransformSize() && aspectDifference(image.widthEmu(), image.heightEmu(),
        image.transformWidthEmu(), image.transformHeightEmu()) > 0.01)
      return List.of(fail("FIG-04", "图片保持原始宽高比", image.widthEmu() + "x" + image.heightEmu()
          + " -> " + image.transformWidthEmu() + "x" + image.transformHeightEmu(),
          "IMAGE_ASPECT_RATIO_MISMATCH", image.source()));
    return List.of();
  }

  private static double aspectDifference(long width, long height, long transformedWidth, long transformedHeight) {
    return Math.abs((double) width / height - (double) transformedWidth / transformedHeight)
        / ((double) width / height);
  }

  private static CheckResult fail(String ruleId, String expected, String actual,
      String reason, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, expected, actual, reason, source);
  }
}
