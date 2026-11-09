package cn.local.quizhelper;

import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class SecretBoxTest {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void rejects(SecretKey key,String value)throws Exception{
        try{SecretBox.open(key,value);}catch(GeneralSecurityException expected){checks++;return;}
        throw new AssertionError("Unauthenticated value accepted");
    }
    public static void main(String[] args)throws Exception{
        KeyGenerator gen=KeyGenerator.getInstance("AES");gen.init(256);SecretKey key=gen.generateKey();
        String sample="dummy-value-for-tests-only";
        String first=SecretBox.seal(key,sample),second=SecretBox.seal(key,sample);
        check(SecretBox.open(key,first).equals(sample),"reload after saving");
        check(!first.equals(second),"fresh randomized IV on every save");
        check(!first.contains(sample),"plaintext not persisted");
        rejects(gen.generateKey(),first);
        String[] parts=first.split("\\.");
        byte[] bytes=Base64.getDecoder().decode(parts[2]);bytes[0]^=1;
        rejects(key,parts[0]+"."+parts[1]+"."+Base64.getEncoder().encodeToString(bytes));
        byte[] iv=Base64.getDecoder().decode(parts[1]);iv[0]^=1;
        rejects(key,parts[0]+"."+Base64.getEncoder().encodeToString(iv)+"."+parts[2]);
        rejects(key,"v9."+parts[1]+"."+parts[2]);
        rejects(key,"v1.a.a");rejects(key,"v1.." );rejects(key,"");
        System.out.println("Credential envelope checks passed: "+checks+" (desktop provider; Android Keystore requires device testing)");
    }
}
