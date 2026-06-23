package com.nodemetry.backend.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    boolean existsByMessageId(String messageId);

    Optional<SensorReading> findTopByNodeIdOrderByReceivedAtDesc(String nodeId);

    List<SensorReading> findTop100ByNodeIdOrderByReceivedAtDesc(String nodeId);
}