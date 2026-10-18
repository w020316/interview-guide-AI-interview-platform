package com.example.interview.service.career;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.StoryBankEntity;
import com.example.interview.repository.StoryBankRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StoryBankService#update} 单元测试（G4，2026-10-18）。
 *
 * <p>这个方法此前是**死代码**（无任何调用方），所以它的语义从未被验证过。
 * 接上路由前先把三条约定钉死：
 * <ol>
 *   <li><b>部分更新</b>：只覆盖非 null 字段 —— 否则前端只传一个字段就会把 STAR 其余层清空；</li>
 *   <li><b>空白标题不覆盖</b>：`"  "` 不能把已有标题洗成空串；</li>
 *   <li><b>不存在/非本人 → 404</b>：此前抛 BusinessException 会被映射成 503「服务不可用」，
 *       把「故事没了」误报成「服务挂了」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoryBankService.update 更新故事")
class StoryUpdateServiceTest {

    @Mock private StoryBankRepository repository;

    @InjectMocks private StoryBankService service;

    private StoryBankEntity existing() {
        return StoryBankEntity.builder()
                .id(1L).userId("alice")
                .title("重构订单模块")
                .situation("旧系统下单偶发超时")
                .task("在不影响业务的前提下优化")
                .action("拆分热点接口 + 加缓存")
                .result("P99 从 800ms 降到 120ms")
                .evidence("监控截图 + 压测报告")
                .capabilityTags("性能优化,Spring")
                .targetTrack("Java 后端")
                .build();
    }

    @Test
    @DisplayName("只传 action → 只改 action，其余字段原样保留")
    void update_partialOnlyTouchesProvidedFields() {
        StoryBankEntity entity = existing();
        when(repository.findByIdAndUserId(1L, "alice")).thenReturn(Optional.of(entity));
        when(repository.save(any(StoryBankEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        StoryBankEntity patch = new StoryBankEntity();
        patch.setAction("拆分热点接口 + 二级缓存 + 异步削峰");

        StoryBankEntity saved = service.update("alice", 1L, patch);

        assertThat(saved.getAction()).isEqualTo("拆分热点接口 + 二级缓存 + 异步削峰");
        assertThat(saved.getSituation()).isEqualTo("旧系统下单偶发超时");
        assertThat(saved.getTask()).isEqualTo("在不影响业务的前提下优化");
        assertThat(saved.getResult()).isEqualTo("P99 从 800ms 降到 120ms");
        assertThat(saved.getTitle()).isEqualTo("重构订单模块");
    }

    @Test
    @DisplayName("标题为空白串 → 不覆盖原标题（不能把标题洗成空）")
    void update_blankTitleKeepsOriginal() {
        StoryBankEntity entity = existing();
        when(repository.findByIdAndUserId(1L, "alice")).thenReturn(Optional.of(entity));
        when(repository.save(any(StoryBankEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        StoryBankEntity patch = new StoryBankEntity();
        patch.setTitle("   ");

        StoryBankEntity saved = service.update("alice", 1L, patch);

        assertThat(saved.getTitle()).isEqualTo("重构订单模块");
    }

    @Test
    @DisplayName("标题前后空格被 trim（避免列表里出现看不见的缩进）")
    void update_trimsTitle() {
        StoryBankEntity entity = existing();
        when(repository.findByIdAndUserId(1L, "alice")).thenReturn(Optional.of(entity));
        when(repository.save(any(StoryBankEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        StoryBankEntity patch = new StoryBankEntity();
        patch.setTitle("  重构订单模块（面试版）  ");

        assertThat(service.update("alice", 1L, patch).getTitle()).isEqualTo("重构订单模块（面试版）");
    }

    @Test
    @DisplayName("故事不存在或非本人 → 404（不是 503）")
    void update_notOwned_throws404() {
        when(repository.findByIdAndUserId(1L, "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("alice", 1L, new StoryBankEntity()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }
}
