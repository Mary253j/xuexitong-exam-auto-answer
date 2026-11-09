package cn.local.quizhelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Only the authenticated ciphertext is persisted in app-private preferences. */
final class KeyVault {
    private static final String ALIAS = "quizhelper.siliconflow.key.v1";
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    KeyVault(Context context) { preferences = context.getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE); }
    private static KeyStore store() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }
    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = store();
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new GeneralSecurityException("Device encryption key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    String load() throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            String value = preferences.getString("ciphertext", null);
            return value == null ? "" : SecretBox.open(key(false), value);
        }
    }
    void save(String value) throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            if (value.isEmpty()) throw new GeneralSecurityException("Empty API key");
            String encrypted = SecretBox.seal(key(true), value);
            if (!preferences.edit().putString("ciphertext", encrypted).commit()) throw new IOException("Could not save encrypted key");
        }
    }
    void clear() throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            // Clearing preferences first ensures it cannot accidentally recover an old value.
            if (!preferences.edit().clear().commit()) throw new IOException("Could not clear encrypted key");
            KeyStore store = store(); if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        }
    }
}
