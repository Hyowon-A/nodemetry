package com.nodemetry.backend.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    Optional<SensorReading> findTopByNodeIdOrderByReceivedAtDesc(String nodeId);

    List<SensorReading> findTop100ByNodeIdOrderByReceivedAtDesc(String nodeId);

    List<SensorReading> findByNodeIdAndRunIdOrderByReceivedAtDesc(String nodeId, String runId);

    @Query("""
            select r.runId
            from SensorReading r
            where r.nodeId = :nodeId and r.runId is not null
            group by r.runId
            order by max(r.receivedAt) desc
            """)
    List<String> findRunIdsByNodeIdOrderByLatestReadingDesc(@Param("nodeId") String nodeId);
}
