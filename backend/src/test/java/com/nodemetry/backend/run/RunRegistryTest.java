package com.nodemetry.backend.run;

import com.nodemetry.backend.node.NodeService;
import com.nodemetry.backend.telemetry.SensorReadingRepository;
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

    private static final long LONG_GRACE_MS = 60_000;

    @Mock
    private VirtualNodeRunRepository repository;

    @Mock
    private NodeService nodeService;

    @Mock
    private SensorReadingRepository readingRepository;

    private RunRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry(repository, nodeService, readingRepository, LONG_GRACE_MS);
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

        when(readingRepository.countByRunId("run-001")).thenReturn(2L);
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

    // Regression test: another backend instance sharing the broker and database
    // wins the insert race for some messages. This instance counted those as
    // dupe events, but the readings ARE stored — the flushed totals must report
    // the DB truth (all saved, zero duplicates), not the local event split.
    @Test
    void flushReportsDbTruthWhenAnotherInstanceSavedTheRows() {
        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 10, 0.5, 0.0));

        registry.recordSaved("run-001", 141);
        registry.recordDupe("run-001", 95);
        when(readingRepository.countByRunId("run-001")).thenReturn(236L);

        registry.flushCounters();

        verify(repository).updateCounters("run-001", 236, 0);
    }

    @Test
    void flushStillCountsTrueDuplicatesAgainstDbTruth() {
        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 10, 0.5, 0.2));

        // 236 messages processed locally, 40 of them re-sent messageIds that
        // never produced a row.
        registry.recordSaved("run-001", 196);
        registry.recordDupe("run-001", 40);
        when(readingRepository.countByRunId("run-001")).thenReturn(196L);

        registry.flushCounters();

        verify(repository).updateCounters("run-001", 196, 40);
    }

    @Test
    void endRunStampsDbReconciledTotalsAndStoresQueuedTotal() {
        when(repository.save(any(VirtualNodeRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));
        registry.recordSaved("run-001", 141);
        registry.recordDupe("run-001", 95);

        VirtualNodeRun run = new VirtualNodeRun();
        run.setRunId("run-001");
        run.setStartedAt(Instant.now());
        run.setTotalSaved(99); // stale row value — must be overwritten from the DB count
        when(repository.findByRunId("run-001")).thenReturn(Optional.of(run));
        when(readingRepository.countByRunId("run-001")).thenReturn(236L);

        VirtualNodeRun ended = registry.endRun("run-001", new EndRunRequest(236L, 1_700_000_000_000L));

        assertThat(ended.getTotalReceived()).isEqualTo(236);
        assertThat(ended.getTotalSaved()).isEqualTo(236);
        assertThat(ended.getDuplicatesSkipped()).isZero();
        assertThat(ended.getEndedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(registry.getCurrentRunId()).isNull();
    }

    @Test
    void lateBatchesKeepSettlingDuringGraceWindowAfterEnd() {
        when(repository.save(any(VirtualNodeRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VirtualNodeRun run = new VirtualNodeRun();
        run.setRunId("run-001");
        run.setStartedAt(Instant.now());
        when(repository.findByRunId("run-001")).thenReturn(Optional.of(run));

        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));
        registry.recordSaved("run-001", 141);

        // Only part of the run has been ingested when the run ends.
        when(readingRepository.countByRunId("run-001")).thenReturn(141L, 236L);
        VirtualNodeRun ended = registry.endRun("run-001", new EndRunRequest(236L, 1_700_000_000_000L));
        assertThat(ended.getTotalSaved()).isEqualTo(141);

        // The remaining batches drain after the end; the grace-window flush
        // settles them into the run-history row.
        registry.recordSaved("run-001", 95);
        registry.flushCounters();

        verify(repository).updateCounters("run-001", 236, 0);
    }

    @Test
    void endedRunIsEvictedAfterGraceAndLateEventsAreIgnored() {
        registry = new RunRegistry(repository, nodeService, readingRepository, 0);

        when(repository.save(any(VirtualNodeRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VirtualNodeRun run = new VirtualNodeRun();
        run.setRunId("run-001");
        run.setStartedAt(Instant.now());
        when(repository.findByRunId("run-001")).thenReturn(Optional.of(run));

        registry.startRun(new StartRunRequest("run-001", "Load test", 1, 3, 0.5, 0.1));
        registry.recordSaved("run-001");
        when(readingRepository.countByRunId("run-001")).thenReturn(1L);

        VirtualNodeRun ended = registry.endRun("run-001", new EndRunRequest(1L, 1_700_000_000_000L));
        assertThat(ended.getTotalSaved()).isEqualTo(1);
        assertThat(ended.getDuplicatesSkipped()).isZero();

        // Grace of zero: the next flush reconciles once more and evicts the run.
        registry.flushCounters();
        verify(repository, never()).updateCounters(anyString(), anyLong(), anyLong());

        // Once evicted, the entry cannot be recreated by a late message for the
        // same runId and the row is no longer touched.
        registry.recordSaved("run-001");
        registry.flushCounters();
        verify(repository, never()).updateCounters(anyString(), anyLong(), anyLong());
    }
}
