package com.example.interview.repository;

import com.example.interview.entity.DataImportLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 数据导入幂等日志 Repository（第三批 C）。
 */
@Repository
public interface DataImportLogRepository extends JpaRepository<DataImportLogEntity, Long> {

    /** 按（用户, importId）查询幂等记录，判断该导入是否已提交过 */
    Optional<DataImportLogEntity> findByUserIdAndImportId(String userId, String importId);
}
