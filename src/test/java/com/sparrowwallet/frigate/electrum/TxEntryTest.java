package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TxEntryTest {
    @Test
    public void scripthashEntryHasNoTweakKeyField() throws Exception {
        TxEntry entry = new TxEntry(800000, 0, "abcd");
        String json = new ObjectMapper().writeValueAsString(entry);
        Assertions.assertFalse(json.contains("\"tweak_key\""));
        Assertions.assertTrue(json.contains("\"tx_hash\""));
    }

    @Test
    public void nullFeeIsOmitted() throws Exception {
        TxEntry entry = new TxEntry(800000, 0, "abcd");
        String json = new ObjectMapper().writeValueAsString(entry);
        Assertions.assertFalse(json.contains("\"fee\""));
    }
}
