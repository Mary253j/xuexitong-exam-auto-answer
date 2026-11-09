package cn.local.quizhelper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The supplied key is opaque and non-exportable when obtained from AndroidKeyStore. */
final class SecretBox {
    private static final byte[] AAD = "cn.local.quizhelper.api-key.v1".getBytes(StandardCharsets.UTF_8);
    private SecretBox() {}
    static String seal(SecretKey key, String plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return "v1." + Base64.getEncoder().encodeToString(cipher.getIV()) + "." + Base64.getEncoder().encodeToString(ciphertext);
    }
    static String open(SecretKey key, String envelope) throws GeneralSecurityException {
        try {
            String[] pieces = envelope.split("\\.", -1);
            if (pieces.length != 3 || !"v1".equals(pieces[0])) throw new GeneralSecurityException("Unsupported encrypted value");
            byte[] iv = Base64.getDecoder().decode(pieces[1]);
            byte[] ciphertext = Base64.getDecoder().decode(pieces[2]);
            if (iv.length != 12 || ciphertext.length < 16) throw new GeneralSecurityException("Invalid encrypted value");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) { throw new GeneralSecurityException("Invalid encrypted value"); }
    }
}
