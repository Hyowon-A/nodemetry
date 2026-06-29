package com.nodemetry.backend.node;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class NodeStatusScheduler {

    private final NodeService nodeService;

    public NodeStatusScheduler(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @Scheduled(fixedDelay = 10_000)
    public void sweepOfflineNodes() {
        int count = nodeService.markStaleNodesOffline(Duration.ofMinutes(1));
        if (count > 0) {
            System.out.println("Marked " + count + " node(s) offline due to inactivity");
        }
    }
}
