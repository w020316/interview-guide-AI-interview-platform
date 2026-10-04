package com.example.interview.repository;

import com.example.interview.entity.UserAiSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户自持 AI Key 设置仓储（第四批 · 竞品清单 #15）。
 *
 * <p>主键即 user_id：一人一套自持配置。
 */
public interface UserAiSettingRepository extends JpaRepository<UserAiSettingEntity, String> {
}
