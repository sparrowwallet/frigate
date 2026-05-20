package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.client.Transport;
import com.github.arteam.simplejsonrpc.client.builder.AbstractBuilder;
import com.github.arteam.simplejsonrpc.client.builder.BatchRequestBuilder;
import com.github.arteam.simplejsonrpc.client.exception.JsonRpcBatchException;
import com.github.arteam.simplejsonrpc.core.domain.ErrorMessage;
import com.google.common.collect.Lists;
import com.sparrowwallet.frigate.io.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PagedBatchRequestBuilder<K, V> extends AbstractBuilder {
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int RETRY_DELAY_SECS = 1;

    private final AtomicLong counter;

    private final List<Request<K>> requests;

    /**
     * Type of request ids
     */
    private final Class<K> keysType;

    /**
     * Expected return type for all requests
     */
    private final Class<V> returnType;

    public PagedBatchRequestBuilder(Transport transport, ObjectMapper mapper, AtomicLong counter) {
        this(transport, mapper, new ArrayList<>(), null, null, counter);
    }

    public PagedBatchRequestBuilder(Transport transport, ObjectMapper mapper,
                                    List<Request<K>> requests,
                                    Class<K> keysType, Class<V> returnType,
                                    AtomicLong counter) {
        super(transport, mapper);
        this.requests = requests;
        this.keysType = keysType;
        this.returnType = returnType;
        this.counter = counter;
    }

    /**
     * Adds a new request without specifying a return type
     */
    public PagedBatchRequestBuilder<K, V> add(K id, String method, Object... params) {
        requests.add(new Request<>(id, counter == null ? null : counter.incrementAndGet(), method, params));
        return this;
    }

    /**
     * Sets type of request keys.
     */
    public <NK> PagedBatchRequestBuilder<NK, V> keysType(Class<NK> keysClass) {
        return new PagedBatchRequestBuilder<>(transport, mapper, new ArrayList<>(), keysClass, returnType, counter);
    }

    /**
     * Sets an expected response type of requests.
     */
    public <NV> PagedBatchRequestBuilder<K, NV> returnType(Class<NV> valuesClass) {
        return new PagedBatchRequestBuilder<>(transport, mapper, requests, keysType, valuesClass, counter);
    }

    public Map<K, V> execute() throws Exception {
        return execute(DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Validates, executes the request and processes the response
     */
    public Map<K, V> execute(int maxAttempts) throws Exception {
        Map<K, V> allResults = new HashMap<>();
        JsonRpcClient client = new JsonRpcClient(transport);

        List<List<Request<K>>> pages = Lists.partition(requests, getPageSize());
        for(List<Request<K>> page : pages) {
            if(counter != null) {
                Map<Long, K> counterIdMap = new HashMap<>();
                BatchRequestBuilder<Long, V> batchRequest = client.createBatchRequest().keysType(Long.class).returnType(returnType);
                for(Request<K> request : page) {
                    counterIdMap.put(request.counterId(), request.id());
                    batchRequest.add(request.counterId(), request.method(), request.params());
                }

                try {
                    Map<Long, V> pageResult = new RetryLogic<Map<Long, V>>(maxAttempts, RETRY_DELAY_SECS, List.of(IllegalStateException.class, IllegalArgumentException.class)).getResult(batchRequest::execute);
                    for(Map.Entry<Long, V> pageEntry : pageResult.entrySet()) {
                        allResults.put(counterIdMap.get(pageEntry.getKey()), pageEntry.getValue());
                    }
                } catch(JsonRpcBatchException e) {
                    Map<Object, Object> mappedSuccesses = new HashMap<>();
                    for(Map.Entry<?, ?> successEntry : e.getSuccesses().entrySet()) {
                        mappedSuccesses.put(counterIdMap.get((Long)successEntry.getKey()), successEntry.getValue());
                    }
                    Map<Object, ErrorMessage> mappedErrors = new HashMap<>();
                    for(Map.Entry<?, ErrorMessage> errorEntry : e.getErrors().entrySet()) {
                        mappedErrors.put(counterIdMap.get((Long)errorEntry.getKey()), errorEntry.getValue());
                    }
                    throw new JsonRpcBatchException(e.getMessage(), mappedSuccesses, mappedErrors);
                }
            } else {
                BatchRequestBuilder<K, V> batchRequest = client.createBatchRequest().keysType(keysType).returnType(returnType);
                for(Request<K> request : page) {
                    if(request.id() instanceof String strReq) {
                        batchRequest.add(strReq, request.method(), request.params());
                    } else if(request.id() instanceof Integer intReq) {
                        batchRequest.add(intReq, request.method(), request.params());
                    } else {
                        throw new IllegalArgumentException("Id of class " + request.id().getClass().getName() + " not supported");
                    }
                }

                Map<K, V> pageResult = new RetryLogic<Map<K, V>>(maxAttempts, RETRY_DELAY_SECS, List.of(IllegalStateException.class, IllegalArgumentException.class)).getResult(batchRequest::execute);
                allResults.putAll(pageResult);
            }
        }

        return allResults;
    }

    private int getPageSize() {
        return Config.get().getCore().getRpcBatchSizeValue();
    }

    /**
     * Creates a builder of a JSON-RPC batch request in initial state
     */
    public static PagedBatchRequestBuilder<?, ?> create(Transport transport) {
        return new PagedBatchRequestBuilder<>(transport, new ObjectMapper(), null);
    }

    /**
     * Creates a builder of a JSON-RPC batch request in initial state with a counter for request ids
     */
    public static PagedBatchRequestBuilder<?, ?> create(Transport transport, AtomicLong counter) {
        return new PagedBatchRequestBuilder<>(transport, new ObjectMapper(), counter);
    }

    private record Request<K>(K id, Long counterId, String method, Object[] params) {}
}
