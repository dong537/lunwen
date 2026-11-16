package cn.luwneshacha.platform.importing;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.platform.account.Role;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ExcelUserFileTest {
  private final ExcelUserFile files = new ExcelUserFile();

  @Test void templatesHaveSchoolCollegeMajorAndStudentGradeColumns() throws Exception {
    assertHeaders(files.template(Role.STUDENT), "学号", "姓名", "学校", "学院", "专业", "年级", "指导教师工号");
    assertHeaders(files.template(Role.TEACHER), "工号", "姓名", "学校", "学院", "专业");
    assertHeaders(files.teacherStudentTemplate(), "学号", "姓名", "学校", "学院", "专业", "年级");
  }

  @Test void readsEveryValueFromTheMatchingTemplateColumn() throws Exception {
    var row = files.read(workbook(new String[] {"学号", "姓名", "学校", "学院", "专业", "年级", "指导教师工号"},
        new String[] {"student01", "学生", "山东石油化工学院", "信息科学与工程学院", "计算机", "2023", "teacher01"}),
        Role.STUDENT).getFirst();
    assertEquals("山东石油化工学院", row.school());
    assertEquals("信息科学与工程学院", row.college());
    assertEquals("计算机", row.major());
    assertEquals("2023", row.grade());
    assertEquals("teacher01", row.teacherUsername());
    var teacherRow = files.readTeacherStudents(workbook(new String[] {"学号", "姓名", "学校", "学院", "专业", "年级"},
        new String[] {"student02", "学生乙", "山东石油化工学院", "信息科学与工程学院", "计算机", "2024"})).getFirst();
    assertEquals("2024", teacherRow.grade());
    assertEquals("", teacherRow.teacherUsername());
  }

  private static void assertHeaders(byte[] bytes, String... expected) throws Exception {
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      var header = book.getSheetAt(0).getRow(0);
      assertEquals(expected.length, header.getLastCellNum());
      for (int i = 0; i < expected.length; i++) assertEquals(expected[i], header.getCell(i).getStringCellValue());
    }
  }

  private static MockMultipartFile workbook(String[] columns, String[] values) throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("账号");
      var header = sheet.createRow(0);
      for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
      var row = sheet.createRow(1);
      for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
      book.write(output);
      return new MockMultipartFile("file", "accounts.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
  }
}
