package com.example.interview.service.job;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobPostingRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 招聘信息智能体服务单测（聚焦多条件筛选的 Specification 构建与 LIKE 通配符转义）
 */
class JobAgentServiceTest {

    private final JobPostingRepository repository = mock(JobPostingRepository.class);
    private final HttpJobPlatformAdapter httpAdapter = mock(HttpJobPlatformAdapter.class);
    private final JobClassifyService classifyService = mock(JobClassifyService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private JobAgentService newService() {
        return new JobAgentService(repository, List.of(), httpAdapter, classifyService, txManager);
    }

    /** 关键字中的通配符 % _ \ 必须被转义，避免破坏精确匹配语义 */
    @Test
    void searchKeywordEscapesLikeWildcards() {
        JobAgentService service = newService();
        Page<JobPostingEntity> empty = new PageImpl<>(List.of());
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        service.search("100%_\\", null, null, null, "AUTUMN", null, null, null, 0, 10);

        // 捕获生成的 Specification 并在 mock 的 Criteria 上求值为 LIKE 谓词，断言转义后的 pattern
        var specCap = org.mockito.ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specCap.capture(), any(Pageable.class));
        @SuppressWarnings("unchecked")
        Specification<JobPostingEntity> spec = specCap.getValue();

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        @SuppressWarnings("unchecked")
        Root<JobPostingEntity> root = mock(Root.class);
        @SuppressWarnings("unchecked")
        jakarta.persistence.criteria.Path<String> lowerExpr = mock(jakarta.persistence.criteria.Path.class);
        Predicate likePred = mock(Predicate.class);
        Predicate orPred = mock(Predicate.class);
        // root.get(attr) 返回统一 Expression，使 lower/like 谓语非 null 可被断言
        when(root.get(any(String.class))).thenReturn((jakarta.persistence.criteria.Path) lowerExpr);
        when(cb.lower(any(Expression.class))).thenReturn(lowerExpr);
        when(cb.like(eq(lowerExpr), anyString(), anyChar())).thenReturn(likePred);
        when(cb.or(any(Predicate[].class))).thenReturn(orPred);

        spec.toPredicate(root, query, cb);

        // 原始关键字 100%_\ 转义后各 LIKE 使用同一 pattern=%100\%\_\\%
        verify(cb, times(3)).like(eq(lowerExpr), eq("%100\\%\\_\\\\%"), eq('\\'));
    }

    /** 关键字完全省略时不应做 LIKE 匹配，仅保留 active 过滤（不调用 lower） */
    @Test
    void searchWithoutKeywordSkipsLike() {
        JobAgentService service = newService();
        Page<JobPostingEntity> empty = new PageImpl<>(List.of());
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        service.search(null, null, null, null, "AUTUMN", null, null, null, 0, 10);

        var specCap = org.mockito.ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specCap.capture(), any(Pageable.class));
        @SuppressWarnings("unchecked")
        Specification<JobPostingEntity> spec = specCap.getValue();

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        @SuppressWarnings("unchecked")
        Root<JobPostingEntity> root = mock(Root.class);
        Predicate conj = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conj);
        when(cb.isTrue(any(Expression.class))).thenReturn(mock(Predicate.class));
        when(cb.equal(any(Expression.class), any())).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        spec.toPredicate(root, query, cb);

        verify(cb, times(0)).lower(any(Expression.class));
        verify(root, never()).get("title");
    }
}