package kr.autologpro.companion;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.CalendarContract;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import kr.autologpro.sync.SupabaseClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

public final class DriveAutomation {
    static final int CAL=201, BT=202, NOTIFY=203;
    static final String ALERT="alp_trip_action";
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("alp_companion_automation",Context.MODE_PRIVATE);}
    static JSONObject obj(Object... pairs) {
        JSONObject o=new JSONObject();
        try{for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);}catch(Exception ignored){}
        return o;
    }
    static JSONArray calendar(Context c,long start,long end){
        JSONArray result=new JSONArray();
        if(c.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED||end<start)return result;
        String[] cols={CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END,CalendarContract.Instances.ALL_DAY};
        try(var cursor=CalendarContract.Instances.query(c.getContentResolver(),cols,start,end)){
            if(cursor!=null)while(result.length()<30&&cursor.moveToNext()){
                String title=cursor.getString(0);
                if(cursor.getInt(3)==0&&title!=null)result.put(obj("title",title.substring(0,Math.min(100,title.length())),"start",cursor.getLong(1),"end",cursor.getLong(2)));
            }
        }catch(RuntimeException ignored){}
        return result;
    }
    static void showAlert(Context c,String id,String vehicle,int rev,boolean localDemo){
        boolean demo=localDemo;
        NotificationManager mgr=c.getSystemService(NotificationManager.class);
        if(mgr==null||!mgr.areNotificationsEnabled())return;
        mgr.createNotificationChannel(new NotificationChannel("alp_trips","운행 분류",NotificationManager.IMPORTANCE_DEFAULT));
        boolean reminder=id.isEmpty();
        Notification.Builder b=new Notification.Builder(c,"alp_trips").setSmallIcon(R.drawable.app_icon)
            .setContentTitle(reminder?"미분류 운행 확인":(demo?"시연 · 운행기록 도착":"운행기록 도착"))
            .setContentText(reminder?"월말 전에 운행 목적을 확인해 주세요.":"알림에서 이번 운행의 목적을 선택하세요.")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true);
        int n=(vehicle+":"+id).hashCode();
        Intent open=new Intent(c,MainActivity.class).putExtra("open_unclassified",true);
        b.setContentIntent(PendingIntent.getActivity(c,n,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        if(!reminder)for(String p:new String[]{"business","commute"}){
            Intent i=new Intent(c,AlertAction.class).setAction(ALERT).setData(android.net.Uri.parse("alp-alert://pick/"+n+"/"+p))
                .putExtra("id",id).putExtra("vehicle",vehicle).putExtra("rev",rev).putExtra("purpose",p).putExtra("demo",localDemo).putExtra("workspace",prefs(c).getString("workspace","")).putExtra("notification",n);
            b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c,R.drawable.app_icon),p.equals("business")?"일반업무":"출퇴근",PendingIntent.getBroadcast(c,n,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        mgr.notify(n,b.build());
    }
    public static final class AlertAction extends BroadcastReceiver {
        public void onReceive(Context c,Intent i){
            if(!ALERT.equals(i.getAction()))return;
            String id=i.getStringExtra("id"),p=i.getStringExtra("purpose");
            if(id==null||!("business".equals(p)||"commute".equals(p)))return;
            if(i.getBooleanExtra("demo",false)){
                prefs(c).edit().putString("demo_action",obj("id",id,"purpose",p).toString()).commit();
                c.getSystemService(NotificationManager.class).cancel(i.getIntExtra("notification",0));return;
            }
            if(!prefs(c).getString("workspace","").equals(i.getStringExtra("workspace")))return;
            PendingResult pending=goAsync();
            new SupabaseClient(c).rpc("update_trip",obj("p_trip_id",id,"p_patch",obj("purpose",p),"p_changed_at",Instant.now().toString()),(result,err,code)->{
                c.getSystemService(NotificationManager.class).cancel(i.getIntExtra("notification",0));
                if(err!=0)showAlert(c,"","",0,false);
                pending.finish();
            });
        }
    }
}
