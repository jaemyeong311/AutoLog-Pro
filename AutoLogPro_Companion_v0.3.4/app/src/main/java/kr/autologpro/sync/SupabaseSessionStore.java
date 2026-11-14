package kr.autologpro.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SupabaseSessionStore {
    private static final String ALIAS="autologpro.supabase.session.v1";
    private final SharedPreferences prefs;

    public SupabaseSessionStore(Context context) {
        prefs=context.getApplicationContext().getSharedPreferences("alp_supabase_session_v1",Context.MODE_PRIVATE);
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return g.generateKey();
    }

    public synchronized void save(String publicKey,SupabaseTransport.Session session) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key());
        JSONObject obj=SupabaseTransport.object("access",session.accessToken,"refresh",session.refreshToken,"expires",session.expiresAt);
        byte[] ciphertext=cipher.doFinal(obj.toString().getBytes(StandardCharsets.UTF_8));
        if(!prefs.edit().putString("public_key",publicKey)
                .putString("nonce",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("encrypted",Base64.encodeToString(ciphertext,Base64.NO_WRAP)).commit())
            throw new java.io.IOException("Could not save session");
    }

    public String publicKey(){
        String saved=prefs.getString("public_key","");
        return SupabaseTransport.isClientKey(saved)?saved:SupabaseTransport.DEFAULT_PUBLISHABLE_KEY;
    }
    public boolean hasSession(){return prefs.contains("encrypted");}
    public synchronized SupabaseTransport.Session read() {
        String encrypted=prefs.getString("encrypted",null), nonce=prefs.getString("nonce",null);
        if(encrypted==null||nonce==null)return null;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(nonce,Base64.NO_WRAP)));
            JSONObject o=new JSONObject(new String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),StandardCharsets.UTF_8));
            return new SupabaseTransport.Session(o.getString("access"),o.getString("refresh"),o.getLong("expires"));
        } catch(Exception e){clear();return null;}
    }
    public synchronized void clear(){prefs.edit().remove("nonce").remove("encrypted").commit();}
}
