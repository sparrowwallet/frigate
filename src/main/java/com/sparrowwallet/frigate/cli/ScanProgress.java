package com.sparrowwallet.frigate.cli;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.index.TxEntry;

import java.util.ArrayList;
import java.util.List;

public class ScanProgress {
    private static final int PROGRESS_BAR_WIDTH = 50;
    public static final double SCAN_PROGRESS_COMPLETE = 1.0;

    private final String address;
    private final boolean canComplete;
    private final boolean showProgress;
    private final List<TxEntry> results = new ArrayList<>();

    private volatile boolean isComplete = false;
    private volatile boolean isInitialComplete = false;
    private final Object completionLock = new Object();

    public ScanProgress(String address, boolean canComplete, boolean showProgress) {
        this.address = address;
        this.canComplete = canComplete;
        this.showProgress = showProgress;
    }

    private void updateProgressBar(SilentPaymentsNotification notification) {
        if(!isInitialComplete) {
            results.addAll(notification.history());
        } else {
            results.clear();
            results.addAll(notification.history());
        }

        double percentage = notification.progress();
        int filledLength = (int) (PROGRESS_BAR_WIDTH * percentage);

        StringBuilder progressBar = new StringBuilder();
        progressBar.append("\r");

        for (int i = 0; i < PROGRESS_BAR_WIDTH; i++) {
            if (i < filledLength) {
                progressBar.append("█");
            } else {
                progressBar.append(" ");
            }
        }

        if(!isInitialComplete && showProgress) {
            progressBar.append(String.format(" %.1f%%", percentage * 100d));
            System.out.print(progressBar);
        }

        if(percentage == SCAN_PROGRESS_COMPLETE) {
            if(!isInitialComplete) {
                isInitialComplete = true;
                if(showProgress) {
                    System.out.println();
                }
            }

            if(canComplete || !results.isEmpty()) {
                GsonBuilder gsonBuilder = new GsonBuilder();
                Gson gson = gsonBuilder.setPrettyPrinting().disableHtmlEscaping().create();
                gson.toJson(results, System.out);
                System.out.println();
            }
        }
    }

    public void waitForCompletion() throws InterruptedException {
        synchronized (completionLock) {
            while (!isComplete) {
                completionLock.wait();
            }
        }
    }

    public boolean isComplete() {
        return isComplete;
    }

    @Subscribe
    public void silentPaymentsNotification(SilentPaymentsNotification notification) {
        if(notification.subscription().address().equals(address)) {
            updateProgressBar(notification);

            if(notification.progress() == SCAN_PROGRESS_COMPLETE && canComplete) {
                synchronized(completionLock) {
                    isComplete = true;
                    completionLock.notifyAll();
                }
            }
        }
    }
}
