package com.sparrowwallet.frigate.io;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;

public final class JsonRpcBatch {
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private JsonRpcBatch() {
    }

    /**
     * Counts the items of a JSON-RPC batch with a streaming parse, skipping each item's contents without building a tree.
     * Counting stops once past the limit, so the cost is bounded by the line length.
     * @return -1 if the line is not a JSON array (a single request, or malformed JSON, which is left to the JSON-RPC server to
     * report), otherwise the number of items, or limit + 1 if there are more than limit
     */
    public static int countItems(String line, int limit) {
        try(JsonParser parser = JSON_FACTORY.createParser(line)) {
            if(parser.nextToken() != JsonToken.START_ARRAY) {
                return -1;
            }
            int count = 0;
            JsonToken token;
            while((token = parser.nextToken()) != JsonToken.END_ARRAY && token != null) {
                parser.skipChildren();
                if(++count > limit) {
                    return count;
                }
            }
            return count;
        } catch(IOException e) {
            //an array too malformed to count: the JSON-RPC server rejects it as a parse error
            return -1;
        }
    }
}
