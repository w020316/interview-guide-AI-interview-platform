package com.example.interview.repository;

import com.example.interview.entity.AgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {

    /**
     * 会话消息列表（正序）
     * JOIN FETCH 防止懒加载 N+1
     */
    @Query("SELECT m FROM AgentMessageEntity m JOIN FETCH m.conversation WHERE m.conversation.id = :conversationId ORDER BY m.id ASC")
    List<AgentMessageEntity> findByConversationIdOrderByIdAsc(@Param("conversationId") Long conversationId);

    /** 会话内最近 N 条消息（倒序取数后由调用方反转为正序），用于装配对话记忆窗口 */
    @Query("SELECT m FROM AgentMessageEntity m WHERE m.conversation.id = :conversationId ORDER BY m.id DESC")
    List<AgentMessageEntity> findLatestByConversationId(@Param("conversationId") Long conversationId, org.springframework.data.domain.Pageable pageable);
}
