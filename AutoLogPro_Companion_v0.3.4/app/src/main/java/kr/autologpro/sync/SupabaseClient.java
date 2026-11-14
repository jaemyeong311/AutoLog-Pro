package kr.autologpro.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes token rotation for requests from the UI and background sync. */
public final class SupabaseClient {
    public interface Callback { void complete(Object value,int status,String code); }
    public interface AuthCheck { Object validate(SupabaseTransport transport,SupabaseTransport.Session session) throws IOException; }
    private interface Job { Object run() throws Exception; }
    private interface AuthorizedJob { Object run(SupabaseTransport transport,SupabaseTransport.Session session) throws IOException; }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final SupabaseSessionStore store;
    private final Handler ui=new Handler(Looper.getMainLooper());

    public SupabaseClient(Context context){store=new SupabaseSessionStore(context);}
    public String publicKey(){return store.publicKey();}
    public boolean signedIn(){return store.hasSession();}

    public void signIn(String publicKey,String email,String password,AuthCheck validator,Callback callback){
        schedule(()->{
            SupabaseTransport t=new SupabaseTransport(SupabaseTransport.DEFAULT_ORIGIN,publicKey);
            SupabaseTransport.Session s=t.signIn(email,password);
            Object context=validator.validate(t,s);
            store.save(publicKey,s);
            return context;
        },callback);
    }

    public void signOut(Callback callback){
        schedule(()->{
            String key=store.publicKey();
            SupabaseTransport.Session s=store.read();
            try {if(s!=null)new SupabaseTransport(SupabaseTransport.DEFAULT_ORIGIN,key).signOut(s);}
            finally {store.clear();}
            return JSONObject.NULL;
        },callback);
    }

    public void rpc(String name,JSONObject args,Callback callback){
        schedule(()->authorized((transport,session)->transport.rpc(session,name,args)),callback);
    }

    public void readTable(String name,String query,Callback callback){
        schedule(()->authorized((t,s)->t.readTable(s,name,query)),callback);
    }
    public void download(String url,Callback callback){
        schedule(()->new SupabaseTransport(SupabaseTransport.DEFAULT_ORIGIN,store.publicKey()).download(url),callback);
    }
    public void function(String name,JSONObject args,Callback callback){
        schedule(()->authorized((transport,session)->transport.function(session,name,args)),callback);
    }

    private Object authorized(AuthorizedJob job) throws Exception {
        String key=store.publicKey();
        SupabaseTransport.Session s=store.read();
        if(s==null)throw new SupabaseTransport.ApiException(401,"signed_out");
        SupabaseTransport t=new SupabaseTransport(SupabaseTransport.DEFAULT_ORIGIN,key);
        if(s.shouldRefresh())s=refresh(t,s,key);
        try{return job.run(t,s);}
        catch(SupabaseTransport.ApiException e){
            if(e.status!=401||"PT401".equals(e.code))throw e;
            return job.run(t,refresh(t,s,key));
        }
    }

    private SupabaseTransport.Session refresh(SupabaseTransport t,SupabaseTransport.Session s,String key) throws Exception {
        try{
            SupabaseTransport.Session next=t.refresh(s.refreshToken);
            store.save(key,next);
            return next;
        }catch(SupabaseTransport.ApiException e){
            if(e.status==400||e.status==401||e.status==403)store.clear();
            throw e;
        }
    }

    private void schedule(Job task,Callback callback){
        IO.execute(()->{
            Object value=null;int status=0;String code="";
            try{value=task.run();}
            catch(SupabaseTransport.ApiException e){status=e.status;code=e.code;}
            catch(IllegalArgumentException e){status=400;code="invalid_input";}
            catch(IOException e){status=503;code="network_or_storage";}
            catch(Exception e){status=500;code="local_session_error";}
            final Object result=value;final int error=status;final String reason=code;
            ui.post(()->callback.complete(result,error,reason));
        });
    }
}
