package cn.luwneshacha.platform.importing;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Reads the template without evaluating formulas or opening external links. */
@Component
public final class ExcelUserFile {
  private static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final int MAX_EXPANDED_BYTES = 32 * 1024 * 1024;
  private static final int MAX_ROWS = 2000;

  public List<RowInput> read(MultipartFile file, Role role) {
    String[] columns = columns(role);
    if (file.isEmpty()) throw invalid("IMPORT_EMPTY", "请选择有内容的 Excel 文件");
    if (file.getSize() > MAX_BYTES) throw oversized();
    if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
      throw invalid("IMPORT_FILE_TYPE", "仅支持 .xlsx 格式，请使用下载的模板");
    try (var input = file.getInputStream()) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw oversized();
      validatePackage(bytes);
      try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
        if (book.getNumberOfSheets() != 1) throw invalid("IMPORT_SHEETS", "请只保留一张账号工作表");
        var sheet = book.getSheetAt(0);
        var formatter = new DataFormatter(Locale.ROOT);
        var header = sheet.getRow(0);
        if (header == null) throw invalid("IMPORT_HEADER", "缺少表头，请使用下载的模板");
        for (int i = 0; i < columns.length; i++) {
          if (!columns[i].equals(formatter.formatCellValue(header.getCell(i)).trim()))
            throw invalid("IMPORT_HEADER", "第 " + (i + 1) + " 列应为“" + columns[i] + "”，请使用下载的模板");
        }
        var result = new ArrayList<RowInput>();
        for (var row : sheet) {
          if (row.getRowNum() == 0) continue;
          String[] values = new String[columns.length];
          java.util.Arrays.fill(values, "");
          boolean nonempty = false;
          String error = null;
          for (var cell : row) {
            String value = formatter.formatCellValue(cell).trim();
            if (value.isEmpty()) continue;
            nonempty = true;
            if (cell.getCellType() == CellType.FORMULA) error = "请将公式替换为文字后再导入";
            else if (cell.getCellType() == CellType.ERROR) error = "单元格存在错误，请修正后再导入";
            else if (cell.getColumnIndex() >= columns.length) error = "有模板以外的内容，请只填写模板中的列";
            else if (value.length() > 512) error = "单元格内容过长，请检查账号、姓名和专业";
            else values[cell.getColumnIndex()] = value;
          }
          if (!nonempty) continue;
          if (result.size() == MAX_ROWS) throw invalid("IMPORT_ROW_LIMIT", "每次最多导入 2000 行，请拆分文件");
          result.add(new RowInput(row.getRowNum() + 1, values[0], values[1],
              role == Role.STUDENT ? values[2] : "", role == Role.STUDENT ? values[3] : "", error));
        }
        if (result.isEmpty()) throw invalid("IMPORT_EMPTY", "表格中没有账号数据，请在表头下填写名单");
        return List.copyOf(result);
      }
    } catch (IOException | POIXMLException | EncryptedDocumentException | IllegalArgumentException e) {
      throw invalid("IMPORT_INVALID_FILE", "文件无法读取，请使用未加密、未损坏的 .xlsx 模板文件");
    }
  }

  public byte[] template(Role role) {
    String[] columns = columns(role);
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet(role == Role.STUDENT ? "学生账号" : "教师账号");
      var text = book.createCellStyle(); text.setDataFormat(book.createDataFormat().getFormat("@"));
      var header = sheet.createRow(0);
      for (int i = 0; i < columns.length; i++) {
        sheet.setDefaultColumnStyle(i, text); sheet.setColumnWidth(i, 24 * 256);
        header.createCell(i).setCellValue(columns[i]);
      }
      sheet.createFreezePane(0, 1);
      book.write(output); return output.toByteArray();
    } catch (IOException e) { throw new IllegalStateException("Cannot generate account template", e); }
  }

  private static void validatePackage(byte[] bytes) throws IOException {
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      var names = new HashSet<String>();
      byte[] buffer = new byte[8192]; long total = 0;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (!names.add(entry.getName()) || names.size() > 1000)
          throw invalid("IMPORT_INVALID_FILE", "Excel 文件结构异常，请重新保存模板");
        int read;
        while ((read = zip.read(buffer)) != -1) {
          total += read;
          if (total > MAX_EXPANDED_BYTES) throw invalid("IMPORT_EXPANSION_LIMIT", "Excel 内容过大，请删除多余工作表、图片和空白格式后重试");
        }
      }
      if (!names.contains("[Content_Types].xml") || !names.contains("xl/workbook.xml"))
        throw invalid("IMPORT_INVALID_FILE", "文件不是有效的 .xlsx 工作簿");
    }
  }
  private static String[] columns(Role role) {
    if (role == Role.STUDENT) return new String[] {"学号", "姓名", "专业", "指导教师工号"};
    if (role == Role.TEACHER) return new String[] {"工号", "姓名"};
    throw invalid("INVALID_ROLE", "只能导入学生或教师账号");
  }
  private static ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
  private static ApiException oversized() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Excel 文件不能超过 5MB"); }
  public record RowInput(int row, String username, String name, String major, String teacherUsername, String error) {}
}
