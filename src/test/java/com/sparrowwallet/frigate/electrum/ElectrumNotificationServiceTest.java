package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.frigate.cli.FrigateCli;
import com.sparrowwallet.frigate.cli.SubscriptionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElectrumNotificationServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCRIPT_HASH = "a0".repeat(32);

    private final List<String> sent = new ArrayList<>();
    private final ElectrumNotificationService service = new JsonRpcClient(request -> {
        sent.add(request);
        return "{\"result\":{},\"error\":null,\"id\":1}";
    }).onDemand(ElectrumNotificationService.class);

    private JsonNode params() throws Exception {
        return MAPPER.readTree(sent.getLast()).path("params");
    }

    @Test
    public void scriptHashNotificationIsPositional() throws Exception {
        service.notifyScriptHash(SCRIPT_HASH, "01".repeat(32));
        assertEquals(MAPPER.readTree("[\"" + SCRIPT_HASH + "\",\"" + "01".repeat(32) + "\"]"), params());
    }

    @Test
    public void nullStatusIsSentExplicitly() throws Exception {
        service.notifyScriptHash(SCRIPT_HASH, null);
        assertEquals(MAPPER.readTree("[\"" + SCRIPT_HASH + "\",null]"), params());
    }

    @Test
    public void headersNotificationIsPositional() throws Exception {
        service.notifyHeaders(new ElectrumBlockHeader(915000, "00ff"));
        assertEquals(MAPPER.readTree("[{\"height\":915000,\"hex\":\"00ff\"}]"), params());
    }

    @Test
    public void silentPaymentsNotificationIsPositionalAndReceivedByTheCli() throws Exception {
        SilentPaymentsSubscription subscription = new SilentPaymentsSubscription("sp1test", new Integer[] {0}, 900000);
        service.notifySilentPayments(subscription, 0.5, List.of(new SilentPaymentsTxEntry(900001, "ab".repeat(32), "02" + "cd".repeat(32))));

        JsonNode params = params();
        assertTrue(params.isArray());
        assertEquals(3, params.size());
        assertEquals("sp1test", params.path(0).path("address").asText());
        assertEquals(0.5, params.path(1).asDouble());
        assertEquals(900001, params.path(2).path(0).path("height").asInt());

        //the CLI's subscription service, using the same JSON-RPC library as Sparrow, receives the positional notification
        List<SilentPaymentsNotification> received = new ArrayList<>();
        Object listener = new Object() {
            @Subscribe
            public void notification(SilentPaymentsNotification notification) {
                received.add(notification);
            }
        };
        FrigateCli.getEventBus().register(listener);
        try {
            new JsonRpcServer().handle(sent.getLast(), new SubscriptionService());
        } finally {
            FrigateCli.getEventBus().unregister(listener);
        }
        assertEquals(1, received.size());
        assertEquals("sp1test", received.getFirst().subscription().address());
        assertEquals(0.5, received.getFirst().progress());
        assertEquals("ab".repeat(32), received.getFirst().history().getFirst().tx_hash);
    }

    @Test
    public void broadcastFailureHasItsOwnErrorCode() {
        int broadcast = BroadcastFailedException.class.getAnnotation(com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError.class).code();
        int version = UnsupportedVersionException.class.getAnnotation(com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError.class).code();
        assertEquals(-32004, broadcast);
        assertNotEquals(version, broadcast);
    }
}
