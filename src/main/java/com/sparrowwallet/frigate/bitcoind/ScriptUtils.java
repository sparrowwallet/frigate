package com.sparrowwallet.frigate.bitcoind;

import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;

public final class ScriptUtils {
    private ScriptUtils() {}

    static ScriptType getValidScriptType(byte[] scriptPubKey) {
        if(scriptPubKey == null) {
            return null;
        }

        int length = scriptPubKey.length;

        // P2PKH: 25 bytes - OP_DUP OP_HASH160 <20-byte hash> OP_EQUALVERIFY OP_CHECKSIG
        if(length == 25 &&
                scriptPubKey[0] == (byte) 0x76 &&
                scriptPubKey[1] == (byte) 0xa9 &&
                scriptPubKey[2] == (byte) 0x14 &&
                scriptPubKey[23] == (byte) 0x88 &&
                scriptPubKey[24] == (byte) 0xac) {
            return ScriptType.P2PKH;
        }

        // P2SH-P2WPKH: 23 bytes - OP_HASH160 <20-byte hash> OP_EQUAL
        if(length == 23 &&
                scriptPubKey[0] == (byte) 0xa9 &&
                scriptPubKey[1] == (byte) 0x14 &&
                scriptPubKey[22] == (byte) 0x87) {
            return ScriptType.P2SH_P2WPKH;
        }

        // P2WPKH: 22 bytes - OP_0 <20-byte hash>
        if(length == 22 &&
                scriptPubKey[0] == (byte) 0x00 &&
                scriptPubKey[1] == (byte) 0x14) {
            return ScriptType.P2WPKH;
        }

        // P2TR: 34 bytes - OP_1 <32-byte taproot output>
        if(length == 34 &&
                scriptPubKey[0] == (byte) 0x51 &&
                scriptPubKey[1] == (byte) 0x20) {
            return ScriptType.P2TR;
        }

        return null;
    }

    static boolean containsTaprootOutput(Transaction tx) {
        for(TransactionOutput txOutput : tx.getOutputs()) {
            ScriptType scriptType = getValidScriptType(txOutput.getScriptBytes());
            if(scriptType == ScriptType.P2TR) {
                return true;
            }
        }
        return false;
    }
}
