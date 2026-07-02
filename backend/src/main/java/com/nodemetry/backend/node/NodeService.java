package com.nodemetry.backend.node;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class NodeService {

    record StatusEvent(String nodeId, String status) {}

    private final SensorNodeRepository nodeRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NodeService(SensorNodeRepository nodeRepository, SimpMessagingTemplate messagingTemplate) {
        this.nodeRepository = nodeRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public boolean processStatusUpdate(String nodeId, String status) {
        SensorNode node = nodeRepository
                .findByNodeId(nodeId)
                .orElse(null);

        if (node == null) {
            return false;
        }

        if ("online".equals(status)) {
            node.markOnline();
        } else if ("offline".equals(status)) {
            node.markOffline();
        } else {
            return false;
        }

        nodeRepository.save(node);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingTemplate.convertAndSend("/topic/nodes/status", new StatusEvent(nodeId, status));
            }
        });

        return true;
    }

    @Transactional
    public int markAllKnownNodesOffline() {
        return markNodesOffline(nodeRepository.findByStatusNot("offline"));
    }

    @Transactional
    public int markStaleNodesOffline(Duration threshold) {
        Instant cutoff = Instant.now().minus(threshold);
        return markNodesOffline(nodeRepository.findByLastSeenAtBeforeAndStatusNot(cutoff, "offline"));
    }

    private int markNodesOffline(List<SensorNode> nodes) {
        for (SensorNode node : nodes) {
            node.markOffline();
        }

        if (!nodes.isEmpty()) {
            nodeRepository.saveAll(nodes);
            List<String> offlineNodeIds = nodes.stream().map(SensorNode::getNodeId).toList();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String nodeId : offlineNodeIds) {
                        messagingTemplate.convertAndSend("/topic/nodes/status", new StatusEvent(nodeId, "offline"));
                    }
                }
            });
        }

        return nodes.size();
    }
}
