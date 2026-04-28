package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SilentPaymentsTxEntry implements Comparable<SilentPaymentsTxEntry> {
    public int height;
    public String tx_hash;
    public String tweak_key;

    public SilentPaymentsTxEntry() {
    }

    public SilentPaymentsTxEntry(int height, String tx_hash, String tweak_key) {
        this.height = height;
        this.tx_hash = tx_hash;
        this.tweak_key = tweak_key;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) {
            return true;
        }
        if(!(o instanceof SilentPaymentsTxEntry that)) {
            return false;
        }

        return height == that.height && Objects.equals(tx_hash, that.tx_hash) && Objects.equals(tweak_key, that.tweak_key);
    }

    @Override
    public int hashCode() {
        int result = height;
        result = 31 * result + Objects.hashCode(tx_hash);
        result = 31 * result + Objects.hashCode(tweak_key);
        return result;
    }

    @Override
    public int compareTo(SilentPaymentsTxEntry o) {
        if(height <= 0 && o.height > 0) {
            return 1;
        }
        if(height > 0 && o.height <= 0) {
            return -1;
        }
        if(height != o.height) {
            return height - o.height;
        }
        return tx_hash.compareTo(o.tx_hash);
    }
}
