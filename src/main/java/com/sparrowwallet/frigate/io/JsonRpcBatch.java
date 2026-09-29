package com.sparrowwallet.frigate.io;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;

public final class JsonRpcBatch {
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final String SILENT_PAYMENTS_SUBSCRIBE = "blockchain.silentpayments.subscribe";

    private JsonRpcBatch() {
    }

    /**
     * Counts the items of a JSON-RPC batch with a streaming parse, skipping each item's contents without building a tree.
     * Counting stops once past the limit, so the cost is bounded by the line length.
     * @return -1 if the line is not a JSON array (a single request, or malformed JSON, which is left to the JSON-RPC server to
     * report), otherwise the number of items, or limit + 1 if there are more than limit
     */
    public static int countItems(String line, int limit) {
        return summarize(line, limit).items();
    }

    /**
     * Summarizes a request line in one streaming pass: its batch item count (as countItems) and how many of its requests are
     * silent payments subscribes, which are costlier to serve than other requests.
     */
    public static Summary summarize(String line, int limit) {
        try(JsonParser parser = JSON_FACTORY.createParser(line)) {
            JsonToken first = parser.nextToken();
            if(first == JsonToken.START_OBJECT) {
                return new Summary(-1, isSilentPaymentsSubscribe(parser) ? 1 : 0);
            }
            if(first != JsonToken.START_ARRAY) {
                return new Summary(-1, 0);
            }

            int count = 0;
            int silentPaymentsSubscribes = 0;
            JsonToken token;
            while((token = parser.nextToken()) != JsonToken.END_ARRAY && token != null) {
                if(token == JsonToken.START_OBJECT) {
                    if(isSilentPaymentsSubscribe(parser)) {
                        silentPaymentsSubscribes++;
                    }
                } else {
                    parser.skipChildren();
                }
                if(++count > limit) {
                    return new Summary(count, silentPaymentsSubscribes);
                }
            }
            return new Summary(count, silentPaymentsSubscribes);
        } catch(IOException e) {
            //too malformed to summarize: the JSON-RPC server rejects it as a parse error
            return new Summary(-1, 0);
        }
    }

    /**
     * Reads the fields of the object the parser is at the start of, through its end.
     * @return true if its method is a silent payments subscribe
     */
    private static boolean isSilentPaymentsSubscribe(JsonParser parser) throws IOException {
        boolean silentPaymentsSubscribe = false;
        while(parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if("method".equals(field) && value == JsonToken.VALUE_STRING && SILENT_PAYMENTS_SUBSCRIBE.equals(parser.getText())) {
                silentPaymentsSubscribe = true;
            }
            parser.skipChildren();
        }
        return silentPaymentsSubscribe;
    }

    /**
     * @param items the batch item count as returned by countItems: -1 if the line is not a batch
     * @param silentPaymentsSubscribes the number of silent payments subscribes among the requests
     */
    public record Summary(int items, int silentPaymentsSubscribes) {
        /**
         * @return the request pacing cost: one per request, with each silent payments subscribe costing silentPaymentsSubscribeCost
         * instead, and at least one for any line
         */
        public long cost(int silentPaymentsSubscribeCost) {
            int requests = Math.max(items, 1);
            return requests + (long)silentPaymentsSubscribes * (silentPaymentsSubscribeCost - 1);
        }
    }
}
