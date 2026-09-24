package com.example.interview.config;

import com.example.interview.service.RagSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("VectorStoreCountSyncRunner 单元测试")
class VectorStoreCountSyncRunnerTest {

    @Mock
    private RagSearchService ragSearchService;

    @InjectMocks
    private VectorStoreCountSyncRunner runner;

    @Test
    @DisplayName("run: 启动时执行一次向量库计数校准，且异常不外抛")
    void run_triggersCountSync() {
        runner.run(null);
        verify(ragSearchService).syncStoredCountFromVectorStore();
    }
}
