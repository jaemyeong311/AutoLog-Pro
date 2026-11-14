package kr.autologpro.sync;

import android.util.Base64;
import org.json.JSONObject;
import org.json.JSONTokener;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Authenticated Supabase calls. Run on the application's IO executor. */
public final class SupabaseTransport {
    public static final String DEFAULT_ORIGIN="https://yrrkazuchcamcslssaye.supabase.co";
    public static final String DEFAULT_PUBLISHABLE_KEY="sb_publishable_I4qoYwr06M4CYYqGPAd_lw_60-U67ju";
    private static final Pattern NAME=Pattern.compile("[a-z][a-z0-9_]{0,59}");
    private static final Pattern FUNCTION=Pattern.compile("[a-z][a-z0-9_-]{0,59}");
    private final String origin, publicKey;

    public SupabaseTransport(String origin,String publicKey) {
        if(!DEFAULT_ORIGIN.equals(origin)||!isClientKey(publicKey)) throw new IllegalArgumentException("Use the project's public key");
        this.origin=origin;
        this.publicKey=publicKey;
    }

    public static boolean isClientKey(String value) {
        if(value==null||value.length()>4096) return false;
        if(value.matches("sb_publishable_[A-Za-z0-9_-]{15,}")) return true;
        try {
            String[] parts=value.split("\\.",-1);
            if(parts.length!=3||parts[2].isEmpty())return false;
            byte[] claims=Base64.decode(parts[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
            JSONObject json=new JSONObject(new String(claims,StandardCharsets.UTF_8));
            return "anon".equals(json.optString("role"))&&"yrrkazuchcamcslssaye".equals(json.optString("ref"));
        } catch(Exception ignored){return false;}
    }

    public Session signIn(String email,String password) throws IOException {
        if(email==null||email.trim().isEmpty()||password==null||password.isEmpty())throw new IllegalArgumentException("Email and password required");
        return session(post("/auth/v1/token?grant_type=password",null,object("email",email,"password",password)));
    }

    public Session refresh(String refreshToken) throws IOException {
        if(refreshToken==null||refreshToken.isEmpty())throw new IllegalArgumentException("Refresh token required");
        return session(post("/auth/v1/token?grant_type=refresh_token",null,object("refresh_token",refreshToken)));
    }

    public Object rpc(Session s,String name,JSONObject args) throws IOException {
        if(!NAME.matcher(name).matches())throw new IllegalArgumentException("Invalid RPC name");
        return post("/rest/v1/rpc/"+name,s.accessToken,args);
    }

    public Object readTable(Session s,String table,String query) throws IOException {
        if(!NAME.matcher(table).matches()||query==null||query.indexOf('#')>=0||query.indexOf('\n')>=0)throw new IllegalArgumentException("Invalid table");
        return request("GET","/rest/v1/"+table+"?"+query,s.accessToken,null);
    }

    public byte[] download(String url) throws IOException {
        URL u=new URL(url);
        if(!"https".equals(u.getProtocol())||!new URL(origin).getHost().equals(u.getHost())||u.getUserInfo()!=null||
           u.getPort()!=-1||!u.getPath().startsWith("/storage/v1/object/sign/logbooks/"))
            throw new IOException("Unexpected download address");
        HttpsURLConnection c=(HttpsURLConnection)u.openConnection();
        try {
            c.setConnectTimeout(6500);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);
            int status=c.getResponseCode();
            if(status!=200)throw new ApiException(status,"download_failed");
            return read(c.getInputStream(),20_000_000);
        } finally {c.disconnect();}
    }

    public Object function(Session s,String name,JSONObject args) throws IOException {
        if(!FUNCTION.matcher(name).matches())throw new IllegalArgumentException("Invalid function name");
        return post("/functions/v1/"+name,s.accessToken,args);
    }

    public void signOut(Session s) throws IOException {
        post("/auth/v1/logout?scope=local",s.accessToken,new JSONObject());
    }

    private Object post(String path,String bearer,JSONObject args) throws IOException {
        return request("POST",path,bearer,args);
    }

    private Object request(String method,String path,String bearer,JSONObject args) throws IOException {
        HttpsURLConnection c=(HttpsURLConnection)new URL(origin+path).openConnection();
        try {
            c.setConnectTimeout(6500);c.setReadTimeout(14000);c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("apikey",publicKey);
            if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
            c.setRequestProperty("Accept","application/json");
            if(args!=null) {
                c.setRequestProperty("Content-Type","application/json");
                c.setDoOutput(true);
                byte[] body=args.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(body.length);
                try(var out=c.getOutputStream()){out.write(body);}
            }
            int status=c.getResponseCode();
            if(status==204)return JSONObject.NULL;
            boolean success=status>=200&&status<300;
            InputStream in=success?c.getInputStream():c.getErrorStream();
            byte[] payload=in==null?new byte[0]:read(in,success?4_000_000:32_000);
            if(!success)throw new ApiException(status,errorCode(payload));
            if(payload.length==0)return JSONObject.NULL;
            try {return new JSONTokener(new String(payload,StandardCharsets.UTF_8)).nextValue();}
            catch(Exception e){throw new IOException("Invalid JSON response");}
        } finally {c.disconnect();}
    }

    private static byte[] read(InputStream in,int max) throws IOException {
        try(in; ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){
                if(out.size()+n>max)throw new IOException("Response too large");
                out.write(b,0,n);
            }
            return out.toByteArray();
        }
    }

    private static String errorCode(byte[] payload){
        try {
            JSONObject o=new JSONObject(new String(payload,StandardCharsets.UTF_8));
            String code=o.optString("error_code",o.optString("code",""));
            return code.matches("[A-Za-z0-9_-]{1,80}")?code:"";
        } catch(Exception ignored){return "";}
    }

    public static JSONObject object(Object... pairs){
        try {
            JSONObject result=new JSONObject();
            for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);
            return result;
        } catch(Exception e){throw new IllegalArgumentException("Invalid JSON body",e);}
    }

    private static Session session(Object value) throws IOException {
        if(!(value instanceof JSONObject))throw new IOException("Missing Auth session");
        JSONObject o=(JSONObject)value;
        String access=o.optString("access_token",""), refresh=o.optString("refresh_token","");
        long expires=o.optLong("expires_at",0);
        if(expires<=0)expires=System.currentTimeMillis()/1000+o.optLong("expires_in",0);
        if(access.isEmpty()||refresh.isEmpty()||expires<=0)throw new IOException("Incomplete Auth session");
        return new Session(access,refresh,expires);
    }

    public static final class Session {
        final String accessToken,refreshToken;
        final long expiresAt;
        Session(String accessToken,String refreshToken,long expiresAt){
            this.accessToken=accessToken;this.refreshToken=refreshToken;this.expiresAt=expiresAt;
        }
        public boolean shouldRefresh(){return expiresAt<=System.currentTimeMillis()/1000+45;}
    }
    public static final class ApiException extends IOException {
        private static final long serialVersionUID=1L;
        public final int status;
        public final String code;
        ApiException(int status,String code){
            super("Supabase request failed ("+status+")");this.status=status;this.code=code;
        }
    }
}
