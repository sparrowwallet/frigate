package com.sparrowwallet.frigate.control;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.electrum.ElectrumBlockHeader;
import com.sparrowwallet.frigate.index.SilentPaymentsBlocksIndexUpdate;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Server;
import com.sparrowwallet.frigate.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BaseMultiResolutionImage;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

public class TrayManager {
    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    private final TrayIcon trayIcon;
    private final PopupMenu popupMenu = new PopupMenu();
    private final MenuItem statusItem;

    private int tipHeight = -1;
    private int indexedHeight = -1;

    public TrayManager() {
        if(!isSupported()) {
            throw new UnsupportedOperationException("System tray is not supported on this platform.");
        }

        SystemTray tray = SystemTray.getSystemTray();

        try {
            List<Image> imgList = new ArrayList<>();
            imgList.add(ImageIO.read(getClass().getResource("/image/frigate-white-small.png")));
            imgList.add(ImageIO.read(getClass().getResource("/image/frigate-white-small@2x.png")));
            imgList.add(ImageIO.read(getClass().getResource("/image/frigate-white-small@3x.png")));

            BaseMultiResolutionImage mrImage = new BaseMultiResolutionImage(imgList.toArray(new Image[0]));
            this.trayIcon = new TrayIcon(mrImage, "Frigate", popupMenu);

            MenuItem versionItem = new MenuItem(Frigate.SERVER_NAME + " " + Frigate.SERVER_VERSION + " (" + Network.get().getName() + ")");
            versionItem.setEnabled(false);
            popupMenu.add(versionItem);

            statusItem = new MenuItem(Config.get().getCore().shouldConnect() ? "Starting..." : "Indexing Disabled");
            statusItem.setEnabled(false);
            popupMenu.add(statusItem);

            MenuItem configItem = new MenuItem("Open Config Folder");
            configItem.addActionListener(e -> {
                try {
                    Desktop.getDesktop().open(Storage.getFrigateDir());
                } catch(IOException ex) {
                    log.error("Could not open config folder", ex);
                }
            });
            popupMenu.add(configItem);

            MenuItem quitItem = new MenuItem("Quit Frigate");
            quitItem.addActionListener(e -> System.exit(0));
            popupMenu.add(quitItem);

            tray.add(trayIcon);
        } catch(IOException | AWTException e) {
            log.error("Could not initialize system tray", e);
            throw new IllegalStateException(e);
        }
    }

    @Subscribe
    public void electrumBlockHeader(ElectrumBlockHeader header) {
        tipHeight = header.height();
        indexedHeight = -1;
        updateStatusItem();
    }

    @Subscribe
    public void silentPaymentsBlocksIndexUpdate(SilentPaymentsBlocksIndexUpdate update) {
        indexedHeight = update.toBlockHeight();
        updateStatusItem();
    }

    private void updateStatusItem() {
        if(tipHeight == -1) {
            statusItem.setLabel("Starting...");
        } else if(indexedHeight >= 0 && indexedHeight < tipHeight) {
            NumberFormat nf = NumberFormat.getIntegerInstance();
            statusItem.setLabel("Indexing: block " + nf.format(indexedHeight) + " / " + nf.format(tipHeight));
        } else {
            Config.ServerConfig sc = Config.get().getServer();
            Server tcpServer = sc.getTcpServer();
            Server sslServer = sc.getSslServer();
            List<String> ports = new ArrayList<>(2);
            if(tcpServer != null) ports.add(Integer.toString(tcpServer.getHostAndPort().getPort()));
            if(sslServer != null) ports.add(Integer.toString(sslServer.getHostAndPort().getPort()));
            statusItem.setLabel("Electrum server on port " + String.join("/", ports));
        }
    }

    public static boolean isSupported() {
        return OsType.getCurrent() == OsType.MACOS && Desktop.isDesktopSupported() && SystemTray.isSupported();
    }
}
