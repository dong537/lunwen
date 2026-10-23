package cn.luwneshacha.thesis.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.docx4j.TraversalUtil;
import org.docx4j.finders.ClassFinder;
import org.docx4j.model.PropertyResolver;
import org.docx4j.model.table.TableStyleConditions;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTBorder;
import org.docx4j.wml.CTTblStylePr;
import org.docx4j.wml.STTblStyleOverrideType;
import org.docx4j.wml.Style;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.TcPr;
import org.docx4j.wml.Tr;
import org.docx4j.wml.TrPr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class TableParser {
  private static final Pattern CAPTION = Pattern.compile(
      "^[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]*(?:续表|表)(?=[0-9A-Z\\s\\p{Zs}])");
  private static final Pattern TABLE_DESCRIPTION = Pattern.compile(
      "(?:测试用例编号|用例编号|测试编号|模块名称|功能模块|模块名|测试模块)\\s*[:：]");
  private static final Pattern TEST_CASE_ID = Pattern.compile("(?i)(?:[A-Z]{1,4}[-_ ]\\d+|CASE[-_ ]?\\d+|TEST[-_ ]?\\d+)");
  /**
   * A shared unit may be written alone at the right of the table top line.
   * The label is optional in the school specification, so do not require
   * the literal “单位：” prefix when locating the paragraph in XML.
   */
  private static final Pattern PLAIN_SHARED_UNIT = Pattern.compile(
      "^(?:[（(]\\s*)?(?:mm|cm|km|μm|nm|mL|L|kg|mg|g|ms|min|h|s|Hz|kHz|mV|V|mA|A|kW|W|kPa|MPa|Pa|mol|cd|K|N|J|C|F|Ω|S|Wb|T|H|lm|lx|Bq|Gy|Sv|kat|m|℃|°C|%)(?:\\s*[）)])?$");

  public List<TableModel> parse(byte[] xml) {
    return parse(xml, null);
  }

  public List<TableModel> parse(byte[] xml, byte[] stylesXml) {
    return parse(xml, stylesXml, null);
  }

  public List<TableModel> parse(byte[] xml, byte[] stylesXml, WordprocessingMLPackage pkg) {
    try {
      var result = new ArrayList<TableModel>();
      Element styles = stylesXml == null ? null : XmlPartSupport.parse(stylesXml).getDocumentElement();
      Element root = XmlPartSupport.parse(xml).getDocumentElement();
      List<ConditionalStyleBorders> conditionalBorders = pkg == null
          ? List.of() : resolveConditionalStyleBorders(pkg, countTables(root));
      collect(root, List.of(), result, styles, conditionalBorders, pkg != null, new int[] {0});
      return List.copyOf(result);
    } catch (Exception e) { throw new IllegalArgumentException("unable to parse tables", e); }
  }

  private void collect(Element element, List<Integer> path, List<TableModel> result, Element styles,
      List<ConditionalStyleBorders> conditionalBorders, boolean packageEvidenceAvailable, int[] tableIndex) {
    if (XmlPartSupport.W.equals(element.getNamespaceURI()) && "tbl".equals(element.getLocalName())) {
      int currentTableIndex = tableIndex[0]++;
      ConditionalStyleBorders conditional = currentTableIndex < conditionalBorders.size()
          ? conditionalBorders.get(currentTableIndex) : ConditionalStyleBorders.empty();
      var caption = findCaption(element);
      boolean captionBefore = caption != null;
      if (caption == null) caption = findFollowingCaption(element);
      Element unitParagraph = findSharedUnitParagraph(element);
      String sharedUnitText = unitParagraph == null ? null : XmlPartSupport.text(unitParagraph).trim();
      boolean sharedUnitRightAligned = unitParagraph != null && isRightAligned(unitParagraph, styles);
      var rows = tableRows(element);
      var repeatingHeaderRowIndexes = new ArrayList<Integer>();
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        if (repeatsAsHeader(rows.get(rowIndex))) repeatingHeaderRowIndexes.add(rowIndex);
      }
      int columns = rows.stream().mapToInt(row -> rowCells(row).size()).max().orElse(0);
      int headerRowIndex = findHeaderRowIndex(rows, columns);
      boolean xmlNumericValuesValid = tableNumericValuesValid(element) && tableGeometryFitsInt(rows);
      var columnWidths = XmlPartSupport.children(element).stream()
          .filter(e -> XmlPartSupport.W.equals(e.getNamespaceURI()) && "tblGrid".equals(e.getLocalName()))
          .flatMap(grid -> XmlPartSupport.children(grid).stream())
          .filter(e -> XmlPartSupport.W.equals(e.getNamespaceURI()) && "gridCol".equals(e.getLocalName()))
          .map(e -> integerAttribute(e, "w"))
          .filter(java.util.Objects::nonNull).toList();
      var rowHeights = rows.stream().map(row -> direct(row, "trPr"))
          .map(TableParser::rowHeight).filter(java.util.Objects::nonNull).toList();
      boolean merged = rows.stream().flatMap(row -> rowCells(row).stream()).anyMatch(TableParser::hasMerge);
      var headerTexts = headerRowIndex < 0 ? List.<String>of()
          : rowCells(rows.get(headerRowIndex)).stream().map(XmlPartSupport::text).toList();
      var note = findFollowingNote(element);
      boolean hasTableStyle = direct(direct(element, "tblPr"), "tblStyle") != null;
      Element borders = first(element, "tblBorders");
      StyleBorders styleBorders = styles != null ? resolveTableStyleBorders(element, styles, packageEvidenceAvailable)
          : hasTableStyle ? StyleBorders.unresolved() : StyleBorders.unstyled();
      boolean styleResolved = styleBorders.resolved() && conditional.resolved()
          && !hasLegacyHorizontalMerge(element);
      boolean top = xmlNumericValuesValid && !rows.isEmpty() && borderCoversRow(element, rows.get(0), "top",
          effectiveBorderState(borders, styleBorders, "top"), conditional.row(0));
      boolean middle = xmlNumericValuesValid && headerRowIndex >= 0 && headerRowIndex + 1 < rows.size()
          && borderCoversBoundary(element, rows.get(headerRowIndex), rows.get(headerRowIndex + 1),
              effectiveBorderState(borders, styleBorders, "insideH"),
              conditional.row(headerRowIndex), conditional.row(headerRowIndex + 1));
      boolean bottom = xmlNumericValuesValid && !rows.isEmpty() && borderCoversRow(element, rows.get(rows.size() - 1), "bottom",
          effectiveBorderState(borders, styleBorders, "bottom"), conditional.row(rows.size() - 1));
      boolean vertical = xmlNumericValuesValid && hasEffectiveVerticalBorder(element, rows, borders, styleBorders, conditional);
      result.add(new TableModel(new SourceAddress("word/document.xml", path, 0, 0),
          caption == null ? null : new SourceAddress("word/document.xml", caption.path, 0, caption.text.length()),
          caption == null ? null : caption.text, rows.size(), columns, top, middle, bottom, vertical,
          captionBefore, false, merged, columnWidths, rowHeights, headerTexts,
          note == null ? null : note.text(), note == null ? null : new SourceAddress("word/document.xml", note.path(), 0, note.text().length()),
          rows.stream().flatMap(row -> rowCells(row).stream()).map(XmlPartSupport::text)
           .anyMatch(text -> text.trim().matches("同前|同左")), hasTableStyle,
           sharedUnitText, sharedUnitRightAligned, styleResolved, xmlNumericValuesValid,
           isCodeLayout(rows, columns, caption, vertical),
           // Description rows can be merged; only the header and data region determine column mapping.
           headerRowIndex >= 0 && countTables(element) == 1
               && rows.stream().skip(headerRowIndex).flatMap(row -> rowCells(row).stream())
                   .noneMatch(TableParser::hasMerge)
               && rows.stream().skip(headerRowIndex + 1L)
                   .allMatch(row -> rowCells(row).size() == headerTexts.size()
                   && direct(direct(row, "trPr"), "gridBefore") == null
                   && direct(direct(row, "trPr"), "gridAfter") == null)
               ? rows.stream().skip(headerRowIndex + 1L)
                   .map(row -> rowCells(row).stream().map(XmlPartSupport::text).toList()).toList()
               : List.of(), repeatingHeaderRowIndexes));
    }
    int index = 0;
    for (Element child : XmlPartSupport.children(element)) {
      var childPath = new ArrayList<>(path); childPath.add(index++);
      collect(child, List.copyOf(childPath), result, styles, conditionalBorders,
          packageEvidenceAvailable, tableIndex);
    }
  }

  private static int findHeaderRowIndex(List<Element> rows, int columns) {
    for (int index = 0; index < rows.size(); index++) {
      Element row = rows.get(index);
      List<Element> cells = rowCells(row);
      String text = cells.stream().map(XmlPartSupport::text).reduce("", String::concat).strip();
      if (isDescriptionRow(cells, columns, text)) continue;
      return index;
    }
    return -1;
  }

  private static boolean isDescriptionRow(List<Element> cells, int columns, String text) {
    if (text.isEmpty()) return false;
    boolean labeledDescription = TABLE_DESCRIPTION.matcher(text).find() || TEST_CASE_ID.matcher(text).find();
    boolean mergedDescription = cells.size() == 1 && cellGridSpan(cells.get(0)) >= columns;
    // A description row may use one cell per label and therefore occupy the
    // same number of cells as the real header.  The colon-backed labels are
    // structural evidence; a plain header such as "模块名称" remains a header.
    boolean perCellDescription = cells.size() > 1 && cells.stream()
        .map(XmlPartSupport::text)
        .allMatch(value -> TABLE_DESCRIPTION.matcher(value).find());
    // Some Word exports split the introductory description across two or
    // more cells. Treat it as a description only when it occupies fewer
    // columns than the actual table, so a full-width header containing a
    // label such as “模块名称” remains a real header row.
    boolean partialDescription = cells.size() > 0 && cells.size() < columns;
    return labeledDescription
        && (mergedDescription || cells.size() == 1 || partialDescription || perCellDescription);
  }

  private static int countTables(Element element) {
    int count = XmlPartSupport.W.equals(element.getNamespaceURI()) && "tbl".equals(element.getLocalName()) ? 1 : 0;
    for (Element child : XmlPartSupport.children(element)) count += countTables(child);
    return count;
  }

  private static List<Element> tableRows(Element table) {
    var rows = table.getElementsByTagNameNS(XmlPartSupport.W, "tr");
    var result = new ArrayList<Element>(rows.getLength());
    for (int index = 0; index < rows.getLength(); index++) {
      Element row = (Element) rows.item(index);
      if (owningTable(row) == table) result.add(row);
    }
    return List.copyOf(result);
  }

  private static boolean repeatsAsHeader(Element row) {
    Element marker = direct(direct(row, "trPr"), "tblHeader");
    if (marker == null) return false;
    String value = attribute(marker, "val");
    if (value == null || value.isBlank()) return true;
    return !List.of("0", "false", "off").contains(value.toLowerCase(java.util.Locale.ROOT));
  }

  private static List<Element> rowCells(Element row) {
    var cells = row.getElementsByTagNameNS(XmlPartSupport.W, "tc");
    var result = new ArrayList<Element>(cells.getLength());
    for (int index = 0; index < cells.getLength(); index++) {
      Element cell = (Element) cells.item(index);
      if (owningRow(cell) == row) result.add(cell);
    }
    return List.copyOf(result);
  }

  private static List<ConditionalStyleBorders> resolveConditionalStyleBorders(
      WordprocessingMLPackage pkg, int expectedTableCount) throws Exception {
    var finder = new ClassFinder(Tbl.class);
    new TraversalUtil(pkg.getMainDocumentPart().getContent(), finder);
    if (finder.results.size() != expectedTableCount)
      return java.util.Collections.nCopies(expectedTableCount, ConditionalStyleBorders.unresolved());
    var resolved = new ArrayList<ConditionalStyleBorders>();
    PropertyResolver propertyResolver = pkg.getMainDocumentPart().getPropertyResolver();
    for (Object tableObject : finder.results) {
      if (!(tableObject instanceof Tbl table)) {
        resolved.add(ConditionalStyleBorders.unresolved());
        continue;
      }
      try {
        Style style = propertyResolver.getEffectiveTableStyle(table.getTblPr());
        if (style == null) {
          resolved.add(table.getTblPr() == null || table.getTblPr().getTblStyle() == null
              ? ConditionalStyleBorders.empty() : ConditionalStyleBorders.unresolved());
          continue;
        }
        resolved.add(conditionalCellBorders(table, style));
      } catch (Exception error) {
        resolved.add(ConditionalStyleBorders.unresolved());
      }
    }
    return List.copyOf(resolved);
  }

  private static ConditionalStyleBorders conditionalCellBorders(Tbl table, Style style) {
    List<Tr> rows = tableRows(table);
    int columnCount = table.getTblGrid() == null ? 0 : table.getTblGrid().getGridCol().size();
    if (columnCount == 0) {
      columnCount = rows.stream().mapToInt(row -> rowGridOffset(row, true)
          + row.getContent().stream().map(org.docx4j.XmlUtils::unwrap)
              .filter(Tc.class::isInstance).map(Tc.class::cast).mapToInt(TableParser::gridSpan).sum()
          + rowGridOffset(row, false)).max().orElse(0);
    }
    var result = new ArrayList<List<Map<String, Boolean>>>();
    boolean resolved = true;
    for (CTTblStylePr conditional : style.getTblStylePr()) {
      if (conditional.getType() == STTblStyleOverrideType.WHOLE_TABLE) continue;
      if (hasBorders(conditional.getTblPr() == null ? null : conditional.getTblPr().getTblBorders()))
        resolved = false;
    }
    var look = TableStyleConditions.look(table.getTblPr());
    int rowBandSize = TableStyleConditions.rowBandSize(table.getTblPr());
    int columnBandSize = TableStyleConditions.colBandSize(table.getTblPr());
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      Tr row = rows.get(rowIndex);
      var cells = new ArrayList<Map<String, Boolean>>();
      int columnIndex = rowGridOffset(row, true);
      List<Tc> rowCells = row.getContent().stream().map(org.docx4j.XmlUtils::unwrap)
          .filter(Tc.class::isInstance).map(Tc.class::cast).toList();
      for (Tc cell : rowCells) {
        TcPr cellProperties = cell.getTcPr();
        int span = gridSpan(cell);
        var conditions = TableStyleConditions.resolve(look, rowBandSize, columnBandSize,
            rowIndex, rows.size(), columnIndex, span, columnCount,
            TableStyleConditions.rowCnf(row.getTrPr()), cellProperties == null ? null : cellProperties.getCnfStyle(), null);
        var applicable = TableStyleConditions.applicable(style, conditions);
        TcPr conditionalProperties = TableStyleConditions.conditionalTcPr(applicable);
        cells.add(borderStates(conditionalProperties == null ? null : conditionalProperties.getTcBorders()));
        columnIndex += span;
      }
      result.add(List.copyOf(cells));
    }
    return new ConditionalStyleBorders(resolved, List.copyOf(result));
  }

  private static boolean hasBorders(org.docx4j.wml.TblBorders borders) {
    return borders != null && (borders.getTop() != null || borders.getBottom() != null
        || borders.getLeft() != null || borders.getRight() != null
        || borders.getInsideH() != null || borders.getInsideV() != null);
  }

  private static int gridSpan(Tc cell) {
    if (cell.getTcPr() == null || cell.getTcPr().getGridSpan() == null
        || cell.getTcPr().getGridSpan().getVal() == null) return 1;
    Integer value = XmlInteger.exact(cell.getTcPr().getGridSpan().getVal());
    return value != null && value > 0 ? value : 1;
  }

  private static List<Tr> tableRows(Tbl table) {
    var finder = new ClassFinder(Tr.class);
    new TraversalUtil(table.getContent(), finder);
    return finder.results.stream().filter(Tr.class::isInstance).map(Tr.class::cast).toList();
  }

  private static int rowGridOffset(Tr row, boolean before) {
    TrPr properties = row.getTrPr();
    if (properties == null) return 0;
    for (Object content : properties.getCnfStyleOrDivIdOrGridBefore()) {
      Object value = org.docx4j.XmlUtils.unwrap(content);
      if (before && value instanceof org.docx4j.wml.CTTrPrBase.GridBefore offset)
        return nonNegativeForTraversal(XmlInteger.exact(offset.getVal()));
      if (!before && value instanceof org.docx4j.wml.CTTrPrBase.GridAfter offset)
        return nonNegativeForTraversal(XmlInteger.exact(offset.getVal()));
    }
    return 0;
  }

  private static Map<String, Boolean> borderStates(org.docx4j.wml.TcPrInner.TcBorders borders) {
    if (borders == null) return Map.of();
    var states = new java.util.HashMap<String, Boolean>();
    putBorderState(states, "top", borders.getTop());
    putBorderState(states, "bottom", borders.getBottom());
    putBorderState(states, "left", borders.getLeft());
    putBorderState(states, "right", borders.getRight());
    putBorderState(states, "insideH", borders.getInsideH());
    putBorderState(states, "insideV", borders.getInsideV());
    return Map.copyOf(states);
  }

  private static void putBorderState(Map<String, Boolean> states, String side, CTBorder border) {
    if (border == null) return;
    String value = border.getVal() == null ? null : border.getVal().value();
    if ("nil".equals(value) || "none".equals(value)) {
      states.put(side, false);
      return;
    }
    Integer size = XmlInteger.exact(border.getSz());
    boolean matches = "single".equals(value)
        && (border.getSz() == null || size != null && size > 0);
    states.put(side, matches);
  }


  private Caption findCaption(Element table) {
    var parent = table.getParentNode();
    if (!(parent instanceof Element parentElement)) return null;
    var siblings = XmlPartSupport.children(parentElement);
    int index = siblings.indexOf(table);
    for (int i = index - 1; i >= 0; i--) {
      Element previous = siblings.get(i);
      if (isIgnorableBetween(previous) || isSharedUnitParagraph(previous)) continue;
      if (!XmlPartSupport.W.equals(previous.getNamespaceURI()) || !"p".equals(previous.getLocalName())) return null;
      String text = XmlPartSupport.text(previous);
      return CAPTION.matcher(text).find() ? new Caption(text, pathOf(previous)) : null;
    }
    return null;
  }

  private Caption findFollowingCaption(Element table) {
    var parent = table.getParentNode();
    if (!(parent instanceof Element parentElement)) return null;
    var siblings = XmlPartSupport.children(parentElement);
    int index = siblings.indexOf(table);
    if (index < 0) return null;
    for (int i = index + 1; i < siblings.size(); i++) {
      Element next = siblings.get(i);
      if (isIgnorableBetween(next)) continue;
      if (!XmlPartSupport.W.equals(next.getNamespaceURI()) || !"p".equals(next.getLocalName())) return null;
      String text = XmlPartSupport.text(next);
      if (!CAPTION.matcher(text).find()) return null;
      // A continuation caption belongs to the table after the caption. Word can
      // split the last row of the preceding table into a separate one-row table,
      // so binding the following "续表" paragraph here creates a false table.
      if (isContinuationCaption(text)) {
        for (int j = i + 1; j < siblings.size(); j++) {
          Element afterCaption = siblings.get(j);
          if (isIgnorableBetween(afterCaption)) continue;
          return XmlPartSupport.W.equals(afterCaption.getNamespaceURI())
              && "tbl".equals(afterCaption.getLocalName()) ? null : new Caption(text, pathOf(next));
        }
      }
      return new Caption(text, pathOf(next));
    }
    return null;
  }

  private static boolean isContinuationCaption(String text) {
    return text.stripLeading().startsWith("\u7eed\u8868");
  }

  private Element findSharedUnitParagraph(Element table) {
    if (!(table.getParentNode() instanceof Element parent)) return null;
    List<Element> siblings = XmlPartSupport.children(parent);
    int index = siblings.indexOf(table);
    for (int i = index - 1; i >= 0; i--) {
      Element previous = siblings.get(i);
      if (isIgnorableBetween(previous)) continue;
      return isSharedUnitParagraph(previous) ? previous : null;
    }
    return null;
  }

  private static boolean isSharedUnitParagraph(Element element) {
    if (!XmlPartSupport.W.equals(element.getNamespaceURI()) || !"p".equals(element.getLocalName())) return false;
    String text = XmlPartSupport.text(element).trim();
    return text.matches("^单位[\\s\\p{Zs}]*[:：]?[\\s\\p{Zs}]*\\S+$")
        || PLAIN_SHARED_UNIT.matcher(text).matches();
  }

  private static boolean isCodeLayout(List<Element> rows, int columns, Caption caption, boolean vertical) {
    if (caption != null || columns != 1 || !vertical || rows.isEmpty()) return false;
    for (Element row : rows) {
      String text = rowCells(row).stream().map(XmlPartSupport::text).reduce("", String::concat).trim();
      if (text.isEmpty()) continue;
      if (text.startsWith("//") || text.startsWith("/*") || text.startsWith("@")
          || text.matches("^[{}].*")
          || text.matches("^(?:(?:public|private|protected|static|final|inline)\\s+)*(?:void|boolean|byte|short|int|long|float|double|char|String|auto)\\s+[A-Za-z_$][\\w$]*\\s*\\([^)]*\\)\\s*(?:\\{|;).*")
          || text.contains(";") && text.matches(".*[(){}=].*")) return true;
    }
    return false;
  }

  private static boolean isRightAligned(Element paragraph, Element styles) {
    return "right".equals(XmlAlignment.effective(paragraph, styles));
  }

  private static boolean isIgnorableBetween(Element element) {
    if (!XmlPartSupport.W.equals(element.getNamespaceURI())) return true;
    String local = element.getLocalName();
    if ("bookmarkStart".equals(local) || "bookmarkEnd".equals(local)
        || "proofErr".equals(local) || "permStart".equals(local) || "permEnd".equals(local)) return true;
    return "p".equals(local) && XmlPartSupport.isBlankText(XmlPartSupport.text(element));
  }

  private record Caption(String text, List<Integer> path) {}
  private record Note(String text, List<Integer> path) {}

  private Note findFollowingNote(Element table) {
    var parent = table.getParentNode();
    if (!(parent instanceof Element parentElement)) return null;
    var siblings = XmlPartSupport.children(parentElement);
    int index = siblings.indexOf(table);
    if (index < 0) return null;
    for (int i = index + 1; i < siblings.size(); i++) {
      Element next = siblings.get(i);
      if (isIgnorableBetween(next)) continue;
      if (!XmlPartSupport.W.equals(next.getNamespaceURI()) || !"p".equals(next.getLocalName())) return null;
      String text = XmlPartSupport.text(next).trim();
      return text.matches("^注\\s*\\d*[\\s\\p{Zs}]*[：:].*") ? new Note(text, pathOf(next)) : null;
    }
    return null;
  }

  private static boolean hasMerge(Element cell) {
    Element properties = direct(cell, "tcPr");
    if (properties == null) return false;
    for (Element child : XmlPartSupport.children(properties)) {
      if (!XmlPartSupport.W.equals(child.getNamespaceURI())) continue;
      if ("gridSpan".equals(child.getLocalName()) || "vMerge".equals(child.getLocalName())) return true;
    }
    return false;
  }

  private static Integer rowHeight(Element trPr) {
    if (trPr == null) return null;
    for (Element child : XmlPartSupport.children(trPr)) {
      if (XmlPartSupport.W.equals(child.getNamespaceURI()) && "trHeight".equals(child.getLocalName()))
        return integerAttribute(child, "val");
    }
    return null;
  }

  private static int nonNegativeForTraversal(Integer value) {
    return value != null && value >= 0 ? value : 0;
  }

  private static boolean tableNumericValuesValid(Element element) {
    if (element == null) return false;
    if (XmlPartSupport.W.equals(element.getNamespaceURI())) {
      String localName = element.getLocalName();
      if ("gridCol".equals(localName) && !validNonNegativeAttribute(element, "w")) return false;
      if ("trHeight".equals(localName) && !validNonNegativeAttribute(element, "val")) return false;
      if ("gridSpan".equals(localName) && !validPositiveAttribute(element, "val")) return false;
      if (("gridBefore".equals(localName) || "gridAfter".equals(localName))
          && !validNonNegativeAttribute(element, "val")) return false;
      if (List.of("top", "bottom", "left", "right", "insideH", "insideV").contains(localName)) {
        if (hasAttribute(element, "sz") && !validNonNegativeAttribute(element, "sz")) return false;
        if (hasAttribute(element, "space") && !validNonNegativeAttribute(element, "space")) return false;
      }
    }
    for (Element child : XmlPartSupport.children(element))
      if (!tableNumericValuesValid(child)) return false;
    return true;
  }

  private static boolean tableGeometryFitsInt(List<Element> rows) {
    for (Element row : rows) {
      long columns = gridOffset(row, "gridBefore") + (long) gridOffset(row, "gridAfter");
      for (Element cell : rowCells(row)) columns += cellGridSpan(cell);
      if (columns > Integer.MAX_VALUE) return false;
    }
    return true;
  }

  private static boolean validPositiveAttribute(Element element, String localName) {
    if (!hasAttribute(element, localName)) return false;
    Integer value = integerAttribute(element, localName);
    return value != null && value > 0;
  }

  private static boolean validNonNegativeAttribute(Element element, String localName) {
    if (!hasAttribute(element, localName)) return false;
    Integer value = integerAttribute(element, localName);
    return value != null && value >= 0;
  }

  private static boolean hasAttribute(Element element, String localName) {
    return element.hasAttributeNS(XmlPartSupport.W, localName) || element.hasAttribute(localName);
  }

  private static Integer integerAttribute(Element element, String localName) {
    if (element == null) return null;
    String value = element.getAttributeNS(XmlPartSupport.W, localName);
    if (value == null || value.isBlank()) value = element.getAttribute(localName);
    return XmlInteger.parse(value);
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
      for (Element child : XmlPartSupport.children(parent)) { if (child == current) break; index++; }
      reversed.add(index); current = parent;
    }
    java.util.Collections.reverse(reversed); return List.copyOf(reversed);
  }
  private static Element first(Element root, String local) {
    for (Element child : XmlPartSupport.children(root)) {
      if (!XmlPartSupport.W.equals(child.getNamespaceURI()) || !"tblPr".equals(child.getLocalName())) continue;
      for (Element property : XmlPartSupport.children(child)) {
        if (XmlPartSupport.W.equals(property.getNamespaceURI()) && local.equals(property.getLocalName())) return property;
      }
    }
    return null;
  }
  private static Boolean effectiveBorderState(Element directBorders, StyleBorders styleBorders, String side) {
    Boolean directState = borderState(directBorders, side);
    return directState != null ? directState : styleBorders.state(side);
  }

  private static StyleBorders resolveTableStyleBorders(Element table, Element styles,
      boolean packageEvidenceAvailable) {
    if (styles == null) return StyleBorders.unresolved();
    Element tableProperties = direct(table, "tblPr");
    Element tableStyle = direct(tableProperties, "tblStyle");
    String styleId = tableStyle == null ? null : attribute(tableStyle, "val");
    Element current = styleId == null ? defaultTableStyle(styles) : findTableStyle(styles, styleId);
    if (current == null) return styleId == null ? StyleBorders.unstyled() : StyleBorders.unresolved();

    var chain = new ArrayList<Element>();
    var visited = new java.util.HashSet<String>();
    while (current != null) {
      String currentId = attribute(current, "styleId");
      if (currentId == null || !visited.add(currentId)) return StyleBorders.unresolved();
      chain.add(current);
      Element basedOn = direct(current, "basedOn");
      if (basedOn == null) break;
      current = findTableStyle(styles, attribute(basedOn, "val"));
      if (current == null) return StyleBorders.unresolved();
    }

    var states = new java.util.HashMap<String, Boolean>();
    boolean resolved = true;
    java.util.Collections.reverse(chain);
    for (Element style : chain) {
      mergeBorders(direct(direct(style, "tblPr"), "tblBorders"), states);
      for (Element conditional : XmlPartSupport.children(style)) {
        if (!XmlPartSupport.W.equals(conditional.getNamespaceURI())
            || !"tblStylePr".equals(conditional.getLocalName())) continue;
        String condition = attribute(conditional, "type");
        if ("wholeTable".equals(condition)) {
          mergeBorders(direct(direct(conditional, "tblPr"), "tblBorders"), states);
          mergeBorders(direct(direct(conditional, "tcPr"), "tcBorders"), states);
        } else if (hasBorders(direct(direct(conditional, "tblPr"), "tblBorders"))
            || !packageEvidenceAvailable
                && hasBorders(direct(direct(conditional, "tcPr"), "tcBorders"))) {
          resolved = false;
        }
      }
    }
    return new StyleBorders(states, resolved);
  }

  private static Element defaultTableStyle(Element styles) {
    for (Element style : XmlPartSupport.children(styles)) {
      if (!XmlPartSupport.W.equals(style.getNamespaceURI()) || !"style".equals(style.getLocalName())
          || !"table".equals(attribute(style, "type"))) continue;
      if (XmlOnOff.attribute(style, "default", false))
        return style;
    }
    return null;
  }

  private static Element findTableStyle(Element styles, String styleId) {
    if (styleId == null) return null;
    for (Element style : XmlPartSupport.children(styles)) {
      if (XmlPartSupport.W.equals(style.getNamespaceURI()) && "style".equals(style.getLocalName())
          && "table".equals(attribute(style, "type")) && styleId.equals(attribute(style, "styleId")))
        return style;
    }
    return null;
  }

  private static void mergeBorders(Element borders, java.util.Map<String, Boolean> states) {
    if (borders == null) return;
    for (String side : List.of("top", "bottom", "left", "right", "insideH", "insideV")) {
      Boolean value = borderState(borders, side);
      if (value != null) states.put(side, value);
    }
  }

  private static boolean hasBorders(Element borders) {
    return borders != null && XmlPartSupport.children(borders).stream()
        .anyMatch(child -> XmlPartSupport.W.equals(child.getNamespaceURI())
            && List.of("top", "bottom", "left", "right", "insideH", "insideV")
                .contains(child.getLocalName()));
  }

  private static String attribute(Element element, String name) {
    if (element == null) return null;
    String value = element.getAttributeNS(XmlPartSupport.W, name);
    if (value.isBlank()) value = element.getAttribute(name);
    return value.isBlank() ? null : value;
  }

  private record StyleBorders(java.util.Map<String, Boolean> values, boolean resolved) {
    private static StyleBorders unstyled() { return new StyleBorders(java.util.Map.of(), true); }
    private static StyleBorders unresolved() { return new StyleBorders(java.util.Map.of(), false); }
    private Boolean state(String side) { return values.get(side); }
  }

  private static Boolean borderState(Element borders, String side) {
    if (borders == null) return null;
    for (Element border : XmlPartSupport.children(borders)) {
      if (!XmlPartSupport.W.equals(border.getNamespaceURI()) || !side.equals(border.getLocalName())) continue;
      String value = border.getAttributeNS(XmlPartSupport.W, "val");
      if (value.isBlank()) value = border.getAttribute("val");
      if ("nil".equals(value) || "none".equals(value)) return false;
      String size = attribute(border, "sz");
      Integer sizeValue = XmlInteger.parse(size);
      return "single".equals(value)
          && (size == null || sizeValue != null && sizeValue > 0);
    }
    return null;
  }

  private static boolean borderCoversRow(Element table, Element row, String side, Boolean tableBorder,
      List<Map<String, Boolean>> styleCellBorders) {
    List<Element> cells = rowCells(row);
    if (cells.isEmpty()) return false;
    for (int index = 0; index < cells.size(); index++) {
      Map<String, Boolean> conditional = index < styleCellBorders.size()
          ? styleCellBorders.get(index) : Map.of();
      if (!Boolean.TRUE.equals(effectiveCellBorderState(table, row, cells.get(index), side,
          tableBorder, conditional))) return false;
    }
    return true;
  }

  private static boolean borderCoversBoundary(Element table, Element upperRow, Element lowerRow,
      Boolean tableBorder, List<Map<String, Boolean>> upperStyleBorders,
      List<Map<String, Boolean>> lowerStyleBorders) {
    List<Element> upperCells = rowCells(upperRow);
    List<Element> lowerCells = rowCells(lowerRow);
    int upperIndex = 0;
    int lowerIndex = 0;
    int upperStart = gridOffset(upperRow, "gridBefore");
    int lowerStart = gridOffset(lowerRow, "gridBefore");
    boolean adjacent = false;
    while (upperIndex < upperCells.size() && lowerIndex < lowerCells.size()) {
      Element upperCell = upperCells.get(upperIndex);
      Element lowerCell = lowerCells.get(lowerIndex);
      int upperEnd = upperStart + cellGridSpan(upperCell);
      int lowerEnd = lowerStart + cellGridSpan(lowerCell);
      // Omitted grid slots have no cell edge; only intersecting spans share this boundary.
      if (Math.max(upperStart, lowerStart) < Math.min(upperEnd, lowerEnd)) {
        adjacent = true;
        Boolean upper = effectiveCellBorderState(table, upperRow, upperCell, "bottom", null,
            upperIndex < upperStyleBorders.size() ? upperStyleBorders.get(upperIndex) : Map.of());
        Boolean lower = effectiveCellBorderState(table, lowerRow, lowerCell, "top", null,
            lowerIndex < lowerStyleBorders.size() ? lowerStyleBorders.get(lowerIndex) : Map.of());
        if (upper != null || lower != null) {
          if (!Boolean.TRUE.equals(upper) && !Boolean.TRUE.equals(lower)) return false;
        } else if (!Boolean.TRUE.equals(tableBorder)) return false;
      }
      if (upperEnd <= lowerEnd) {
        upperStart = upperEnd;
        upperIndex++;
      }
      if (lowerEnd <= upperEnd) {
        lowerStart = lowerEnd;
        lowerIndex++;
      }
    }
    return adjacent;
  }

  private static boolean hasEffectiveVerticalBorder(Element table, List<Element> rows,
      Element tableBorders, StyleBorders styleBorders, ConditionalStyleBorders conditionalBorders) {
    int columns = gridColumnCount(table, rows);
    if (columns == 0)
      return Boolean.TRUE.equals(effectiveBorderState(tableBorders, styleBorders, "left"))
          || Boolean.TRUE.equals(effectiveBorderState(tableBorders, styleBorders, "right"))
          || Boolean.TRUE.equals(effectiveBorderState(tableBorders, styleBorders, "insideV"));

    Boolean tableLeft = effectiveBorderState(tableBorders, styleBorders, "left");
    Boolean tableRight = effectiveBorderState(tableBorders, styleBorders, "right");
    Boolean tableInside = effectiveBorderState(tableBorders, styleBorders, "insideV");
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      Element row = rows.get(rowIndex);
      int column = gridOffset(row, "gridBefore");
      int cellIndex = 0;
      for (Element cell : rowCells(row)) {
        int span = cellGridSpan(cell);
        Map<String, Boolean> conditional = cellIndex < conditionalBorders.row(rowIndex).size()
            ? conditionalBorders.row(rowIndex).get(cellIndex) : Map.of();
        Boolean leftBase = column == 0 ? tableLeft : tableInside;
        Boolean rightBase = column + span >= columns ? tableRight : tableInside;
        if (Boolean.TRUE.equals(effectiveCellBorderState(table, row, cell, "left", leftBase, conditional))
            || Boolean.TRUE.equals(effectiveCellBorderState(table, row, cell, "right", rightBase, conditional))
            || Boolean.TRUE.equals(effectiveCellBorderState(table, row, cell, "insideV", null, conditional)))
          return true;
        column += span;
        cellIndex++;
      }
    }
    return false;
  }

  private static boolean hasLegacyHorizontalMerge(Element table) {
    var rows = table.getElementsByTagNameNS(XmlPartSupport.W, "tr");
    for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
      Element row = (Element) rows.item(rowIndex);
      var cells = row.getElementsByTagNameNS(XmlPartSupport.W, "tc");
      for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
        Element cell = (Element) cells.item(cellIndex);
        if (owningTable(cell) != table || owningRow(cell) != row) continue;
        Element merge = direct(direct(cell, "tcPr"), "hMerge");
        if (merge != null) return true;
      }
    }
    return false;
  }

  private static Boolean effectiveCellBorderState(Element table, Element row, Element cell,
      String side, Boolean tableState, Map<String, Boolean> conditionalStates) {
    Boolean direct = cellBorderState(table, row, cell, side);
    if (direct != null) return direct;
    Boolean rowState = rowBorderState(row, side);
    if (rowState != null) return rowState;
    Boolean conditional = conditionalStates.get(side);
    return conditional != null ? conditional : tableState;
  }

  private static int gridColumnCount(Element table, List<Element> rows) {
    int declaredColumns = 0;
    Element grid = direct(table, "tblGrid");
    if (grid != null) declaredColumns = (int) XmlPartSupport.children(grid).stream()
        .filter(child -> XmlPartSupport.W.equals(child.getNamespaceURI())
            && "gridCol".equals(child.getLocalName())).count();
    int rowColumns = rows.stream().mapToInt(row -> gridOffset(row, "gridBefore")
        + XmlPartSupport.children(row).stream()
        .filter(cell -> XmlPartSupport.W.equals(cell.getNamespaceURI()) && "tc".equals(cell.getLocalName()))
        .mapToInt(TableParser::cellGridSpan).sum() + gridOffset(row, "gridAfter")).max().orElse(0);
    return Math.max(declaredColumns, rowColumns);
  }

  private static int gridOffset(Element row, String name) {
    Integer value = integerAttribute(direct(direct(row, "trPr"), name), "val");
    return nonNegativeForTraversal(value);
  }

  private static int cellGridSpan(Element cell) {
    Integer span = integerAttribute(direct(direct(cell, "tcPr"), "gridSpan"), "val");
    return span == null || span < 1 ? 1 : span;
  }

  private static Boolean rowBorderState(Element row, String side) {
    Element exception = direct(row, "tblPrEx");
    return borderState(direct(exception, "tblBorders"), side);
  }

  private static Boolean cellBorderState(Element table, Element row, Element cell, String side) {
    var nodes = cell.getElementsByTagNameNS(XmlPartSupport.W, "tcBorders");
    for (int index = 0; index < nodes.getLength(); index++) {
      Element borders = (Element) nodes.item(index);
      if (owningTable(borders) == table && owningRow(borders) == row
          && owningCell(borders) == cell) {
        Boolean state = borderState(borders, side);
        if (state != null) return state;
      }
    }
    return null;
  }

  private static Element owningTable(Element element) {
    Node current = element.getParentNode();
    while (current instanceof Element parent) {
      if (XmlPartSupport.W.equals(parent.getNamespaceURI()) && "tbl".equals(parent.getLocalName())) return parent;
      current = parent.getParentNode();
    }
    return null;
  }

  private static Element owningRow(Element element) {
    Node current = element.getParentNode();
    while (current instanceof Element parent) {
      if (XmlPartSupport.W.equals(parent.getNamespaceURI()) && "tr".equals(parent.getLocalName())) return parent;
      current = parent.getParentNode();
    }
    return null;
  }

  private static Element owningCell(Element element) {
    Node current = element.getParentNode();
    while (current instanceof Element parent) {
      if (XmlPartSupport.W.equals(parent.getNamespaceURI()) && "tc".equals(parent.getLocalName())) return parent;
      current = parent.getParentNode();
    }
    return null;
  }

  private record ConditionalStyleBorders(boolean resolved, List<List<Map<String, Boolean>>> rows) {
    private static ConditionalStyleBorders unresolved() {
      return new ConditionalStyleBorders(false, List.of());
    }

    private static ConditionalStyleBorders empty() {
      return new ConditionalStyleBorders(true, List.of());
    }

    private List<Map<String, Boolean>> row(int index) {
      return index >= 0 && index < rows.size() ? rows.get(index) : List.of();
    }

  }
}
