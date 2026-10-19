package cn.luwneshacha.platform.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "sys_user")
public class User {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, unique = true, length = 32, updatable = false) private String username;
  @JsonIgnore @Column(name = "password_hash", nullable = false, length = 72) private String passwordHash;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16, updatable = false) private Role role;
  @Column(nullable = false, length = 64) private String name;
  @Column(length = 64) private String major;
  @Column(name = "teacher_id") private Long teacherId;
  @Column(name = "must_change_password", nullable = false) private boolean mustChangePassword;
  @Column(name = "is_active", nullable = false) private boolean active = true;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected User() {}
  public User(String username, String passwordHash, Role role, String name, String major, Long teacherId,
      boolean mustChangePassword) {
    this.username = username; this.passwordHash = passwordHash; this.role = role; this.name = name;
    this.major = major; this.teacherId = teacherId; this.mustChangePassword = mustChangePassword;
  }
  @PrePersist void created() { createdAt = Instant.now(); updatedAt = createdAt; }
  @PreUpdate void updated() { updatedAt = Instant.now(); }
  public Long getId() { return id; }
  public String getUsername() { return username; }
  @JsonIgnore public String getPasswordHash() { return passwordHash; }
  public Role getRole() { return role; }
  public String getName() { return name; }
  public String getMajor() { return major; }
  public Long getTeacherId() { return teacherId; }
  public boolean isActive() { return active; }
  public boolean isMustChangePassword() { return mustChangePassword; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void updateProfile(String name, String major, Long teacherId) { this.name = name; this.major = major; this.teacherId = teacherId; }
  public void setActive(boolean active) { this.active = active; }
  public void changePassword(String hash, boolean mustChange) { passwordHash = hash; mustChangePassword = mustChange; }
}
