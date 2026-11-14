package kr.autologpro.companion;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import kr.autologpro.sync.SupabaseClient;
import kr.autologpro.sync.SupabaseTransport;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;

final class CloudCompanion {
    private static final Pattern UUID=Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern DATE=Pattern.compile("20[0-9]{2}-[0-9]{2}-[0-9]{2}");
    private final MainActivity a;
    private final SupabaseClient client;
    private int generation;
    CloudCompanion(MainActivity activity){a=activity;client=new SupabaseClient(activity);}
    boolean signedIn(){return client.signedIn();}
    private static JSONObject json(Object... pairs){return SupabaseTransport.object(pairs);}
    private static boolean uuid(String s){return s!=null&&UUID.matcher(s).matches();}
    private EditText field(LinearLayout v,String hint,String value,int mode){
        EditText t=new EditText(a);t.setSingleLine();t.setInputType(InputType.TYPE_CLASS_TEXT|mode);
        t.setHint(hint);t.setText(value);v.addView(t);return t;
    }
    void configure(){
        LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(36,8,36,0);
        EditText key=field(v,"Supabase 공개 키",client.publicKey(),InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText email=field(v,"직원 또는 관리자 이메일","",InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass=field(v,"계정 비밀번호","",InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(a).setTitle("Supabase 로그인")
            .setMessage(SupabaseTransport.DEFAULT_ORIGIN).setView(v).setNegativeButton("취소",null)
            .setNeutralButton("로그아웃",(d,w)->{
                generation++;
                client.signOut((value,err,code)->{a.cancelCloudNotifications();a.event("disconnected",json());});
            }).setPositiveButton("로그인",(d,w)->{
                String pub=key.getText().toString().trim();
                if(!SupabaseTransport.isClientKey(pub)){a.message("이 프로젝트의 공개용 키를 입력하세요.");return;}
                client.signIn(pub,email.getText().toString().trim(),pass.getText().toString(),
                    (transport,session)->{
                        Object result=transport.rpc(session,"my_context",new JSONObject());
                        if(!(result instanceof JSONObject)||!Arrays.asList("employee","fleet_manager","admin").contains(((JSONObject)result).optString("role")))
                            throw new IllegalArgumentException("Employee or manager account required");
                        return result;
                    },(result,err,code)->{
                        if(err!=0||!(result instanceof JSONObject)){a.message("공개 키와 계정 정보를 확인하세요. ("+err+")");return;}
                        generation++;a.cancelCloudNotifications();a.event("connected",(JSONObject)result);
                    });
                pass.setText("");
            }).show();
    }
    private void reply(String callback,Object data,int err,String code,int gen){
        if(gen!=generation){err=409;code="account_changed";data=null;}
        a.event("reply",json("callback",callback,"result",data==null?JSONObject.NULL:data,"error",err,"code",code));
    }
    void call(String op,JSONObject p,String callback){
        int gen=generation;
        SupabaseClient.Callback done=(data,err,code)->reply(callback,data,err,code,gen);
        switch(op) {
            case "context":client.rpc("my_context",new JSONObject(),done);return;
            case "vehicles":client.readTable("vehicles","select=id,plate_no,model,is_shared&order=plate_no.asc&limit=1000",done);return;
            case "access":client.readTable("vehicle_access","select=vehicle_id&limit=1000",done);return;
            case "deliveries":client.readTable("logbook_deliveries","select=id,vehicle_id,period_start,period_end,channel,status,attempts,sent_at&order=created_at.desc&limit=20",done);return;
            case "trips":
                JSONObject args=json("p_limit",150);
                String since=p.optString("since","");
                if(!since.isEmpty()){
                    try{Instant.parse(since);}catch(Exception e){done.complete(null,400,"invalid_cursor");return;}
                    try{args.put("p_since",since);}catch(Exception ignored){}
                }
                String cursor=p.optString("cursor","");
                if(uuid(cursor))try{args.put("p_since_id",cursor);}catch(Exception ignored){}
                client.rpc("sync_trips",args,done);return;
            case "edit":
                String id=p.optString("id","");
                JSONObject patch=p.optJSONObject("patch");
                if(!uuid(id)||patch==null||patch.length()<1||patch.length()>2
                    ||(patch.has("purpose")&&!Arrays.asList("business","commute","personal","unclassified").contains(patch.optString("purpose")))
                    ||(patch.has("memo")&&patch.optString("memo").length()>1000)){
                    done.complete(null,400,"invalid_edit");return;
                }
                for(java.util.Iterator<String> it=patch.keys();it.hasNext();){
                    if(!Arrays.asList("purpose","memo").contains(it.next())){done.complete(null,400,"invalid_edit");return;}
                }
                client.rpc("update_trip",json("p_trip_id",id,"p_patch",patch,"p_changed_at",Instant.now().toString()),done);return;
            default:done.complete(null,400,"unsupported");
        }
    }
    void report(String vehicle,String start,String end,String format,boolean share){
        if(!signedIn()){a.message("Supabase에 먼저 로그인하세요.");return;}
        if(!uuid(vehicle)||!DATE.matcher(start).matches()||!DATE.matcher(end).matches()||!Arrays.asList("csv","xlsx","html").contains(format)){
            a.message("차량, 기간, 파일 형식을 확인하세요.");return;
        }
        final int gen=generation;
        client.function("logbook",json("action","create","vehicle_id",vehicle,"period_start",start,"period_end",end,"format",format),(result,err,code)->{
            if(gen!=generation)return;
            if(err!=0||!(result instanceof JSONObject)){a.message("서버 운행기록부 생성에 실패했어요. ("+err+")");return;}
            JSONObject response=(JSONObject)result, file=response.optJSONObject("file");
            if(file==null){a.message("서버 파일 정보를 확인하지 못했어요.");return;}
            client.download(response.optString("download_url"),(bytes,e,reason)->{
                if(gen!=generation)return;
                if(e!=0||!(bytes instanceof byte[])){a.message("서버 파일을 내려받지 못했어요. ("+e+")");return;}
                String name="AutoLogPro_"+start+"_"+end+"."+format;
                a.saveCloudReport(name,(byte[])bytes,share);
            });
        });
    }
}
