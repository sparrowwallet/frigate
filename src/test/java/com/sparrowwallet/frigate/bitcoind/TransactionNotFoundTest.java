package com.sparrowwallet.frigate.bitcoind;

import com.github.arteam.simplejsonrpc.client.exception.JsonRpcException;
import com.github.arteam.simplejsonrpc.core.domain.ErrorMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionNotFoundTest {
    private static JsonRpcException error(int code, String message) {
        return new JsonRpcException(new ErrorMessage(code, message, null));
    }

    @Test
    public void onlyATransactionMissingFromMempoolAndChainIsNotFound() {
        //getrawtransaction's messages from Bitcoin Core's rpc/rawtransaction.cpp
        assertTrue(BitcoindClient.isTransactionNotFound(error(-5, "No such mempool or blockchain transaction. Use gettransaction for wallet transactions.")));

        //without a txindex, or while it is being built, a confirmed transaction cannot be found: a configuration problem to report, not skip
        assertFalse(BitcoindClient.isTransactionNotFound(error(-5, "No such mempool transaction. Use -txindex or provide a block hash to enable blockchain transaction queries. "
                + "Use gettransaction for wallet transactions.")));
        assertFalse(BitcoindClient.isTransactionNotFound(error(-5, "No such mempool or blockchain transaction. Blockchain transactions are still in the process of being indexed. "
                + "Use gettransaction for wallet transactions.")));

        assertFalse(BitcoindClient.isTransactionNotFound(error(-8, "parameter 1 must be hexadecimal string")));
    }
}
