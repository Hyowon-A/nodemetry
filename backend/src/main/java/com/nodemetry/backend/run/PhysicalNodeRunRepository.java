package com.nodemetry.backend.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PhysicalNodeRunRepository extends JpaRepository<PhysicalNodeRun, Long> {
    Optional<PhysicalNodeRun> findByRunIdAndNodeId(String runId, String nodeId);
    List<PhysicalNodeRun> findByRunIdOrderByNodeIdAsc(String runId);
    List<PhysicalNodeRun> findByNodeIdOrderByLastMessageAtDesc(String nodeId);
    List<PhysicalNodeRun> findAllByOrderByLastMessageAtDesc();

    // Absolute write (not increment) so it is idempotent: PhysicalNodeRunRegistry
    // reconciles its in-memory events against sensor_readings and mirrors the
    // result here.
    @Transactional
    @Modifying
    @Query("""
            update PhysicalNodeRun p
            set p.messagesReceived = :received, p.messagesSaved = :saved,
                p.duplicatesSkipped = :dupes, p.avgProcessingMs = :avgMs,
                p.lastMessageAt = :lastMessageAt
            where p.runId = :runId and p.nodeId = :nodeId
            """)
    int updateCounters(
            @Param("runId") String runId,
            @Param("nodeId") String nodeId,
            @Param("received") long received,
            @Param("saved") long saved,
            @Param("dupes") long dupes,
            @Param("avgMs") Double avgMs,
            @Param("lastMessageAt") Instant lastMessageAt
    );
}
