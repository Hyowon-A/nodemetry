package com.nodemetry.backend.run;

import com.nodemetry.backend.telemetry.SensorReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhysicalNodeRunRegistryTest {

    private static final long LONG_EVICT_MS = 60_000;
    private static final List<String> PREFIXES = List.of("vnode-", "mock-");

    @Mock
    private PhysicalNodeRunRepository repository;

    @Mock
    private SensorReadingRepository readingRepository;

    private PhysicalNodeRunRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PhysicalNodeRunRegistry(repository, readingRepository, PREFIXES, LONG_EVICT_MS);
    }

    @Test
    void virtualNodesAndBlankIdsAreIgnored() {
        assertThat(registry.isPhysicalNode("kitchen-03")).isTrue();
        // test-node traffic counts as physical by default; only configured
        // prefixes are excluded.
        assertThat(registry.isPhysicalNode("test-node-001")).isTrue();
        assertThat(registry.isPhysicalNode("vnode-0001")).isFalse();
        assertThat(registry.isPhysicalNode("mock-01")).isFalse();
        assertThat(registry.isPhysicalNode(" ")).isFalse();
        assertThat(registry.isPhysicalNode(null)).isFalse();

        registry.recordBatch("run-001", "vnode-0001", 5, 1, 0.5);
        registry.recordBatch("run-001", "mock-01", 5, 1, 0.5);
        registry.recordBatch(null, "kitchen-03", 5, 1, 0.5);
        registry.flushCounters();

        verifyNoInteractions(repository, readingRepository);
    }

    // First sight of a (run, node) pair has no row yet: the idempotent update
    // hits nothing and the flush inserts instead. The flushed totals must be
    // the DB truth, not the local event split (another instance may have won
    // the insert race for messages this instance counted as dupes).
    @Test
    void firstFlushInsertsRowWithDbReconciledTotals() {
        registry.recordBatch("run-001", "kitchen-03", 141, 95, 0.5);
        when(readingRepository.countByNodeIdAndRunId("kitchen-03", "run-001")).thenReturn(236L);
        when(repository.updateCounters(eq("run-001"), eq("kitchen-03"),
                anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(0);

        registry.flushCounters();

        ArgumentCaptor<PhysicalNodeRun> captor = ArgumentCaptor.forClass(PhysicalNodeRun.class);
        verify(repository).save(captor.capture());
        PhysicalNodeRun row = captor.getValue();
        assertThat(row.getRunId()).isEqualTo("run-001");
        assertThat(row.getNodeId()).isEqualTo("kitchen-03");
        assertThat(row.getMessagesReceived()).isEqualTo(236);
        assertThat(row.getMessagesSaved()).isEqualTo(236);
        assertThat(row.getDuplicatesSkipped()).isZero();
        assertThat(row.getAvgProcessingMs()).isEqualTo(0.5);
        assertThat(row.getFirstMessageAt()).isNotNull();
        assertThat(row.getLastMessageAt()).isNotNull();
    }

    @Test
    void flushUpdatesExistingRowAndSkipsWhenCountsAreUnchanged() {
        registry.recordBatch("run-001", "kitchen-03", 196, 40, 1.5);
        when(readingRepository.countByNodeIdAndRunId("kitchen-03", "run-001")).thenReturn(196L);
        when(repository.updateCounters(eq("run-001"), eq("kitchen-03"),
                eq(236L), eq(196L), eq(40L), eq(1.5), any())).thenReturn(1);

        registry.flushCounters();
        verify(repository, never()).save(any());

        // A second flush with no new counts writes nothing further.
        registry.flushCounters();
        verify(repository, times(1)).updateCounters(any(), any(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    // Physical runIds have no end event, so counters are dropped after an idle
    // window once flushed clean; a later batch for the same pair simply
    // re-creates them and resumes reconciling against the same row.
    @Test
    void idleEntriesAreEvictedOnceFlushedClean() {
        registry = new PhysicalNodeRunRegistry(repository, readingRepository, PREFIXES, 0);

        registry.recordBatch("run-001", "kitchen-03", 1, 0, 0.5);
        when(readingRepository.countByNodeIdAndRunId("kitchen-03", "run-001")).thenReturn(1L);
        when(repository.updateCounters(eq("run-001"), eq("kitchen-03"),
                anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(1);

        registry.flushCounters(); // writes; kept because the counts just changed
        registry.flushCounters(); // clean and idle past the zero grace: evicted
        registry.flushCounters(); // nothing left to reconcile

        verify(readingRepository, times(2)).countByNodeIdAndRunId("kitchen-03", "run-001");
        verify(repository, times(1)).updateCounters(any(), any(), anyLong(), anyLong(), anyLong(), any(), any());
    }
}
