package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class EquationParser {
  private static final Pattern NUMBER = Pattern.compile(
      "(?<![\\p{L}\\d（(])(?<open>[（(])?\\s*(?<number>(?:\\d+-\\d+)|(?:[A-Z]\\d+))\\s*(?<close>[）)])?(?![\\p{L}\\d）)])");
  private static final Pattern COMPLETE_MATH_NUMBER = Pattern.compile(
      "^(?<open>[（(])?\\s*(?<number>(?:\\d+-\\d+)|(?:[A-Z]\\d+))\\s*(?<close>[）)])?$");
  private static final Pattern VARIABLE_EXPLANATION = Pattern.compile("^\\s*(?:式中|其中)\\s*[：:].*");
  private static final Pattern TABLE_CAPTION = Pattern.compile(
      "^[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]*(?:续表|表)"
          + "[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]*(?:\\d+-\\d+|[A-Z]\\d+)");
  private record TableNumber(String number, SourceAddress source, boolean formulaCentered,
      boolean rightAligned, boolean borderless) {}

  public List<EquationModel> parse(byte[] xml) {
    return parse(xml, null);
  }

  public List<EquationModel> parse(byte[] xml, byte[] stylesXml) {
    return parse(xml, stylesXml, java.util.Map.of());
  }

  public List<EquationModel> parse(byte[] xml, byte[] stylesXml, java.util.Map<String, String> themeFonts) {
    try {
      Element stylesRoot = stylesXml == null ? null : XmlPartSupport.parse(stylesXml).getDocumentElement();
      var result = new ArrayList<EquationModel>();
      Element documentRoot = XmlPartSupport.parse(xml).getDocumentElement();
      collect(documentRoot, List.of(), null, result, stylesRoot, themeFonts, documentRoot);
      return List.copyOf(result);
    } catch (Exception e) { throw new IllegalArgumentException("unable to parse equations", e); }
  }

  private void collect(Element element, List<Integer> path, String paragraphText, List<EquationModel> result,
      Element stylesRoot, java.util.Map<String, String> themeFonts, Element documentRoot) {
    String currentText = "p".equals(element.getLocalName()) && XmlPartSupport.W.equals(element.getNamespaceURI())
        ? XmlPartSupport.text(element) : paragraphText;
    List<Integer> paragraphPath = "p".equals(element.getLocalName()) && XmlPartSupport.W.equals(element.getNamespaceURI())
        ? path : null;
    if (paragraphPath == null) paragraphPath = findParagraphPath(element, path);
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMathPara".equals(element.getLocalName())) {
      String summary = mathText(element);
      if (!isVariableExplanation(summary) && !isVariableExplanationParagraph(currentText)) {
        List<Element> rows = numberedArrayRows(element, stylesRoot, themeFonts);
        if (rows.size() > 1) {
          for (Element row : rows)
            result.add(model(row, paragraphPath, "OMML", formulaRowText(row), currentText,
                numberIndex(element, paragraphPath, currentText), stylesRoot, themeFonts, documentRoot, null));
        } else {
          result.add(model(element, paragraphPath, "OMML", summary, currentText,
              numberIndex(element, paragraphPath, currentText), stylesRoot, themeFonts, documentRoot, null));
        }
      }
    } else if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMath".equals(element.getLocalName())
        && !(element.getParentNode() instanceof Element parent
            && XmlPartSupport.M.equals(parent.getNamespaceURI()) && "oMathPara".equals(parent.getLocalName()))) {
      String summary = mathText(element);
      if (!isVariableExplanation(summary) && !isVariableExplanationParagraph(currentText))
        result.add(model(element, paragraphPath, "OMML", summary, currentText,
            numberIndex(element, paragraphPath, currentText), stylesRoot, themeFonts, documentRoot, null));
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "object".equals(element.getLocalName())) {
      Element ole = firstDescendant(element, "OLEObject");
      String progId = ole == null ? "" : ole.getAttribute("ProgID");
      if (progId.equalsIgnoreCase("Equation.3") || progId.toLowerCase(java.util.Locale.ROOT).contains("mathtype")) {
        result.add(model(element, paragraphPath, "OLE", "嵌入公式对象", currentText,
            numberIndex(element, paragraphPath, currentText), stylesRoot, themeFonts, documentRoot, progId));
      }
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "drawing".equals(element.getLocalName())
        && embeddedEquationProgId(element) != null) {
      result.add(model(element, paragraphPath, "OLE", "宓屽叆鍏紡瀵硅薄", currentText,
          numberIndex(element, paragraphPath, currentText), stylesRoot, themeFonts, documentRoot,
          embeddedEquationProgId(element)));
      return;
    } else if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "drawing".equals(element.getLocalName())
        && hasPicture(element) && ((isStandalone(currentText) && NUMBER.matcher(currentText == null ? "" : currentText).find())
            || findSiblingCellNumber(element) != null)) {
        result.add(model(element, paragraphPath, "PICTURE", "图片公式（内部结构不可解析）", currentText,
          numberIndex(element, paragraphPath, currentText), stylesRoot, themeFonts, documentRoot, null));
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path); childPath.add(index++);
      collect(child, List.copyOf(childPath), currentText, result, stylesRoot, themeFonts, documentRoot);
    }
  }

  private EquationModel model(Element equation, List<Integer> path, String type, String summary,
      String paragraphText, int numberIndex, Element stylesRoot, java.util.Map<String, String> themeFonts,
      Element documentRoot, String objectProgId) {
    EquationNumberEvidence numberEvidence = "OMML".equals(type)
        ? ommlNumberEvidence(equation, stylesRoot, themeFonts) : null;
    TableNumber tableNumber = findSiblingCellNumber(equation);
    Matcher matcher = NUMBER.matcher(paragraphText == null ? "" : paragraphText);
    String number = null;
    SourceAddress numberSource = null;
    boolean standalone = isStandalone(paragraphText);
    if (standalone) {
      for (int index = 0; matcher.find(); index++) {
        if (index == numberIndex) {
          number = matcher.group("number");
          // `path` is the containing paragraph path supplied by collect().
          numberSource = new SourceAddress("word/document.xml", path,
              matcher.start(), matcher.end());
          break;
        }
      }
      if (number == null) {
        if (tableNumber != null) {
          number = tableNumber.number();
          numberSource = tableNumber.source();
        }
      }
    }
    if (standalone && numberEvidence != null) {
      number = numberEvidence.text().replaceAll("^[（(]|[）)]$", "");
      // Keep the exact math-run location.  Previously this was discarded and
      // callers had to fall back to the formula object when creating an
      // anchor, which is especially wrong for an eqArr number in a separate
      // math run.
      numberSource = numberEvidence.source();
    }
    Boolean numberRightAligned;
    if (tableNumber != null) numberRightAligned = tableNumber.rightAligned();
    else if (numberEvidence != null) numberRightAligned = null;
    else numberRightAligned = numberRightAligned(equation, paragraphText, numberIndex, stylesRoot, documentRoot);
    Boolean ommlFormulaCentered = null;
    Boolean ommlNumberRightAligned = null;
    if (numberEvidence != null) {
      Element paragraph = ancestor(equation, "p");
      String alignment = paragraphAlignment(paragraph);
      ommlFormulaCentered = alignment.isBlank() ? null : "center".equals(alignment);
      ommlNumberRightAligned = ommlNumberRightAligned(equation);
      // Expose the same structural evidence on the model's convenience field.
      // Consumers that do not inspect layoutEvidence must not see a false
      // unknown merely because the number lives inside an OMML eqArr row.
      numberRightAligned = ommlNumberRightAligned;
    }
    EquationLayoutEvidence layoutEvidence = tableNumber != null
        ? new EquationLayoutEvidence("BORDERLESS_TABLE", tableNumber.formulaCentered(), numberRightAligned,
            tableNumber.borderless(), tableNumber.source())
        : numberEvidence != null
            ? new EquationLayoutEvidence("OMML_NUMBER_ARRAY", ommlFormulaCentered, ommlNumberRightAligned,
                false, numberEvidence.source())
            : new EquationLayoutEvidence("TAB_STOP", null, numberRightAligned, false, numberSource);
    EquationObjectEvidence objectEvidence = "OMML".equals(type)
        ? EquationObjectEvidence.omml() : EquationObjectEvidence.external(type, objectProgId);
    String variableExplanation = "OMML".equals(type) ? followingVariableExplanation(equation) : null;
    if (!"OMML".equals(type)) return new EquationModel(visibleSource(equation, path),
        type, summary, number, standalone, true, Set.of(), Set.of(), null, numberSource, null,
        Set.of(), false, null, numberRightAligned, layoutEvidence, objectEvidence, List.of());
    List<Integer> sourcePath = "e".equals(equation.getLocalName()) && XmlPartSupport.M.equals(equation.getNamespaceURI())
        ? pathOf(equation) : path;
    return new EquationModel(visibleSource(equation, sourcePath), type, summary,
        number, standalone, false, nodeTypes(equation), fonts(equation), italicVariables(equation), numberSource,
        variableExplanation, EquationSymbolExtractor.extract(equation), true, numberEvidence, numberRightAligned,
        layoutEvidence, objectEvidence, EquationSymbolExtractor.extractStructured(equation));
  }

  private List<Element> numberedArrayRows(Element equationParagraph, Element stylesRoot,
      java.util.Map<String, String> themeFonts) {
    List<Element> maths = XmlPartSupport.children(equationParagraph).stream()
        .filter(child -> XmlPartSupport.M.equals(child.getNamespaceURI()) && "oMath".equals(child.getLocalName()))
        .toList();
    if (maths.size() != 1) return List.of();
    List<Element> content = XmlPartSupport.children(maths.get(0));
    if (content.size() != 1 || !XmlPartSupport.M.equals(content.get(0).getNamespaceURI())
        || !"eqArr".equals(content.get(0).getLocalName())) return List.of();
    List<Element> rows = XmlPartSupport.children(content.get(0)).stream()
        .filter(child -> XmlPartSupport.M.equals(child.getNamespaceURI()) && "e".equals(child.getLocalName()))
        .toList();
    // A derivation sharing one number is one formula, regardless of its line count.
    return rows.stream().allMatch(row -> equationNumberEvidenceInRow(row, row, stylesRoot, themeFonts) != null)
        ? rows : List.of();
  }

  private static String formulaRowText(Element row) {
    List<Element> runs = XmlPartSupport.children(row).stream()
        .filter(child -> XmlPartSupport.M.equals(child.getNamespaceURI()))
        .toList();
    int end = runs.size();
    if (end >= 2 && "#".equals(mathRunText(runs.get(end - 2)).strip())) end -= 2;
    var result = new StringBuilder();
    for (int index = 0; index < end; index++) {
      Element child = runs.get(index);
      String text = "r".equals(child.getLocalName()) ? mathRunText(child) : mathText(child);
      if (!result.isEmpty() && !text.isEmpty() && isIdentifierPart(result.codePointBefore(result.length()))
          && isIdentifierPart(text.codePointAt(0))) result.append(' ');
      result.append(text);
    }
    return result.toString();
  }

  private Boolean numberRightAligned(Element equation, String paragraphText, int numberIndex,
      Element stylesRoot, Element documentRoot) {
    Element paragraph = ancestor(equation, "p");
    if (paragraph == null || numberIndex < 0) return null;
    Integer pageWidth = sectionTextWidth(paragraph, documentRoot);
    if (pageWidth == null) return null;
    java.util.Map<Integer, String> stops = effectiveTabStops(paragraph, stylesRoot);
    if (stops.isEmpty()) return null;
    boolean exactRightStop = stops.entrySet().stream().anyMatch(entry -> entry.getValue().equals("right")
        && entry.getKey().equals(pageWidth));
    return exactRightStop && hasTabBetweenEquationAndNumber(paragraph, equation, paragraphText, numberIndex);
  }

  private static boolean hasTabBetweenEquationAndNumber(Element paragraph, Element equation,
      String paragraphText, int numberIndex) {
    Matcher numbers = NUMBER.matcher(paragraphText == null ? "" : paragraphText);
    int ordinal = 0;
    while (numbers.find() && ordinal < numberIndex) ordinal++;
    if (ordinal != numberIndex || numbers.start() < 0) return false;
    Element numberText = textElementAt(paragraph, numbers.start());
    if (numberText == null) return false;
    var tabs = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "tab");
    for (int index = 0; index < tabs.getLength(); index++) {
      Element tab = (Element) tabs.item(index);
      if (isBefore(equation, tab) && isBefore(tab, numberText)) return true;
    }
    return false;
  }

  private static Element textElementAt(Element paragraph, int offset) {
    var textNodes = paragraph.getElementsByTagNameNS(XmlPartSupport.W, "t");
    int cursor = 0;
    for (int index = 0; index < textNodes.getLength(); index++) {
      Element text = (Element) textNodes.item(index);
      int end = cursor + text.getTextContent().length();
      if (offset >= cursor && offset < end) return text;
      cursor = end;
    }
    return null;
  }

  private static boolean isBefore(Node first, Node second) {
    return (first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING) != 0;
  }

  private static Integer sectionTextWidth(Element paragraph, Element documentRoot) {
    Element body = firstDescendantByLocalName(documentRoot, "body");
    if (body == null) return null;
    Element section = null;
    var paragraphs = body.getElementsByTagNameNS(XmlPartSupport.W, "p");
    boolean found = false;
    for (int index = 0; index < paragraphs.getLength(); index++) {
      Element candidate = (Element) paragraphs.item(index);
      if (!found && candidate == paragraph) found = true;
      if (found) {
        Element pPr = directElement(candidate, XmlPartSupport.W, "pPr");
        section = directElement(pPr, XmlPartSupport.W, "sectPr");
        if (section != null) break;
      }
    }
    if (section == null) section = directElement(body, XmlPartSupport.W, "sectPr");
    if (section == null) {
      var sections = body.getElementsByTagNameNS(XmlPartSupport.W, "sectPr");
      if (sections.getLength() > 0) section = (Element) sections.item(sections.getLength() - 1);
    }
    if (section == null) {
      var sections = documentRoot.getElementsByTagNameNS("*", "sectPr");
      if (sections.getLength() > 0) section = (Element) sections.item(sections.getLength() - 1);
    }
    Element page = directElement(section, XmlPartSupport.W, "pgSz");
    Element margins = directElement(section, XmlPartSupport.W, "pgMar");
    if (page == null && section != null && section.getElementsByTagNameNS("*", "pgSz").getLength() > 0)
      page = (Element) section.getElementsByTagNameNS("*", "pgSz").item(0);
    if (margins == null && section != null && section.getElementsByTagNameNS("*", "pgMar").getLength() > 0)
      margins = (Element) section.getElementsByTagNameNS("*", "pgMar").item(0);
    Integer width = parseInteger(attribute(page, "w"));
    Integer left = parseInteger(attribute(margins, "left"));
    Integer right = parseInteger(attribute(margins, "right"));
    Integer gutter = parseInteger(attribute(margins, "gutter"));
    return width == null || left == null || right == null ? null : width - left - right - (gutter == null ? 0 : gutter);
  }

  private static String attribute(Element element, String name) {
    if (element == null) return null;
    String value = element.getAttributeNS(XmlPartSupport.W, name);
    return value.isBlank() ? element.getAttribute("w:" + name) : value;
  }

  private static java.util.Map<Integer, String> effectiveTabStops(Element paragraph, Element stylesRoot) {
    var stops = new java.util.LinkedHashMap<Integer, String>();
    if (stylesRoot != null) {
      Element defaults = directElement(stylesRoot, XmlPartSupport.W, "docDefaults");
      Element pPr = directElement(directElement(defaults, XmlPartSupport.W, "pPrDefault"), XmlPartSupport.W, "pPr");
      applyTabStops(stops, directElement(pPr, XmlPartSupport.W, "tabs"));
      String styleId = paragraphStyleId(stylesRoot, paragraph);
      var chain = new ArrayList<Element>();
      var visited = new java.util.HashSet<String>();
      while (styleId != null && !styleId.isBlank() && visited.add(styleId)) {
        Element style = findStyle(stylesRoot, styleId);
        if (style == null || !"paragraph".equals(style.getAttributeNS(XmlPartSupport.W, "type"))) break;
        chain.add(style);
        Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
        styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
      }
      java.util.Collections.reverse(chain);
      for (Element style : chain) applyTabStops(stops,
          directElement(directElement(style, XmlPartSupport.W, "pPr"), XmlPartSupport.W, "tabs"));
    }
    Element directPPr = directElement(paragraph, XmlPartSupport.W, "pPr");
    applyTabStops(stops, directElement(directPPr, XmlPartSupport.W, "tabs"));
    return stops;
  }

  private static void applyTabStops(java.util.Map<Integer, String> stops, Element tabs) {
    if (tabs == null) return;
    for (Element tab : XmlPartSupport.children(tabs)) {
      if (!XmlPartSupport.W.equals(tab.getNamespaceURI()) || !"tab".equals(tab.getLocalName())) continue;
      Integer position = parseInteger(tab.getAttributeNS(XmlPartSupport.W, "pos"));
      if (position == null) continue;
      String value = tab.getAttributeNS(XmlPartSupport.W, "val");
      if ("clear".equals(value)) stops.remove(position);
      else stops.put(position, value.isBlank() ? "left" : value);
    }
  }

  private EquationNumberEvidence ommlNumberEvidence(Element equation, Element stylesRoot,
      java.util.Map<String, String> themeFonts) {
    if ("e".equals(equation.getLocalName()) && XmlPartSupport.M.equals(equation.getNamespaceURI()))
      return equationNumberEvidenceInRow(equation, equation, stylesRoot, themeFonts);
    var arrays = equation.getElementsByTagNameNS(XmlPartSupport.M, "eqArr");
    for (int arrayIndex = arrays.getLength() - 1; arrayIndex >= 0; arrayIndex--) {
      Element array = (Element) arrays.item(arrayIndex);
      List<Element> rows = XmlPartSupport.children(array).stream()
          .filter(child -> XmlPartSupport.M.equals(child.getNamespaceURI())
              && "e".equals(child.getLocalName())).toList();
      if (rows.isEmpty()) continue;
      EquationNumberEvidence evidence = equationNumberEvidenceInRow(rows.get(rows.size() - 1),
          equation, stylesRoot, themeFonts);
      if (evidence != null) return evidence;
    }
    return null;
  }

  /**
   * OMML equation arrays use a math run containing {@code #} immediately
   * before the number as the explicit alignment marker.  It is structural
   * evidence, unlike ordinary spaces in a paragraph, so it is safe to use it
   * for the "number at the right" check.
   */
  private Boolean ommlNumberRightAligned(Element equation) {
    Element row = "e".equals(equation.getLocalName())
        && XmlPartSupport.M.equals(equation.getNamespaceURI()) ? equation : null;
    if (row == null) {
      var arrays = equation.getElementsByTagNameNS(XmlPartSupport.M, "eqArr");
      for (int arrayIndex = arrays.getLength() - 1; arrayIndex >= 0 && row == null; arrayIndex--) {
        Element array = (Element) arrays.item(arrayIndex);
        List<Element> rows = XmlPartSupport.children(array).stream()
            .filter(child -> XmlPartSupport.M.equals(child.getNamespaceURI())
                && "e".equals(child.getLocalName())).toList();
        if (!rows.isEmpty()) row = rows.get(rows.size() - 1);
      }
    }
    if (row == null) return null;
    List<Element> runs = XmlPartSupport.children(row).stream()
        .filter(child -> XmlPartSupport.M.equals(child.getNamespaceURI())
            && "r".equals(child.getLocalName())).toList();
    if (runs.size() < 2) return null;
    return mathNumberSuffix(row) != null;
  }

  private EquationNumberEvidence equationNumberEvidenceInRow(Element row, Element equation,
      Element stylesRoot, java.util.Map<String, String> themeFonts) {
    MathNumberSuffix number = mathNumberSuffix(row);
    return number == null ? null : typographyEvidence(number.firstRun(), equation,
        number.text(), stylesRoot, themeFonts);
  }

  private record MathNumberSuffix(Element firstRun, String text) {}

  private static MathNumberSuffix mathNumberSuffix(Element row) {
    List<Element> runs = XmlPartSupport.children(row).stream()
        .filter(child -> XmlPartSupport.M.equals(child.getNamespaceURI())
            && "r".equals(child.getLocalName())).toList();
    if (runs.size() < 2) return null;
    int markerIndex = -1;
    for (int index = runs.size() - 2; index >= 0; index--) {
      if ("#".equals(mathRunText(runs.get(index)).strip())) {
        markerIndex = index;
        break;
      }
    }
    if (markerIndex < 0 || markerIndex == runs.size() - 1) return null;
    // Word may split the displayed number over multiple math runs when its
    // formatting changes.  Validate the complete trailing sequence instead
    // of assuming that the last run alone contains "(3-1)".
    StringBuilder text = new StringBuilder();
    for (int index = markerIndex + 1; index < runs.size(); index++)
      text.append(mathRunText(runs.get(index)));
    Matcher matcher = COMPLETE_MATH_NUMBER.matcher(text.toString().strip());
    if (!matcher.matches()) return null;
    Element firstNumberRun = runs.get(markerIndex + 1);
    return new MathNumberSuffix(firstNumberRun, text.toString().strip());
  }

  private EquationNumberEvidence typographyEvidence(Element numberRun, Element equation,
      String text, Element stylesRoot, java.util.Map<String, String> themeFonts) {
    Matcher matcher = COMPLETE_MATH_NUMBER.matcher(text);
    boolean parenthesized = text.startsWith("(") && text.endsWith(")")
        || text.startsWith("（") && text.endsWith("）");
    Element runProperties = directElement(numberRun, XmlPartSupport.W, "rPr");
    Element paragraph = ancestor(equation, "p");
    Element fonts = effectiveProperty(stylesRoot, runProperties, paragraph, "rFonts");
    Element size = effectiveProperty(stylesRoot, runProperties, paragraph, "sz");
    String fontName = effectiveFontName(fonts, themeFonts);
    Integer fontSize = parseInteger(size == null ? null : size.getAttributeNS(XmlPartSupport.W, "val"));
    // SourceAddress offsets belong to the containing paragraph, including
    // preceding Word and math runs. The readable formula summary may insert
    // separators, so neither its length nor a run-local zero is a coordinate.
    SourceAddress runSource = visibleSource(numberRun, pathOf(numberRun));
    String rawNumberRun = mathRunText(numberRun);
    int start = runSource.start() + rawNumberRun.length() - rawNumberRun.stripLeading().length();
    return new EquationNumberEvidence(text, parenthesized || (matcher.matches() && matcher.group("open") != null), fontName, fontSize,
        new SourceAddress("word/document.xml", pathOf(numberRun), start, start + text.length()));
  }

  private record VisibleTextFragment(Element element, String text) {}

  /** Maps an XML subtree onto the visible paragraph stream used for comments. */
  private static SourceAddress visibleSource(Element target, List<Integer> sourcePath) {
    Element paragraph = ancestor(target, "p");
    Element root = paragraph == null ? target : paragraph;
    var fragments = new ArrayList<VisibleTextFragment>();
    collectVisibleText(root, root, fragments);
    int start = 0;
    int length = 0;
    for (VisibleTextFragment fragment : fragments) {
      if (within(fragment.element(), target)) length += fragment.text().length();
      else if (isBefore(fragment.element(), target)) start += fragment.text().length();
    }
    return new SourceAddress("word/document.xml", sourcePath, start, start + length);
  }

  private static void collectVisibleText(Element parent, Element paragraph,
      List<VisibleTextFragment> result) {
    for (Element child : XmlPartSupport.children(parent)) {
      if ("p".equals(child.getLocalName()) && child != paragraph) continue;
      if ("del".equals(child.getLocalName()) || "moveFrom".equals(child.getLocalName())) continue;
      if (!"r".equals(child.getLocalName())) {
        collectVisibleText(child, paragraph, result);
        continue;
      }
      String namespace = child.getNamespaceURI();
      for (Element runChild : XmlPartSupport.children(child)) {
        if (!java.util.Objects.equals(namespace, runChild.getNamespaceURI())) continue;
        String text = switch (runChild.getLocalName()) {
          case "t" -> runChild.getTextContent();
          case "tab" -> "\t";
          case "cr" -> "\n";
          case "br" -> {
            String type = runChild.getAttributeNS(namespace, "type");
            yield "page".equals(type) || "column".equals(type) ? "" : "\n";
          }
          default -> "";
        };
        if (!text.isEmpty()) result.add(new VisibleTextFragment(runChild, text));
      }
    }
  }

  private static boolean within(Element element, Element ancestor) {
    for (Node node = element; node != null; node = node.getParentNode())
      if (node == ancestor) return true;
    return false;
  }

  private static String mathRunText(Element run) {
    var nodes = run.getElementsByTagNameNS(XmlPartSupport.M, "t");
    var text = new StringBuilder();
    for (int index = 0; index < nodes.getLength(); index++) text.append(nodes.item(index).getTextContent());
    return text.toString();
  }

  private static Element firstDescendantByLocalName(Element parent, String localName) {
    var nodes = parent.getElementsByTagNameNS("*", localName);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static Element effectiveProperty(Element stylesRoot, Element directRunProperties,
      Element paragraph, String property) {
    Element direct = directElement(directRunProperties, XmlPartSupport.W, property);
    if (hasPropertyValue(direct, property)) return direct;
    if (stylesRoot != null && paragraph != null) {
      Element rStyle = directElement(directRunProperties, XmlPartSupport.W, "rStyle");
      Element value = styleProperty(stylesRoot,
          rStyle == null ? null : rStyle.getAttributeNS(XmlPartSupport.W, "val"), "character", property);
      if (hasPropertyValue(value, property)) return value;

      value = styleProperty(stylesRoot, paragraphStyleId(stylesRoot, paragraph), "paragraph", property);
      if (hasPropertyValue(value, property)) return value;

      Element defaults = directElement(stylesRoot, XmlPartSupport.W, "docDefaults");
      Element defaultRPr = directElement(directElement(defaults, XmlPartSupport.W, "rPrDefault"), XmlPartSupport.W, "rPr");
      value = directElement(defaultRPr, XmlPartSupport.W, property);
      if (hasPropertyValue(value, property)) return value;
    }
    return null;
  }

  private static boolean hasPropertyValue(Element value, String property) {
    if (value == null) return false;
    if (!"rFonts".equals(property)) return true;
    return List.of("ascii", "asciiTheme").stream()
        .anyMatch(attribute -> value.hasAttributeNS(XmlPartSupport.W, attribute));
  }

  private static Element styleProperty(Element styles, String styleId, String type, String property) {
    var visited = new java.util.HashSet<String>();
    while (styleId != null && !styleId.isBlank() && visited.add(styleId)) {
      Element style = findStyle(styles, styleId);
      if (style == null || !type.equals(style.getAttributeNS(XmlPartSupport.W, "type"))) return null;
      Element rPr = directElement(style, XmlPartSupport.W, "rPr");
      Element value = directElement(rPr, XmlPartSupport.W, property);
      if (hasPropertyValue(value, property)) return value;
      Element basedOn = directElement(style, XmlPartSupport.W, "basedOn");
      styleId = basedOn == null ? null : basedOn.getAttributeNS(XmlPartSupport.W, "val");
    }
    return null;
  }

  private static String paragraphStyleId(Element styles, Element paragraph) {
    Element pPr = directElement(paragraph, XmlPartSupport.W, "pPr");
    Element pStyle = directElement(pPr, XmlPartSupport.W, "pStyle");
    if (pStyle != null) return pStyle.getAttributeNS(XmlPartSupport.W, "val");
    var candidates = styles.getElementsByTagNameNS(XmlPartSupport.W, "style");
    for (int index = 0; index < candidates.getLength(); index++) {
      Element style = (Element) candidates.item(index);
      if ("paragraph".equals(style.getAttributeNS(XmlPartSupport.W, "type"))
          && "1".equals(style.getAttributeNS(XmlPartSupport.W, "default")))
        return style.getAttributeNS(XmlPartSupport.W, "styleId");
    }
    return null;
  }

  private static Element findStyle(Element styles, String styleId) {
    var candidates = styles.getElementsByTagNameNS(XmlPartSupport.W, "style");
    for (int index = 0; index < candidates.getLength(); index++) {
      Element candidate = (Element) candidates.item(index);
      if (styleId.equals(candidate.getAttributeNS(XmlPartSupport.W, "styleId"))) return candidate;
    }
    return null;
  }

  private static Element directElement(Element parent, String namespace, String localName) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (namespace.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    return null;
  }

  private static String effectiveFontName(Element fonts, java.util.Map<String, String> themeFonts) {
    if (fonts == null) return null;
    if (fonts.hasAttributeNS(XmlPartSupport.W, "asciiTheme"))
      return themeFonts.get(fonts.getAttributeNS(XmlPartSupport.W, "asciiTheme"));
    return emptyToNull(fonts.getAttributeNS(XmlPartSupport.W, "ascii"));
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Integer parseInteger(String value) {
    try { return value == null || value.isBlank() ? null : Integer.valueOf(value); }
    catch (NumberFormatException ignored) { return null; }
  }

  private String followingVariableExplanation(Element equation) {
    Element paragraph = ancestor(equation, "p");
    if (paragraph == null || !(paragraph.getParentNode() instanceof Element parent)) return null;
    List<Element> siblings = XmlPartSupport.children(parent);
    int index = siblings.indexOf(paragraph);
    for (int next = index + 1; next < siblings.size(); next++) {
      Element candidate = siblings.get(next);
      if (!XmlPartSupport.W.equals(candidate.getNamespaceURI())) continue;
      if (!"p".equals(candidate.getLocalName())) return null;
      String text = XmlPartSupport.text(candidate);
      String math = mathText(candidate);
      String combined = (text + math).strip();
      if (XmlPartSupport.isBlankText(combined)) continue;
      return VARIABLE_EXPLANATION.matcher(combined).matches() ? combined : null;
    }
    return null;
  }

  private TableNumber findSiblingCellNumber(Element equation) {
    Element cell = ancestor(equation, "tc");
    if (cell == null || !(cell.getParentNode() instanceof Element row)
        || !XmlPartSupport.W.equals(row.getNamespaceURI()) || !"tr".equals(row.getLocalName())
        || !(row.getParentNode() instanceof Element table)
        || !XmlPartSupport.W.equals(table.getNamespaceURI()) || !"tbl".equals(table.getLocalName())) return null;

    List<Element> rows = XmlPartSupport.children(table).stream()
        .filter(child -> XmlPartSupport.W.equals(child.getNamespaceURI()) && "tr".equals(child.getLocalName())).toList();
    List<Element> cells = XmlPartSupport.children(row).stream()
        .filter(child -> XmlPartSupport.W.equals(child.getNamespaceURI()) && "tc".equals(child.getLocalName())).toList();
    if (rows.size() != 1 || rows.get(0) != row || cells.size() != 2 || !cells.contains(cell)
        || hasCaptionBeforeTable(table)
        || !XmlPartSupport.isBlankText(XmlPartSupport.text(cell))) return null;

    Element numberCell = cells.get(0) == cell ? cells.get(1) : cells.get(0);
    String numberText = XmlPartSupport.text(numberCell).trim();
    Matcher number = NUMBER.matcher(numberText);
    if (!number.matches()) return null;

    var formulaUnits = new ArrayList<Element>();
    collectEquationUnits(cell, formulaUnits, numberText);
    if (formulaUnits.size() != 1 || formulaUnits.get(0) != equation) return null;

    Element numberParagraph = null;
    var paragraphs = numberCell.getElementsByTagNameNS(XmlPartSupport.W, "p");
    for (int index = 0; index < paragraphs.getLength(); index++) {
      Element candidate = (Element) paragraphs.item(index);
      if (!numberText.equals(XmlPartSupport.text(candidate).trim())) continue;
      if (numberParagraph != null) return null;
      numberParagraph = candidate;
    }
    if (numberParagraph == null) return null;
    boolean borderless = isBorderless(table) && isBorderless(cell) && isBorderless(numberCell);
    Element formulaParagraph = ancestor(equation, "p");
    boolean formulaCentered = "center".equals(paragraphAlignment(formulaParagraph));
    boolean rightAligned = "right".equals(paragraphAlignment(numberParagraph)) && borderless;
    return new TableNumber(number.group("number"), new SourceAddress("word/document.xml",
        pathOf(numberParagraph), 0, XmlPartSupport.text(numberParagraph).length()), formulaCentered,
        rightAligned, borderless);
  }

  private static String paragraphAlignment(Element paragraph) {
    if (paragraph == null) return "";
    Element pPr = direct(paragraph, "pPr");
    Element jc = direct(pPr, "jc");
    if (jc == null) return "";
    String value = jc.getAttributeNS(XmlPartSupport.W, "val");
    return value.isBlank() ? jc.getAttribute("w:val") : value;
  }

  private static boolean isBorderless(Element element) {
    if (element == null) return true;
    Element properties = direct(element, element.getLocalName().equals("tbl") ? "tblPr" : "tcPr");
    Element borders = direct(properties, element.getLocalName().equals("tbl") ? "tblBorders" : "tcBorders");
    if (borders == null) return true;
    for (Element border : XmlPartSupport.children(borders)) {
      if (!XmlPartSupport.W.equals(border.getNamespaceURI())) continue;
      String value = border.getAttributeNS(XmlPartSupport.W, "val");
      if (value.isBlank()) value = border.getAttribute("w:val");
      if (!value.isBlank() && !"nil".equals(value) && !"none".equals(value)) return false;
    }
    return true;
  }

  private boolean hasCaptionBeforeTable(Element table) {
    if (!(table.getParentNode() instanceof Element parent)) return false;
    List<Element> siblings = XmlPartSupport.children(parent);
    int index = siblings.indexOf(table);
    for (int previousIndex = index - 1; previousIndex >= 0; previousIndex--) {
      Element previous = siblings.get(previousIndex);
      if (!XmlPartSupport.W.equals(previous.getNamespaceURI())) continue;
      String localName = previous.getLocalName();
      if ("bookmarkStart".equals(localName) || "bookmarkEnd".equals(localName)
          || "proofErr".equals(localName) || "permStart".equals(localName) || "permEnd".equals(localName)
          || ("p".equals(localName) && XmlPartSupport.isBlankText(XmlPartSupport.text(previous)))) continue;
      if (!"p".equals(localName)) return false;
      return TABLE_CAPTION.matcher(XmlPartSupport.text(previous).trim()).find();
    }
    return false;
  }

  private static Element ancestor(Element element, String localName) {
    Node current = element;
    while (current instanceof Element parent) {
      if (XmlPartSupport.W.equals(parent.getNamespaceURI()) && localName.equals(parent.getLocalName())) return parent;
      current = parent.getParentNode();
    }
    return null;
  }

  private static Element direct(Element parent, String localName) {
    if (parent == null) return null;
    for (Element child : XmlPartSupport.children(parent))
      if (XmlPartSupport.W.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) return child;
    return null;
  }

  private static List<Integer> pathOf(Element element) {
    var reversed = new ArrayList<Integer>();
    Node current = element;
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
    return List.copyOf(reversed);
  }


  private static Set<String> nodeTypes(Element equation) {
    var result = new LinkedHashSet<String>();
    collectNodeTypes(equation, result);
    return result;
  }

  private static void collectNodeTypes(Element element, Set<String> result) {
    if (XmlPartSupport.M.equals(element.getNamespaceURI())) result.add(element.getLocalName());
    for (Element child : XmlPartSupport.children(element)) collectNodeTypes(child, result);
  }

  private static Set<String> fonts(Element equation) {
    var result = new LinkedHashSet<String>();
    var nodes = equation.getElementsByTagNameNS(XmlPartSupport.W, "rFonts");
    for (int index = 0; index < nodes.getLength(); index++) {
      Element node = (Element) nodes.item(index);
      for (String attribute : List.of("ascii", "hAnsi", "eastAsia", "cs")) {
        String value = node.getAttributeNS(XmlPartSupport.W, attribute);
        if (!value.isBlank()) result.add(value);
      }
    }
    return result;
  }

  private static Boolean italicVariables(Element equation) {
    var styles = equation.getElementsByTagNameNS(XmlPartSupport.M, "sty");
    Boolean result = null;
    for (int index = 0; index < styles.getLength(); index++) {
      String value = ((Element) styles.item(index)).getAttributeNS(XmlPartSupport.M, "val");
      if ("i".equalsIgnoreCase(value)) result = Boolean.TRUE;
      if ("p".equalsIgnoreCase(value)) result = Boolean.FALSE;
    }
    return result;
  }

  private int numberIndex(Element equation, List<Integer> paragraphPath, String paragraphText) {
    Element paragraph = equation.getOwnerDocument().getDocumentElement();
    for (int index : paragraphPath) paragraph = XmlPartSupport.children(paragraph).get(index);
    var units = new ArrayList<Element>();
    collectEquationUnits(paragraph, units, paragraphText);
    return units.indexOf(equation);
  }

  private void collectEquationUnits(Element element, List<Element> units, String paragraphText) {
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMathPara".equals(element.getLocalName())) {
      units.add(element);
      return;
    }
    if (XmlPartSupport.M.equals(element.getNamespaceURI()) && "oMath".equals(element.getLocalName())) {
      if (!(element.getParentNode() instanceof Element parent
          && XmlPartSupport.M.equals(parent.getNamespaceURI()) && "oMathPara".equals(parent.getLocalName()))) {
        units.add(element);
      }
      return;
    }
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "object".equals(element.getLocalName())) {
      Element ole = firstDescendant(element, "OLEObject");
      String progId = ole == null ? "" : ole.getAttribute("ProgID");
      if (progId.equalsIgnoreCase("Equation.3") || progId.toLowerCase(java.util.Locale.ROOT).contains("mathtype")) {
        units.add(element);
        return;
      }
    }
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "drawing".equals(element.getLocalName())
        && hasPicture(element) && NUMBER.matcher(paragraphText == null ? "" : paragraphText).find()) {
      units.add(element);
      return;
    }
    for (Element child : XmlPartSupport.children(element)) collectEquationUnits(child, units, paragraphText);
  }

  private static boolean hasPicture(Element drawing) {
    return drawing.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/picture", "pic").getLength() > 0;
  }

  /** Finds embedded equation objects regardless of legacy VML or DrawingML container. */
  private static String embeddedEquationProgId(Element drawing) {
    var nodes = drawing.getElementsByTagNameNS("*", "OLEObject");
    for (int index = 0; index < nodes.getLength(); index++) {
      Element object = (Element) nodes.item(index);
      String progId = object.getAttribute("ProgID");
      if (progId == null || progId.isBlank()) progId = object.getAttributeNS(XmlPartSupport.O, "ProgID");
      if (isEquationProgId(progId)) return progId;
    }
    String uri = drawing.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main",
        "graphicData").getLength() == 0 ? "" : ((Element) drawing
            .getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main", "graphicData")
            .item(0)).getAttribute("uri");
    return uri.toLowerCase(java.util.Locale.ROOT).contains("ole") ? "EmbeddedEquationObject" : null;
  }

  private static boolean isEquationProgId(String progId) {
    if (progId == null || progId.isBlank()) return false;
    String normalized = progId.toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("equation") || normalized.contains("mathtype")
        || normalized.contains("mathcad") || normalized.contains("wps.math");
  }

  private static boolean isStandalone(String paragraphText) {
    String text = paragraphText == null ? "" : NUMBER.matcher(paragraphText).replaceAll("").trim();
    return XmlPartSupport.isBlankText(text);
  }

  private static boolean isVariableExplanation(String summary) {
    return summary != null && VARIABLE_EXPLANATION.matcher(summary).matches();
  }

  private static boolean isVariableExplanationParagraph(String paragraphText) {
    return paragraphText != null && paragraphText.stripLeading().matches("^(?:式中|其中)\\s*[：:].*");
  }

  private static List<Integer> findParagraphPath(Element element, List<Integer> path) {
    var result = new ArrayList<Integer>(path);
    Element current = element;
    while (current != null && !(XmlPartSupport.W.equals(current.getNamespaceURI()) && "p".equals(current.getLocalName()))) {
      if (!(current.getParentNode() instanceof Element parent)) return path;
      int index = 0;
      for (Element child : XmlPartSupport.children(parent)) {
        if (child == current) break;
        index++;
      }
      if (!result.isEmpty()) result.remove(result.size() - 1);
      current = parent;
    }
    return current == null ? path : result;
  }

  private static Element firstDescendant(Element root, String localName) {
    var nodes = root.getElementsByTagNameNS("urn:schemas-microsoft-com:office:office", localName);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static String mathText(Element element) {
    var text = new StringBuilder();
    var runs = element.getElementsByTagNameNS(XmlPartSupport.M, "r");
    for (int runIndex = 0; runIndex < runs.getLength(); runIndex++) {
      Element run = (Element) runs.item(runIndex);
      var nodes = run.getElementsByTagNameNS(XmlPartSupport.M, "t");
      var token = new StringBuilder();
      for (int textIndex = 0; textIndex < nodes.getLength(); textIndex++)
        token.append(nodes.item(textIndex).getTextContent());
      if (token.isEmpty()) continue;
      if (!text.isEmpty() && isIdentifierPart(text.codePointBefore(text.length()))
          && isIdentifierPart(token.codePointAt(0))) text.append(' ');
      text.append(token);
    }
    return text.toString();
  }

  private static boolean isIdentifierPart(int codePoint) {
    return Character.isLetterOrDigit(codePoint) || Character.getType(codePoint) == Character.LETTER_NUMBER;
  }

}
