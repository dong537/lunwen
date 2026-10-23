package cn.luwneshacha.thesis.document;

/** Representation limits of the stored equation object. */
public record EquationObjectEvidence(String type, String progId, boolean internalStructureReadable,
    String limitation) {
  public static EquationObjectEvidence omml() {
    return new EquationObjectEvidence("OMML", null, true, null);
  }

  public static EquationObjectEvidence external(String type, String progId) {
    return new EquationObjectEvidence(type, progId, false,
        "公式内部字体、变量斜体、上下标和内部结构无法从当前 DOCX XML 读取");
  }
}
