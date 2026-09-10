package com.example.interview.repository;

import com.example.interview.entity.AgentConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentConversationRepository extends JpaRepository<AgentConversationEntity, Long> {

    /** 用户的会话列表（按最近更新倒序） */
    List<AgentConversationEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
