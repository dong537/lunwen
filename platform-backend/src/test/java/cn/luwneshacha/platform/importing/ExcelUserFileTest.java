package cn.luwneshacha.platform.importing;

import static org.junit.jupiter.api.Assertions.*;
import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.common.ApiException;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ExcelUserFileTest {
  private final ExcelUserFile files = new ExcelUserFile();

  @Test void exactlyTwoThousandDataRowsAreAcceptedAndTextAccountsKeepLeadingZeros() throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("账号"); var header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号"); header.createCell(1).setCellValue("姓名");
      for (int i = 1; i <= 2000; i++) {
        var row = sheet.createRow(i); row.createCell(0).setCellValue("000" + i); row.createCell(1).setCellValue("教师");
      }
      book.write(output);
      var rows = files.read(new MockMultipartFile("file", "users.xlsx", "application/octet-stream", output.toByteArray()), Role.TEACHER);
      assertEquals(2000, rows.size()); assertEquals("0001", rows.getFirst().username());
    }
  }

  @Test void formulaCellsBecomeRowErrorsAndDamagedPackagesAreRejected() throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("账号"); var header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号"); header.createCell(1).setCellValue("姓名");
      var row = sheet.createRow(1); row.createCell(0).setCellValue("teacher001"); row.createCell(1).setCellFormula("1+1");
      book.write(output);
      var rows = files.read(new MockMultipartFile("file", "users.xlsx", "application/octet-stream", output.toByteArray()), Role.TEACHER);
      assertEquals("请将公式替换为文字后再导入", rows.getFirst().error());
    }
    var error = assertThrows(ApiException.class, () -> files.read(new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", new byte[] {1, 2}), Role.TEACHER));
    assertEquals("IMPORT_INVALID_FILE", error.code());
  }
}
