package cn.luwneshacha.platform.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorCatalogRepository extends JpaRepository<MajorCatalog, Long> {
  List<MajorCatalog> findByCollegeCodeOrderByNameAsc(String collegeCode);
  List<MajorCatalog> findByCollegeCodeAndActiveTrueOrderByNameAsc(String collegeCode);
  Optional<MajorCatalog> findByCollegeCodeAndName(String collegeCode, String name);
  boolean existsByCollegeCodeAndNameAndActiveTrue(String collegeCode, String name);
}
