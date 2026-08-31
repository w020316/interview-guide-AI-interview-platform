package com.example.interview.service;

import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.repository.InterviewEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 面试日历业务服务
 * - 查询/创建/更新/删除日程
 * - 所有操作带 userId 越权校验
 */
@Service
public class InterviewEventService {

    @Autowired
    private InterviewEventRepository eventRepository;

    /** 查询用户全部日程，按面试时间升序 */
    public List<InterviewEventEntity> listByUser(String userId) {
        return eventRepository.findByUserIdOrderByInterviewAtAsc(userId);
    }

    /**
     * 创建日程
     */
    @Transactional
    public InterviewEventEntity create(String userId, InterviewEventEntity event) {
        event.setId(null);
        event.setUserId(userId);
        if (event.getStatus() == null) {
            event.setStatus("UPCOMING");
        }
        return eventRepository.save(event);
    }

    /**
     * 更新日程（仅限本人），返回更新后的实体
     */
    @Transactional
    public InterviewEventEntity update(Long id, String userId, InterviewEventEntity updates) {
        InterviewEventEntity existing = getOwned(id, userId);
        if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
        if (updates.getInterviewer() != null) existing.setInterviewer(updates.getInterviewer());
        if (updates.getLocation() != null) existing.setLocation(updates.getLocation());
        if (updates.getNote() != null) existing.setNote(updates.getNote());
        if (updates.getInterviewAt() != null) existing.setInterviewAt(updates.getInterviewAt());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        return eventRepository.save(existing);
    }

    /**
     * 根据 ID 删除日程（仅限本人）
     */
    @Transactional
    public void delete(Long id, String userId) {
        InterviewEventEntity existing = getOwned(id, userId);
        eventRepository.delete(existing);
    }

    /**
     * 查询某用户指定时间范围内的日程
     */
    public List<InterviewEventEntity> listByRange(String userId,
                                                  java.time.LocalDateTime from,
                                                  java.time.LocalDateTime to) {
        return eventRepository.findByUserIdAndInterviewAtBetween(userId, from, to);
    }

    /** 查询本人拥有的日程（不存在或非本人抛参数异常） */
    private InterviewEventEntity getOwned(Long id, String userId) {
        InterviewEventEntity event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("日程不存在：" + id));
        if (!event.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作他人日程");
        }
        return event;
    }
}