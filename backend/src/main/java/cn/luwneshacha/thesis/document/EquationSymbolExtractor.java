package cn.luwneshacha.thesis.document;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

public final class EquationSymbolExtractor {
  private static final Pattern IDENTIFIER = Pattern.compile("\\p{L}[\\p{L}\\p{N}]*");
  private static final Set<String> FUNCTION_NAMES = Set.of(
      "sin", "cos", "tan", "cot", "sec", "csc", "asin", "acos", "atan", "sinh", "cosh", "tanh",
      "arcsin", "arccos", "arctan", "log", "log10", "ln", "lg", "exp", "sqrt", "max", "min",
      "lim", "det", "diag", "rank", "gcd", "lcm", "mod", "arg", "Re", "Im", "tr", "sign",
      "floor", "ceil", "round", "mean", "median", "std", "Var", "Cov");

  private EquationSymbolExtractor() {}

  static Set<String> extract(Element formula) {
    var variables = new LinkedHashSet<String>();
    var runs = formula.getElementsByTagNameNS(XmlPartSupport.M, "r");
    for (int runIndex = 0; runIndex < runs.getLength(); runIndex++) {
      Element run = (Element) runs.item(runIndex);
      if (insideFunctionName(run)) continue;
      var textNodes = run.getElementsByTagNameNS(XmlPartSupport.M, "t");
      var runText = new StringBuilder();
      for (int textIndex = 0; textIndex < textNodes.getLength(); textIndex++)
        runText.append(textNodes.item(textIndex).getTextContent());
      addIdentifiers(runText.toString(), variables);
    }
    return Set.copyOf(variables);
  }

  static java.util.List<EquationVariable> extractStructured(Element formula) {
    var result = new java.util.ArrayList<EquationVariable>();
    collectStructured(formula, result);
    return java.util.List.copyOf(result);
  }

  private static void collectStructured(Element element, java.util.List<EquationVariable> result) {
    if (!XmlPartSupport.M.equals(element.getNamespaceURI())) return;
    String name = element.getLocalName();
    if ("sSub".equals(name) || "sSup".equals(name) || "sSubSup".equals(name)) {
      String base = mathText(directMathChild(element, "e"));
      String sub = mathText(directMathChild(element, "sub"));
      String sup = mathText(directMathChild(element, "sup"));
      if (isIdentifier(base)) {
        String identifier = base + (sub.isBlank() ? "" : "_" + sub)
            + (sup.isBlank() ? "" : "^" + sup);
        result.add(new EquationVariable(base, sub, sup, identifier, new SourceAddress(
            "word/document.xml", pathOf(element), 0, identifier.length()),
            explicitItalic(directMathChild(element, "e"))));
      } else {
        // A scripted expression such as (x+y)^n is not one variable token.
        // Keep its operands instead of silently discarding the entire subtree.
        for (Element child : XmlPartSupport.children(element)) collectStructured(child, result);
      }
      return;
    }
    if ("r".equals(name)) {
      if (insideFunctionName(element)) return;
      String value = mathText(element);
      Matcher identifiers = IDENTIFIER.matcher(value);
      while (identifiers.find()) {
        String identifier = identifiers.group();
        if (!FUNCTION_NAMES.contains(identifier))
          result.add(new EquationVariable(identifier, "", "", identifier, new SourceAddress(
              "word/document.xml", pathOf(element), identifiers.start(), identifiers.end()),
              explicitItalic(element)));
      }
      return;
    }
    for (Element child : XmlPartSupport.children(element)) collectStructured(child, result);
  }

  private static Element directMathChild(Element parent, String localName) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (XmlPartSupport.M.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    return null;
  }

  private static String mathText(Element element) {
    if (element == null) return "";
    var nodes = element.getElementsByTagNameNS(XmlPartSupport.M, "t");
    var result = new StringBuilder();
    for (int index = 0; index < nodes.getLength(); index++) result.append(nodes.item(index).getTextContent());
    return result.toString();
  }

  private static boolean isIdentifier(String value) {
    return value != null && IDENTIFIER.matcher(value).matches();
  }

  private static Boolean explicitItalic(Element element) {
    if (element == null) return null;
    var runs = new java.util.ArrayList<Element>();
    if ("r".equals(element.getLocalName()) && XmlPartSupport.M.equals(element.getNamespaceURI()))
      runs.add(element);
    var descendants = element.getElementsByTagNameNS(XmlPartSupport.M, "r");
    for (int index = 0; index < descendants.getLength(); index++) runs.add((Element) descendants.item(index));
    if (runs.isEmpty()) return null;
    Boolean result = null;
    for (Element run : runs) {
      Element properties = directMathChild(run, "rPr");
      Element style = directMathChild(properties, "sty");
      if (style == null) return null;
      String value = style.getAttributeNS(XmlPartSupport.M, "val");
      Boolean italic = "i".equals(value) ? Boolean.TRUE : "p".equals(value) ? Boolean.FALSE : null;
      if (italic == null || (result != null && !result.equals(italic))) return null;
      result = italic;
    }
    return result;
  }

  private static java.util.List<Integer> pathOf(Element element) {
    var reversed = new java.util.ArrayList<Integer>();
    org.w3c.dom.Node current = element;
    while (current.getParentNode() instanceof Element parent) {
      int index = 0;
      for (Element child : XmlPartSupport.children(parent)) {
        if (child == current) break;
        index++;
      }
      reversed.add(index);
      current = parent;
    }
    java.util.Collections.reverse(reversed);
    return java.util.List.copyOf(reversed);
  }

  private static boolean insideFunctionName(Element element) {
    for (var parent = element.getParentNode(); parent instanceof Element ancestor; parent = parent.getParentNode())
      if (XmlPartSupport.M.equals(ancestor.getNamespaceURI()) && "fName".equals(ancestor.getLocalName())) return true;
    return false;
  }

  public static void addIdentifiers(String text, Set<String> target) {
    Matcher matcher = IDENTIFIER.matcher(text);
    while (matcher.find()) {
      String identifier = matcher.group();
      if (!FUNCTION_NAMES.contains(identifier)) target.add(identifier);
    }
  }
}
