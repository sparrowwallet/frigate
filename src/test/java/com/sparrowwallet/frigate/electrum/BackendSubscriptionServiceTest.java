package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BackendSubscriptionServiceTest {
    private static final String SCRIPT_HASH = "8b01df4e368ea28f8dc0423bcf7a4923e3a12d307c875e47a0cfbf90b5c39161";
    private static final String OTHER_SCRIPT_HASH = "f1".repeat(32);
    private static final String STATUS = "a1".repeat(32);

    private final JsonRpcServer jsonRpcServer = new JsonRpcServer();
    private final ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
    private final List<List<String>> delivered = new ArrayList<>();
    private final BackendSubscriptionService service = new BackendSubscriptionService(subscriptions, (scriptHash, status) -> delivered.add(Arrays.asList(scriptHash, status)));

    @Test
    public void subscribedNotificationIsRecordedAndDelivered() {
        subscriptions.subscribe(SCRIPT_HASH);

        handle(notification(SCRIPT_HASH, "\"" + STATUS + "\""));

        assertEquals(List.of(Arrays.asList(SCRIPT_HASH, STATUS)), delivered);
        assertEquals(STATUS, subscriptions.getStatus(SCRIPT_HASH));
    }

    @Test
    public void nullStatusNotificationIsDelivered() {
        subscriptions.subscribe(SCRIPT_HASH);
        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS);

        handle(notification(SCRIPT_HASH, "null"));

        assertEquals(List.of(Arrays.asList(SCRIPT_HASH, null)), delivered);
        assertTrue(subscriptions.isSubscribed(SCRIPT_HASH));
        assertNull(subscriptions.getStatus(SCRIPT_HASH));
    }

    @Test
    public void unsubscribedNotificationIsDropped() {
        subscriptions.subscribe(SCRIPT_HASH);

        handle(notification(OTHER_SCRIPT_HASH, "\"" + STATUS + "\""));

        assertTrue(delivered.isEmpty());
        assertFalse(subscriptions.isSubscribed(OTHER_SCRIPT_HASH));
    }

    private void handle(String json) {
        jsonRpcServer.handle(json, service);
    }

    private static String notification(String scriptHash, String statusJson) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"blockchain.scripthash.subscribe\",\"params\":[\"" + scriptHash + "\"," + statusJson + "]}";
    }
}
