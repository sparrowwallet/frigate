package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VerboseBlockTest {
    private static final String V3_JSON = """
            {
              "hash": "0000000000000000000111111111111111111111111111111111111111111111",
              "confirmations": 1,
              "height": 800000,
              "time": 1690000000,
              "previousblockhash": "0000000000000000000222222222222222222222222222222222222222222222",
              "tx": [
                {
                  "txid": "aaaa000000000000000000000000000000000000000000000000000000000000",
                  "hash": "aaaa000000000000000000000000000000000000000000000000000000000000",
                  "hex": "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03520101ffffffff0100000000000000000000000000",
                  "vin": [
                    {
                      "coinbase": "520101",
                      "sequence": 4294967295
                    }
                  ],
                  "vout": [
                    {
                      "value": 0.0,
                      "n": 0,
                      "scriptPubKey": {
                        "asm": "",
                        "hex": "",
                        "type": "nonstandard"
                      }
                    }
                  ]
                },
                {
                  "txid": "bbbb000000000000000000000000000000000000000000000000000000000000",
                  "hex": "0200000001cccc00000000000000000000000000000000000000000000000000000000000000000000ffffffff0100000000000000002251200000000000000000000000000000000000000000000000000000000000000abc00000000",
                  "vin": [
                    {
                      "txid": "cccc000000000000000000000000000000000000000000000000000000000000",
                      "vout": 0,
                      "scriptSig": {"asm": "", "hex": ""},
                      "sequence": 4294967295,
                      "prevout": {
                        "generated": false,
                        "height": 799999,
                        "value": 0.001,
                        "scriptPubKey": {
                          "asm": "OP_1 abcd",
                          "hex": "5120000000000000000000000000000000000000000000000000000000000000abcd",
                          "address": "bc1pdummy",
                          "type": "witness_v1_taproot"
                        }
                      }
                    }
                  ],
                  "vout": [
                    {
                      "value": 0.0009,
                      "n": 0,
                      "scriptPubKey": {
                        "hex": "5120000000000000000000000000000000000000000000000000000000000000abc0",
                        "type": "witness_v1_taproot"
                      }
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    public void deserialisesV3Block() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VerboseBlock vb = mapper.readValue(V3_JSON, VerboseBlock.class);

        assertEquals("0000000000000000000111111111111111111111111111111111111111111111", vb.hash());
        assertEquals(800000, vb.height());
        assertEquals(1690000000L, vb.time());
        assertEquals(2, vb.tx().size());
    }

    @Test
    public void coinbaseInputHasNoTxid() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VerboseBlock vb = mapper.readValue(V3_JSON, VerboseBlock.class);

        VerboseBlock.VerboseTransaction coinbase = vb.tx().get(0);
        VerboseBlock.VerboseVin vin = coinbase.vin().get(0);
        assertTrue(vin.isCoinbase());
        assertNull(vin.txid());
        assertNull(vin.prevout());
    }

    @Test
    public void nonCoinbaseInputCarriesPrevoutScriptPubKey() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VerboseBlock vb = mapper.readValue(V3_JSON, VerboseBlock.class);

        VerboseBlock.VerboseTransaction spending = vb.tx().get(1);
        VerboseBlock.VerboseVin vin = spending.vin().get(0);
        assertFalse(vin.isCoinbase());
        assertEquals("cccc000000000000000000000000000000000000000000000000000000000000", vin.txid());
        assertEquals(0, vin.voutIndex().intValue());
        assertNotNull(vin.prevout());
        assertNotNull(vin.prevout().scriptPubKey());
        assertEquals("5120000000000000000000000000000000000000000000000000000000000000abcd", vin.prevout().scriptPubKey().hex());
    }

    @Test
    public void outputCarriesScriptPubKey() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VerboseBlock vb = mapper.readValue(V3_JSON, VerboseBlock.class);

        VerboseBlock.VerboseTransaction spending = vb.tx().get(1);
        VerboseBlock.VerboseVout vout = spending.vout().get(0);
        assertEquals(0, vout.n());
        assertEquals("5120000000000000000000000000000000000000000000000000000000000000abc0", vout.scriptPubKey().hex());
    }
}
