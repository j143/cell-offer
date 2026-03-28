package j143.github.pushgateway;

import j143.github.push.proto.ServerToClient;
import j143.github.pushgateway.model.ClientSession;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConnectionManagerTest {

    private ConnectionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ConnectionManager();
    }

    @SuppressWarnings("unchecked")
    private ClientSession session(String userId, String deviceId) {
        StreamObserver<ServerToClient> obs = mock(StreamObserver.class);
        return new ClientSession(userId, deviceId, obs, 0L);
    }

    @Test
    void registerSession_addsSession() {
        ClientSession s = session("user-1", "dev-A");
        manager.registerSession("user-1", s);

        Optional<ClientSession> found = manager.getSession("user-1");
        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo("user-1");
        assertThat(found.get().getDeviceId()).isEqualTo("dev-A");
    }

    @Test
    void registerSession_replacesExistingAndClosesOldStream() {
        ClientSession old = session("user-2", "dev-old");
        manager.registerSession("user-2", old);

        ClientSession newer = session("user-2", "dev-new");
        manager.registerSession("user-2", newer);

        // Old stream should have been completed
        verify(old.getOutbound()).onCompleted();

        // Active session is now the new one
        assertThat(manager.getSession("user-2").map(ClientSession::getDeviceId))
                .contains("dev-new");
    }

    @Test
    void getSession_unknownUser_returnsEmpty() {
        assertThat(manager.getSession("ghost")).isEmpty();
    }

    @Test
    void removeSession_removesActiveSession() {
        manager.registerSession("user-3", session("user-3", "dev-X"));
        manager.removeSession("user-3");
        assertThat(manager.getSession("user-3")).isEmpty();
    }

    @Test
    void activeSessionCount_tracksConnectedSessions() {
        assertThat(manager.activeSessionCount()).isEqualTo(0);
        manager.registerSession("user-4", session("user-4", "d1"));
        manager.registerSession("user-5", session("user-5", "d2"));
        assertThat(manager.activeSessionCount()).isEqualTo(2);
        manager.removeSession("user-4");
        assertThat(manager.activeSessionCount()).isEqualTo(1);
    }

    @Test
    void getActiveUserIds_returnsAllConnectedUsers() {
        manager.registerSession("alpha", session("alpha", "d1"));
        manager.registerSession("beta",  session("beta",  "d2"));
        assertThat(manager.getActiveUserIds()).containsExactlyInAnyOrder("alpha", "beta");
    }
}
