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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void recordCountersAccumulateInMemoryAndFlushToDb() {
        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));
        verify(nodeService).markAllKnownNodesOffline();

        registry.recordSaved("run-001");
        registry.recordSaved("run-001");
        registry.recordDupe("run-001");

        // Recording alone touches no DB; the scheduled flush mirrors the totals.
        verify(repository, never()).updateCounters(anyString(), anyLong(), anyLong());

        registry.flushCounters();
        verify(repository).updateCounters("run-001", 2, 1);

        // A second flush with no new counts writes nothing further.
        registry.flushCounters();
        verify(repository, times(1)).updateCounters(anyString(), anyLong(), anyLong());
    }

    @Test
    void recordCountersSkipWhenNoRunIsActive() {
        registry.recordSaved();
        registry.recordDupe();
        registry.flushCounters();

        verify(repository, never()).updateCounters(anyString(), anyLong(), anyLong());
    }

    @Test
    void endRunStampsCountersFromMemoryAndStoresQueuedTotal() {
        when(repository.save(any(TestRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));
        registry.recordSaved("run-001");
        registry.recordSaved("run-001");
        registry.recordDupe("run-001");

        TestRun run = new TestRun();
        run.setRunId("run-001");
        run.setStartedAt(Instant.now());
        run.setTotalSaved(99); // stale row value — must be overwritten from the in-memory adder
        when(repository.findByRunId("run-001")).thenReturn(Optional.of(run));

        TestRun ended = registry.endRun("run-001", new EndRunRequest(729L, 1_700_000_000_000L));

        assertThat(ended.getTotalReceived()).isEqualTo(729);
        assertThat(ended.getTotalSaved()).isEqualTo(2);
        assertThat(ended.getDuplicatesSkipped()).isEqualTo(1);
        assertThat(ended.getEndedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(registry.getCurrentRunId()).isNull();
    }

    @Test
    void countersFreezeWhenRunEndsAndLateReadingsAreIgnored() {
        when(repository.save(any(TestRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TestRun run = new TestRun();
        run.setRunId("run-001");
        run.setStartedAt(Instant.now());
        when(repository.findByRunId("run-001")).thenReturn(Optional.of(run));

        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));
        registry.recordSaved("run-001");
        registry.endRun("run-001", new EndRunRequest(10L, 1_700_000_000_000L));

        // Late readings may still be persisted, but they do not move the ended
        // run's history counters.
        registry.recordSaved("run-001");
        registry.recordDupe("run-001");

        registry.flushCounters();
        verify(repository, never()).updateCounters(anyString(), anyLong(), anyLong());

        // Once ended, the entry is evicted and cannot be recreated by a late
        // message for the same runId.
        registry.recordSaved("run-001");
        registry.flushCounters();
        verify(repository, never()).updateCounters(anyString(), anyLong(), anyLong());
    }
}
