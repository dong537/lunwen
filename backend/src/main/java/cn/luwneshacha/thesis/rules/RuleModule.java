package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParsedDocument;
import java.util.List;

/**
 * A semantic rule module.  A module owns one document region or relation and
 * returns only findings it can prove from the parsed DOCX evidence.
 */
@FunctionalInterface
interface RuleModule {
  List<CheckResult> check(ParsedDocument document, RuleSet rules);
}
