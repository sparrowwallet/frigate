package com.sparrowwallet.frigate.index;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TweakEntry {
    public String txid;
    public String tweak;

    public TweakEntry() {
    }

    public TweakEntry(String txid, String tweak) {
        this.txid = txid;
        this.tweak = tweak;
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) {
            return true;
        }
        if(!(o instanceof TweakEntry tweakEntry)) {
            return false;
        }

        return Objects.equals(txid, tweakEntry.txid) && Objects.equals(tweak, tweakEntry.tweak);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(txid);
        result = 31 * result + Objects.hashCode(tweak);
        return result;
    }

    @Override
    public String toString() {
        return "TweakEntry{" +
                "txid='" + txid + '\'' +
                ", tweak='" + tweak + '\'' +
                '}';
    }
}