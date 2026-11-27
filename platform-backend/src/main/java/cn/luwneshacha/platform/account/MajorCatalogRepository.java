package cn.luwneshacha.platform.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MajorCatalogRepository extends JpaRepository<MajorCatalog, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select major from MajorCatalog major where major.id = :id")
  Optional<MajorCatalog> findByIdForUpdate(@Param("id") long id);
  List<MajorCatalog> findByCollegeCodeOrderByNameAsc(String collegeCode);
  List<MajorCatalog> findByCollegeCodeAndActiveTrueOrderByNameAsc(String collegeCode);
  Optional<MajorCatalog> findByCollegeCodeAndName(String collegeCode, String name);
  boolean existsByCollegeCodeAndNameAndActiveTrue(String collegeCode, String name);
}
