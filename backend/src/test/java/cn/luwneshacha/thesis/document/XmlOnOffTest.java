package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class XmlOnOffTest {
  @Test
  void parsesEveryDefinedOnOffValueExactly() {
    for (String value : new String[] {"true", "1", "on"}) {
      assertEquals(true, XmlOnOff.parse(value, "test"), value);
    }
    for (String value : new String[] {"false", "0", "off"}) {
      assertEquals(false, XmlOnOff.parse(value, "test"), value);
    }
  }

  @Test
  void rejectsUnknownCaseAndEmptyValues() {
    for (String value : new String[] {"TRUE", "False", "yes", "maybe", "", " "}) {
      assertThrows(IllegalArgumentException.class, () -> XmlOnOff.parse(value, "test"), value);
    }
  }

  @Test
  void missingValueDefaultsToEnabledButExplicitEmptyValueIsInvalid() throws Exception {
    Element absent = XmlPartSupport.parse(("<w:flag xmlns:w=\"" + XmlPartSupport.W + "\"/>")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .getDocumentElement();
    Element empty = XmlPartSupport.parse(("<w:flag xmlns:w=\"" + XmlPartSupport.W + "\" w:val=\"\"/>")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .getDocumentElement();

    assertEquals(true, XmlOnOff.enabled(absent));
    assertThrows(IllegalArgumentException.class, () -> XmlOnOff.enabled(empty));
  }

  @Test
  void styleDefaultAttributeUsesItsOwnFalseDefaultAndTheSameExactValues() throws Exception {
    Element styleWithoutDefault = XmlPartSupport.parse(("<w:style xmlns:w=\"" + XmlPartSupport.W + "\"/>")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).getDocumentElement();
    Element styleDefaultOn = XmlPartSupport.parse(("<w:style xmlns:w=\"" + XmlPartSupport.W
        + "\" w:default=\"on\"/>").getBytes(java.nio.charset.StandardCharsets.UTF_8)).getDocumentElement();
    Element styleDefaultUppercase = XmlPartSupport.parse(("<w:style xmlns:w=\"" + XmlPartSupport.W
        + "\" w:default=\"TRUE\"/>").getBytes(java.nio.charset.StandardCharsets.UTF_8)).getDocumentElement();

    assertEquals(false, XmlOnOff.attribute(styleWithoutDefault, "default", false));
    assertEquals(true, XmlOnOff.attribute(styleDefaultOn, "default", false));
    assertThrows(IllegalArgumentException.class,
        () -> XmlOnOff.attribute(styleDefaultUppercase, "default", false));
  }
}
