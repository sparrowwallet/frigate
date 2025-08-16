package com.sparrowwallet.frigate.index;

import com.sparrowwallet.drongo.protocol.Transaction;

public class Index {
    private int lastBlockIndexed = 97354;

    public int getLastBlockIndexed() {
        //TODO: Read from DB
        return lastBlockIndexed;
    }

    public void addToIndex(int blockHeight, Transaction transaction) {
        //TODO: Add to index
        System.out.println("Adding " + transaction.getTxId() + " to index");
        this.lastBlockIndexed = blockHeight;
    }
}
