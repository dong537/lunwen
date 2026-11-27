package cn.luwneshacha.platform.account;

import jakarta.persistence.*;

@Entity @Table(name = "major_catalog", uniqueConstraints = @UniqueConstraint(columnNames = {"college_code", "name"}))
public class MajorCatalog {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "college_code", nullable = false, length = 32, updatable = false) private String collegeCode;
  @Column(nullable = false, length = 64) private String name;
  @Column(name = "is_active", nullable = false) private boolean active = true;
  protected MajorCatalog() {}
  public MajorCatalog(String collegeCode, String name) { this.collegeCode = collegeCode; this.name = name; }
  public Long getId() { return id; }
  public String getCollegeCode() { return collegeCode; }
  public String getName() { return name; }
  public boolean isActive() { return active; }
  public void rename(String name) { this.name = name; }
  public void setActive(boolean active) { this.active = active; }
}
