package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.AutomaticNumberingModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import java.util.List;

/** Numbering policy is independent of heading recognition and bibliography parsing. */
final class AutomaticNumberingRuleModule implements RuleModule {
  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return document.automaticNumbering().stream().map(evidence -> {
      boolean unknown = evidence.kind() == AutomaticNumberingModel.Kind.UNKNOWN_LIST;
      String actual = switch (evidence.kind()) {
        case NUMBERED_LIST -> "自动列表编号";
        case SEQUENCE_FIELD -> "自动序号";
        case LISTNUM_FIELD -> "行内自动编号";
        case UNKNOWN_LIST -> "编号设置不完整，无法确认编号类型";
      };
      return new CheckResult("NUMBERING-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
          unknown ? Judgement.REVIEW : Judgement.FAIL, "使用手动输入的编号", actual,
          unknown ? "AUTOMATIC_NUMBERING_UNCONFIRMED" : "AUTOMATIC_NUMBERING_FORBIDDEN",
          evidence.source());
    }).toList();
  }
}
