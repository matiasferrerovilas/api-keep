package api.m2.file.repository;

import api.m2.file.entity.FileActivity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileActivityRepository extends JpaRepository<FileActivity, Long> {
    List<FileActivity> findByFileIdOrderByCreatedAtDesc(Long fileId);
}
