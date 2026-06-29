package com.nodemetry.backend.node;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SensorNodeRepository extends JpaRepository<SensorNode, Long> {
    Optional<SensorNode> findByNodeId(String nodeId);
    List<SensorNode> findByLastSeenAtBeforeAndStatusNot(Instant threshold, String status);
}