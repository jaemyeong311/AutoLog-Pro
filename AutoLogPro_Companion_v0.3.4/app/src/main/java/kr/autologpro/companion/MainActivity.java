package kr.autologpro.companion;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import org.json.JSONObject;

import android.content.ClipData;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.Arrays;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String INDEX = "https://" + HOST + "/index.html";
    private static final int CREATE_CSV = 101;
    private static final int SCAN_VEHICLE = 102;
    private static final Map<String, String> ASSETS;
    static {
        Map<String, String> files = new HashMap<>();
        files.put("/index.html", "text/html");
        files.put("/style.css", "text/css");
        files.put("/app.js", "application/javascript");
        files.put("/automation.js", "application/javascript");
        files.put("/genesis_gv90.png", "image/png");
        ASSETS = Collections.unmodifiableMap(files);
    }
    static final Pattern FILE_NAME = Pattern.compile("AutoLogPro_[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{4}-[0-9]{2}-[0-9]{2}\\.(?:csv|xlsx|html)");
    private CloudCompanion cloud;
    private byte[] pendingCloud;
    private WebView webView;
    private String pendingCsv;
    private boolean choosingFile;

    @Override protected void onCreate(Bundle previous) {
        super.onCreate(previous);
        cloud=new CloudCompanion(this);
        if (previous != null) {
            pendingCsv = previous.getString("pendingCsv");
            choosingFile = previous.getBoolean("choosingFile");
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v, i) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }
            return i;
        });
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(245,246,247));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !INDEX.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String path = uri.getPath();
                if ("https".equals(uri.getScheme()) && HOST.equals(uri.getHost()) &&
                    uri.getPort() == -1 && uri.getQuery() == null && path != null && ASSETS.containsKey(path)) {
                    try {
                        return new WebResourceResponse(ASSETS.get(path), path.endsWith(".png") ? null : "UTF-8", 200, "OK",
                            Collections.singletonMap("X-Content-Type-Options", "nosniff"),
                            getAssets().open(path.substring(1), AssetManager.ACCESS_STREAMING));
                    } catch (IOException ignored) {}
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        webView.addJavascriptInterface(new ReportBridge(), "CompanionHost");
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // The theme sets system-bar appearance. Configure insets after the window has content.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.requestApplyInsets();
        }
        cleanOldReports();
        webView.loadUrl(INDEX);
    }

    void event(String name,JSONObject data){
        runOnUiThread(()->{
            if(webView!=null)webView.evaluateJavascript("window.alpNativeEvent&&alpNativeEvent("+JSONObject.quote(name)+","+data.toString()+")",null);
        });
    }
    void message(String text){toast(text);}
    private void permissions(String feature){
        if("calendar".equals(feature)){
            if(checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED){event("permission",DriveAutomation.obj("feature",feature,"granted",true));return;}
            new AlertDialog.Builder(this).setMessage("캘린더의 업무 일정과 운행 시간을 휴대폰 안에서 비교해요. 거절해도 운행 목적을 직접 선택할 수 있어요.")
                .setPositiveButton("계속",(d,w)->requestPermissions(new String[]{Manifest.permission.READ_CALENDAR},DriveAutomation.CAL)).setNegativeButton("취소",null).show();
        }else if("notification".equals(feature)){
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                new AlertDialog.Builder(this).setMessage("앱이 새 미분류 운행을 동기화했을 때 알려줘요. 거절해도 앱에서 직접 분류할 수 있어요.")
                    .setPositiveButton("계속",(d,w)->requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},DriveAutomation.NOTIFY)).setNegativeButton("취소",null).show();
            else {event("permission",DriveAutomation.obj("feature",feature,"granted",true));}
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] result){
        super.onRequestPermissionsResult(code,names,result);
        boolean granted=result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED;
        if(code==DriveAutomation.CAL||code==DriveAutomation.NOTIFY){
            event("permission",DriveAutomation.obj("feature",code==DriveAutomation.CAL?"calendar":"notification","granted",granted));
        }
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);emitResume();}
    @Override protected void onResume(){super.onResume();emitResume();}
    private void emitResume(){
        boolean open=getIntent()!=null&&getIntent().getBooleanExtra("open_unclassified",false);
        if(open)getIntent().removeExtra("open_unclassified");
        var p=DriveAutomation.prefs(this);
        String action=p.getString("demo_action","");
        if(webView!=null)webView.evaluateJavascript("typeof alpNativeEvent==='function'",ready->{
            if(!"true".equals(ready)){if(open&&getIntent()!=null)getIntent().putExtra("open_unclassified",true);return;}
            if(!action.isEmpty())p.edit().remove("demo_action").apply();
            event("resume",DriveAutomation.obj("open",open,"demoAction",action));
        });
    }

    private final class ReportBridge {
        @JavascriptInterface public boolean connected(){return cloud.signedIn();}
        @JavascriptInterface public String takeLaunch(){
            boolean open=getIntent()!=null&&getIntent().getBooleanExtra("open_unclassified",false);
            if(open)getIntent().removeExtra("open_unclassified");
            var prefs=DriveAutomation.prefs(MainActivity.this);String a=prefs.getString("demo_action","");
            if(!a.isEmpty())prefs.edit().remove("demo_action").commit();
            return DriveAutomation.obj("open",open,"demoAction",a).toString();
        }
        @JavascriptInterface public void nativeAction(String name){
            runOnUiThread(()->{switch(name){
                case "server":cloud.configure();break;
                case "scan":
                    startActivityForResult(new Intent(MainActivity.this, VehicleQrActivity.class), SCAN_VEHICLE);
                    break;
                case "calendar":case "notification":permissions(name);break;
            }});
        }
        @JavascriptInterface public String calendar(long start,long end){
            return end>=start&&end-start<=2*86400000L?DriveAutomation.calendar(MainActivity.this,start,end).toString():"[]";
        }
        @JavascriptInterface public void demoAlert(String id){
            if(id!=null&&id.matches("TR-[0-9-]{5,40}"))runOnUiThread(()->DriveAutomation.showAlert(MainActivity.this,id,"demo",0,true));
        }
        @JavascriptInterface public void server(String op,String json,String callback){
            if(json==null||json.length()>12000||callback==null||!callback.matches("[0-9]{1,12}"))return;
            runOnUiThread(()->{try{cloud.call(op,new JSONObject(json),callback);}
                catch(Exception ignored){event("reply",DriveAutomation.obj("callback",callback,"error",400));}});
        }
        @JavascriptInterface public void cloudReport(String vehicle,String start,String end,String format,boolean share){
            if(vehicle!=null&&start!=null&&end!=null&&format!=null)runOnUiThread(()->cloud.report(vehicle,start,end,format,share));
        }
        @JavascriptInterface public void cloudAlert(String id,String account){
            if(id==null||!id.matches("[0-9a-fA-F-]{36}")||account==null||!account.matches("[0-9a-fA-F-]{36}"))return;
            runOnUiThread(()->{
                if(!cloud.signedIn())return;
                DriveAutomation.prefs(MainActivity.this).edit().putString("workspace",account).apply();
                DriveAutomation.showAlert(MainActivity.this,id,"",0,false);
            });
        }
        @JavascriptInterface public void exportReport(String mode, String name, String csv) {
            if (name == null || !FILE_NAME.matcher(name).matches() || csv == null || csv.length() > 300_000) return;
            runOnUiThread(() -> {
                if ("save".equals(mode)) saveReport(name, csv);
                else if ("share".equals(mode)) shareReport(name, csv);
            });
        }
    }

    private void saveReport(String name, String csv) {
        if (choosingFile) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name);
        pendingCsv = csv;
        choosingFile = true;
        try { startActivityForResult(intent, CREATE_CSV); }
        catch (RuntimeException err) {
            choosingFile = false;
            pendingCsv = null;
            toast("파일 저장 화면을 열 수 없어요.");
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pendingCsv", pendingCsv);
        outState.putBoolean("choosingFile", choosingFile);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==SCAN_VEHICLE){
            if(resultCode==RESULT_OK&&data!=null){
                String id=data.getStringExtra("vehicle");
                if(VehicleQrCode.isId(id))event("vehicleQr",DriveAutomation.obj("id",id));
            }
            return;
        }
        if(requestCode==102){
            choosingFile=false;byte[] bytes=pendingCloud;pendingCloud=null;
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&bytes!=null)try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){
                if(out==null)throw new IOException("No output stream");
                out.write(bytes);toast("서버 운행기록부를 저장했어요.");
            }catch(IOException|RuntimeException e){toast("파일을 저장하지 못했어요.");}
            return;
        }
        if (requestCode != CREATE_CSV) return;
        choosingFile = false;
        String csv = pendingCsv;
        pendingCsv = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || csv == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new IOException("No output stream");
            out.write(csv.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException err) {
            toast("CSV 파일을 저장하지 못했어요.");
            return;
        }
        toast("CSV 파일을 저장했어요.");
    }

    void cancelCloudNotifications(){
        DriveAutomation.prefs(this).edit().remove("workspace").apply();
        android.app.NotificationManager n=getSystemService(android.app.NotificationManager.class);
        if(n!=null)n.cancelAll();
    }
    static String reportMime(String name){
        if(name.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if(name.endsWith(".html"))return "text/html";
        return "text/csv";
    }
    void saveCloudReport(String name,byte[] bytes,boolean share){
        if(share){shareFile(name,bytes);return;}
        if(choosingFile)return;
        try {
            pendingCloud=bytes;choosingFile=true;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(reportMime(name)).putExtra(Intent.EXTRA_TITLE,name),102);
        }catch(RuntimeException e){choosingFile=false;pendingCloud=null;toast("파일 저장 화면을 열 수 없어요.");}
    }
    private void shareReport(String name, String csv) {shareFile(name,csv.getBytes(StandardCharsets.UTF_8));}
    private void shareFile(String name,byte[] bytes) {
        try {
            String token = UUID.randomUUID().toString();
            File folder = new File(new File(getCacheDir(), "reports"), token);
            if (!folder.mkdirs()) throw new IOException("Cache unavailable");
            try (FileOutputStream out = new FileOutputStream(new File(folder, name))) {
                out.write(bytes);
            }
            Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".reports")
                .appendPath(token).appendPath(name).build();
            Intent send = new Intent(Intent.ACTION_SEND).setType(reportMime(name))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(name, uri));
            startActivity(Intent.createChooser(send, "운행기록부 공유"));
        } catch (IOException | RuntimeException err) {
            toast("공유 화면을 열 수 없어요. CSV 저장을 이용해 주세요.");
        }
    }

    private void cleanOldReports() {
        File[] dirs = new File(getCacheDir(), "reports").listFiles();
        if (dirs == null) return;
        long deadline = System.currentTimeMillis() - 86_400_000L;
        for (File dir : dirs) {
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File file : children) {
                if (file.lastModified() < deadline) file.delete();
            }
            dir.delete();
        }
    }

    @Override public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript("typeof appHandleBack === 'function' && appHandleBack()", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("CompanionHost");
            if (webView.getParent() instanceof FrameLayout) ((FrameLayout) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
