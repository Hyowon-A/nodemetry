package com.nodemetry.backend.run;

import com.nodemetry.backend.node.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunRegistryTest {

    @Mock
    private TestRunRepository repository;

    @Mock
    private NodeService nodeService;

    private RunRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry(repository, nodeService);
    }

    @Test
    void recordCountersPersistToActiveRun() {
        when(repository.save(any(TestRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.incrementTotalReceived("run-001")).thenReturn(1);
        when(repository.incrementTotalSaved("run-001")).thenReturn(1);
        when(repository.incrementDuplicatesSkipped("run-001")).thenReturn(1);

        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));

        verify(nodeService).markAllKnownNodesOffline();
        registry.recordReceived();
        registry.recordSaved();
        registry.recordDupe();

        verify(repository).incrementTotalReceived("run-001");
        verify(repository).incrementTotalSaved("run-001");
        verify(repository).incrementDuplicatesSkipped("run-001");
    }

    @Test
    void recordCountersSkipWhenNoRunIsActive() {
        registry.recordReceived();
        registry.recordSaved();
        registry.recordDupe();

        verify(repository, never()).incrementTotalReceived(anyString());
        verify(repository, never()).incrementTotalSaved(anyString());
        verify(repository, never()).incrementDuplicatesSkipped(anyString());
    }

    @Test
    void endRunKeepsFinalCounterSnapshot() {
        when(repository.save(any(TestRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.incrementTotalReceived("run-001")).thenReturn(1);
        when(repository.incrementTotalSaved("run-001")).thenReturn(1);
        when(repository.incrementDuplicatesSkipped("run-001")).thenReturn(1);

        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));
        verify(nodeService).markAllKnownNodesOffline();
        registry.recordReceived();
        registry.recordSaved();
        registry.recordDupe();

        TestRun run = new TestRun();
        run.setRunId("run-001");
        run.setStartedAt(Instant.now());
        when(repository.findByRunId("run-001")).thenReturn(Optional.of(run));

        TestRun ended = registry.endRun("run-001");

        assertThat(ended.getTotalReceived()).isEqualTo(1);
        assertThat(ended.getTotalSaved()).isEqualTo(1);
        assertThat(ended.getDuplicatesSkipped()).isEqualTo(1);
        assertThat(ended.getEndedAt()).isNotNull();
        assertThat(registry.getCurrentRunId()).isNull();
    }
}
