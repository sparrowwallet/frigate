package com.sparrowwallet.frigate.io;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.frigate.Frigate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

public class Storage {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);

    public static final String FRIGATE_DIR = ".frigate";
    public static final String WINDOWS_FRIGATE_DIR = "Frigate";

    public static File getFrigateDir() {
        File frigateDir;
        Network network = Network.get();
        if(network != Network.MAINNET) {
            frigateDir = new File(getFrigateHome(), network.getHome());
            if(!network.getName().equals(network.getHome()) && !frigateDir.exists()) {
                File networkNameDir = new File(getFrigateHome(), network.getName());
                if(networkNameDir.exists() && networkNameDir.isDirectory() && !Files.isSymbolicLink(networkNameDir.toPath())) {
                    try {
                        if(networkNameDir.renameTo(frigateDir) && !isWindows()) {
                            Files.createSymbolicLink(networkNameDir.toPath(), Path.of(frigateDir.getName()));
                        }
                    } catch(Exception e) {
                        log.debug("Error creating symlink from " + networkNameDir.getAbsolutePath() + " to " + frigateDir.getName(), e);
                    }
                }
            }
        } else {
            frigateDir = getFrigateHome();
        }

        if(!frigateDir.exists()) {
            createOwnerOnlyDirectory(frigateDir);
        }

        if(!network.getName().equals(network.getHome()) && !isWindows()) {
            try {
                Path networkNamePath = getFrigateHome().toPath().resolve(network.getName());
                if(Files.isSymbolicLink(networkNamePath)) {
                    Path symlinkTarget = getFrigateHome().toPath().resolve(Files.readSymbolicLink(networkNamePath));
                    if(!Files.isSameFile(frigateDir.toPath(), symlinkTarget)) {
                        Files.delete(networkNamePath);
                        Files.createSymbolicLink(networkNamePath, Path.of(frigateDir.getName()));
                    }
                } else if(!Files.exists(networkNamePath)) {
                    Files.createSymbolicLink(networkNamePath, Path.of(frigateDir.getName()));
                }
            } catch(Exception e) {
                log.debug("Error updating symlink from " + network.getName() + " to " + frigateDir.getName(), e);
            }
        }

        return frigateDir;
    }

    public static File getFrigateHome() {
        return getFrigateHome(false);
    }

    public static File getFrigateHome(boolean useDefault) {
        if(!useDefault && System.getProperty(Frigate.APP_HOME_PROPERTY) != null) {
            return new File(System.getProperty(Frigate.APP_HOME_PROPERTY));
        }

        if(isWindows()) {
            return new File(getHomeDir(), WINDOWS_FRIGATE_DIR);
        }

        return new File(getHomeDir(), FRIGATE_DIR);
    }

    static File getHomeDir() {
        if(isWindows()) {
            return new File(System.getenv("APPDATA"));
        }

        return new File(System.getProperty("user.home"));
    }

    public static boolean createOwnerOnlyDirectory(File directory) {
        try {
            if(isWindows()) {
                Files.createDirectories(directory.toPath());
                return true;
            }

            Files.createDirectories(directory.toPath(), PosixFilePermissions.asFileAttribute(getDirectoryOwnerOnlyPosixFilePermissions()));
            return true;
        } catch(UnsupportedOperationException e) {
            return directory.mkdirs();
        } catch(IOException e) {
            log.error("Could not create directory " + directory.getAbsolutePath(), e);
        }

        return false;
    }

    public static boolean createOwnerOnlyFile(File file) {
        try {
            if(isWindows()) {
                Files.createFile(file.toPath());
                return true;
            }

            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(getFileOwnerOnlyPosixFilePermissions()));
            return true;
        } catch(UnsupportedOperationException e) {
            return false;
        } catch(IOException e) {
            log.error("Could not create file " + file.getAbsolutePath(), e);
        }

        return false;
    }

    private static Set<PosixFilePermission> getDirectoryOwnerOnlyPosixFilePermissions() {
        Set<PosixFilePermission> ownerOnly = getFileOwnerOnlyPosixFilePermissions();
        ownerOnly.add(PosixFilePermission.OWNER_EXECUTE);

        return ownerOnly;
    }

    private static Set<PosixFilePermission> getFileOwnerOnlyPosixFilePermissions() {
        Set<PosixFilePermission> ownerOnly = EnumSet.noneOf(PosixFilePermission.class);
        ownerOnly.add(PosixFilePermission.OWNER_READ);
        ownerOnly.add(PosixFilePermission.OWNER_WRITE);

        return ownerOnly;
    }

    private static boolean isWindows() {
        return OsType.getCurrent() == OsType.WINDOWS;
    }
}
