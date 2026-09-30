package com.sparrowwallet.frigate.index;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.io.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class IndexResumeTest {
    private static final int START_HEIGHT = 709632;

    @TempDir
    Path tempDir;

    private String previousHome;
    private final List<SilentPaymentsBlocksIndexUpdate> updates = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() {
        //the index extracts the scanning extension under Frigate's home, so keep it out of the real one
        previousHome = System.getProperty(Frigate.APP_HOME_PROPERTY);
        System.setProperty(Frigate.APP_HOME_PROPERTY, tempDir.resolve("home").toString());
        Config config = new Config();
        config.getDatabase().setUrl("jdbc:duckdb:" + tempDir.resolve("frigate.duckdb"));
        Config.setInstance(config);
        Frigate.getEventBus().register(this);
    }

    @AfterEach
    public void tearDown() {
        Frigate.getEventBus().unregister(this);
        Config.setInstance(null);
        if(previousHome == null) {
            System.clearProperty(Frigate.APP_HOME_PROPERTY);
        } else {
            System.setProperty(Frigate.APP_HOME_PROPERTY, previousHome);
        }
    }

    @Subscribe
    public void blocksIndexUpdate(SilentPaymentsBlocksIndexUpdate update) {
        updates.add(update);
    }

    private static Map<BlockTransaction, byte[]> block(int height, int nonce) {
        Transaction tx = new Transaction();
        tx.setLocktime(nonce);
        BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), height, null, 0L, tx, null);
        return Map.of(blkTx, new byte[33]);
    }

    @Test
    public void firstBlockAfterRestartReportsOnlyItself() {
        Index index = new Index(START_HEIGHT, false, 1000);
        index.addToIndex(969302, Sha256Hash.ZERO_HASH.getBytes(), block(969302, 1));
        index.close();
        updates.clear();

        //a restart with the index up to date: nothing is caught up before the next block arrives
        Index restarted = new Index(START_HEIGHT, false, 1000);
        try {
            restarted.addToIndex(969303, Sha256Hash.ZERO_HASH.getBytes(), block(969303, 2));
        } finally {
            restarted.close();
        }

        assertEquals(1, updates.size());
        assertEquals(969303, updates.getFirst().fromBlockHeight());
        assertEquals(969303, updates.getFirst().toBlockHeight());
    }
}
