package com.sparrowwallet.frigate.io;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.File;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * A key manager whose certificate and key can be replaced while the server runs, so a renewed certificate is served without a
 * restart. The TLS context is built once around it: new handshakes use the latest certificate, and existing sessions continue.
 *
 * A handshake chooses an alias, then separately asks for that alias's certificate chain and private key, so a reload between
 * those calls must not pair the new chain with the old key or the reverse. Each load therefore uses a distinct alias, and a
 * lookup by alias is answered by the load that issued it: the current one, or the one it replaced for a handshake in progress.
 */
public class ReloadingKeyManager extends X509ExtendedKeyManager {
    private static final String ALIAS_PREFIX = "frigate-";

    private volatile Loaded current;
    private volatile Loaded previous;
    private int generation;

    /**
     * @throws com.sparrowwallet.frigate.ConfigurationException if the certificate or key cannot be loaded
     */
    public ReloadingKeyManager(File certFile, File keyFile) {
        reload(certFile, keyFile);
    }

    /**
     * Loads the certificate and key, replacing those served if they are valid, and leaving them unchanged if not.
     * @return the loaded certificate
     * @throws com.sparrowwallet.frigate.ConfigurationException if the certificate or key cannot be loaded
     */
    public synchronized X509Certificate reload(File certFile, File keyFile) {
        String alias = ALIAS_PREFIX + (++generation);
        X509ExtendedKeyManager keyManager = SslUtil.loadKeyManager(certFile, keyFile, alias);
        previous = current;
        current = new Loaded(alias, keyManager);
        return keyManager.getCertificateChain(alias)[0];
    }

    public X509Certificate getCertificate() {
        Loaded loaded = current;
        return loaded.keyManager().getCertificateChain(loaded.alias())[0];
    }

    private X509ExtendedKeyManager forAlias(String alias) {
        Loaded loaded = current;
        if(loaded.alias().equals(alias)) {
            return loaded.keyManager();
        }
        Loaded replaced = previous;
        if(replaced != null && replaced.alias().equals(alias)) {
            return replaced.keyManager();
        }
        return loaded.keyManager();
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return current.keyManager().getClientAliases(keyType, issuers);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return current.keyManager().chooseClientAlias(keyType, issuers, socket);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return current.keyManager().chooseEngineClientAlias(keyType, issuers, engine);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return current.keyManager().getServerAliases(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return current.keyManager().chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        return current.keyManager().chooseEngineServerAlias(keyType, issuers, engine);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return forAlias(alias).getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return forAlias(alias).getPrivateKey(alias);
    }

    private record Loaded(String alias, X509ExtendedKeyManager keyManager) {}
}
