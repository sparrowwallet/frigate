package com.sparrowwallet.frigate.index;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class IndexTest {
    @Test
    public void testIndex() {
        byte[] hash = Utils.hexToBytes("3e9fce73d4e77a4809908e3c3a2e54ee147b9312dc5044a193d1fc85de46e3c1");
        long hashPrefix = Index.getHashPrefix(hash, 0);
        Assertions.assertEquals(hashPrefix, 4512552348537027144L);
    }

    @Test
    public void testIndexNegative() {
        byte[] hash = Utils.hexToBytes("f4c2da807f89cb1501f1a77322a895acfb93c28e08ed2724d2beb8e44539ba38");
        long hashPrefix = Index.getHashPrefix(hash, 0);
        Assertions.assertEquals(hashPrefix, -809844737542862059L);
    }

    @Test
    public void testIndexOffset() {
        byte[] hash = Utils.hexToBytes("3e9fce73d4e77a4809908e3c3a2e54ee147b9312dc5044a193d1fc85de46e3c1");
        long hashPrefix = Index.getHashPrefix(hash, 1);
        Assertions.assertEquals(hashPrefix, -6931475418222802935L);
    }
}
