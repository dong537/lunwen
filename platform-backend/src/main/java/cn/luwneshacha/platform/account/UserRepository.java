package cn.luwneshacha.platform.account;

import java.util.Optional;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
  Optional<User> findByUsername(String username);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") Long id);
  boolean existsByUsername(String username);
  boolean existsByRole(Role role);
  long countByRoleAndActive(Role role, boolean active);
  long countByRoleAndActiveAndCollegeCode(Role role, boolean active, String collegeCode);
  long countByTeacherId(Long teacherId);
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("""
      update User u set u.major=:newName, u.updatedAt=:updatedAt
      where u.collegeCode=:collegeCode and u.major=:previousName
          and u.role in (cn.luwneshacha.platform.account.Role.STUDENT, cn.luwneshacha.platform.account.Role.TEACHER)
      """)
  int renameAssignedMajor(@Param("collegeCode") String collegeCode, @Param("previousName") String previousName,
      @Param("newName") String newName, @Param("updatedAt") Instant updatedAt);
}
