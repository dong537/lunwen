package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InspectionKernelCompatibilityGateTest {
  @Test
  void localFooterAndTextBoxGapsDoNotStopUnrelatedRules() {
    var footer = gap("word/footer1.xml", "兼容内容分支（位置 [0]）");
    var textBox = gap("word/document.xml", "文本框兼容内容分支（位置 [0]）");
    var footerTextBox = gap("word/footer2.xml", "文本框兼容内容分支（位置 [0]）");

    assertFalse(InspectionKernel.requiresGlobalCompatibilityStop(List.of(footer, textBox, footerTextBox)));
    assertTrue(InspectionKernel.hasUntrustedFooter(List.of(footer, textBox, footerTextBox)));
    assertTrue(InspectionKernel.hasUntrustedFooter(List.of(footerTextBox)));
  }

  @Test
  void ordinaryBodyAndGlobalStyleAlternativesStillStopHardRules() {
    assertTrue(InspectionKernel.requiresGlobalCompatibilityStop(List.of(
        gap("word/document.xml", "兼容内容分支（位置 [0]）"))));
    assertTrue(InspectionKernel.requiresGlobalCompatibilityStop(List.of(
        gap("word/styles.xml", "兼容内容分支"))));
  }

  private static UncheckedRegion gap(String part, String region) {
    return new UncheckedRegion(part, region, CoverageKind.NOT_INSPECTED, "ambiguous");
  }
}
