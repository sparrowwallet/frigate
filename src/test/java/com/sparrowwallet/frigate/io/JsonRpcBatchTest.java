package com.sparrowwallet.frigate.io;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public class JsonRpcBatchTest {
    private static String batch(int items) {
        return IntStream.range(0, items).mapToObj(i -> "{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"server.ping\",\"params\":[]}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    public void singleRequestIsNotABatch() {
        assertEquals(-1, JsonRpcBatch.countItems("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server.ping\",\"params\":[]}", 10));
    }

    @Test
    public void countsBatchItems() {
        assertEquals(0, JsonRpcBatch.countItems("[]", 10));
        assertEquals(1, JsonRpcBatch.countItems(batch(1), 10));
        assertEquals(10, JsonRpcBatch.countItems(batch(10), 10));
    }

    @Test
    public void countingStopsPastLimit() {
        assertEquals(11, JsonRpcBatch.countItems(batch(11), 10));
        assertEquals(11, JsonRpcBatch.countItems(batch(1000), 10));
    }

    @Test
    public void nestedValuesCountAsOneItem() {
        String line = "[{\"id\":1,\"params\":[[1,2,[3]],{\"a\":[4,5]}]}, [6,7], \"text\", 8, null, {\"id\":2,\"params\":{\"x\":{\"y\":[]}}}]";
        assertEquals(6, JsonRpcBatch.countItems(line, 10));
    }

    @Test
    public void leadingWhitespaceIsAllowed() {
        assertEquals(3, JsonRpcBatch.countItems(" \t" + batch(3), 10));
    }

    @Test
    public void summaryCountsSilentPaymentsSubscribes() {
        String subscribe = "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"method\":\"blockchain.silentpayments.subscribe\"},\"method\":\"blockchain.silentpayments.subscribe\"}";
        String other = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"blockchain.scripthash.subscribe\",\"params\":[\"blockchain.silentpayments.subscribe\"]}";

        assertEquals(new JsonRpcBatch.Summary(-1, 1), JsonRpcBatch.summarize(subscribe, 10));
        //the method name appearing only in params does not count
        assertEquals(new JsonRpcBatch.Summary(-1, 0), JsonRpcBatch.summarize(other, 10));
        assertEquals(new JsonRpcBatch.Summary(4, 2), JsonRpcBatch.summarize("[" + subscribe + "," + other + ",[1]," + subscribe + "]", 10));
    }

    @Test
    public void malformedJsonIsLeftToTheServer() {
        assertEquals(-1, JsonRpcBatch.countItems("[{\"id\":1,", 10));
        assertEquals(-1, JsonRpcBatch.countItems("[1,2", 10));
        assertEquals(-1, JsonRpcBatch.countItems("not json", 10));
        assertEquals(-1, JsonRpcBatch.countItems("", 10));
        assertEquals(new JsonRpcBatch.Summary(-1, 0), JsonRpcBatch.summarize("{\"method\":\"blockchain.silentpayments.subscribe\",", 10));
    }
}
