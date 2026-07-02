package com.nodemetry.backend.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeServiceTest {

    @Mock
    private SensorNodeRepository nodeRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void newNodesStartOfflineUntilTelemetryOrStatusArrives() {
        SensorNode node = new SensorNode("node-001");

        assertThat(node.getStatus()).isEqualTo("offline");
        assertThat(node.getLastSeenAt()).isNull();

        node.updateHealth(87.0, -62.0, "firmware-1.0.0");

        assertThat(node.getStatus()).isEqualTo("online");
        assertThat(node.getLastSeenAt()).isNotNull();
    }

    @Test
    void markAllKnownNodesOfflineResetsOnlineNodesAndPublishesAfterCommit() {
        NodeService service = new NodeService(nodeRepository, messagingTemplate);
        SensorNode node = new SensorNode("node-001");
        node.markOnline();
        when(nodeRepository.findByStatusNot("offline")).thenReturn(List.of(node));

        TransactionSynchronizationManager.initSynchronization();
        try {
            int count = service.markAllKnownNodesOffline();

            assertThat(count).isEqualTo(1);
            assertThat(node.getStatus()).isEqualTo("offline");
            verify(nodeRepository).saveAll(List.of(node));
            verifyNoInteractions(messagingTemplate);

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<NodeService.StatusEvent> eventCaptor =
                ArgumentCaptor.forClass(NodeService.StatusEvent.class);
        verify(messagingTemplate).convertAndSend(
                org.mockito.Mockito.eq("/topic/nodes/status"),
                eventCaptor.capture()
        );
        assertThat(eventCaptor.getValue()).isEqualTo(new NodeService.StatusEvent("node-001", "offline"));
    }
}
