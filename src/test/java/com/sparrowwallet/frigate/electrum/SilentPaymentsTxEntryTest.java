package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SilentPaymentsTxEntryTest {
    @Test
    public void serializedFormHasNoFeeField() throws Exception {
        SilentPaymentsTxEntry entry = new SilentPaymentsTxEntry(800000, "abcd", "0011");
        String json = new ObjectMapper().writeValueAsString(entry);
        Assertions.assertFalse(json.contains("\"fee\""));
        Assertions.assertTrue(json.contains("\"tweak_key\""));
        Assertions.assertTrue(json.contains("\"tx_hash\""));
        Assertions.assertTrue(json.contains("\"height\""));
    }

    @Test
    public void nullTweakKeyIsOmitted() throws Exception {
        SilentPaymentsTxEntry entry = new SilentPaymentsTxEntry(800000, "abcd", null);
        String json = new ObjectMapper().writeValueAsString(entry);
        Assertions.assertFalse(json.contains("\"tweak_key\""));
    }
}
