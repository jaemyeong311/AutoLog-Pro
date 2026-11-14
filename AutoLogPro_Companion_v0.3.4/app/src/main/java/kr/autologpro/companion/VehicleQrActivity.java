package kr.autologpro.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class VehicleQrActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int CAMERA_PERMISSION=201, PICK_IMAGE=202;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private TextureView preview;
    private TextView hint;
    private Camera camera;
    private boolean active, completed, imagePending;
    private int cameraVersion;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(12),dp(18),dp(16));
        root.setBackgroundColor(Color.rgb(20,25,33));
        root.setOnApplyWindowInsetsListener((v,i)->{
            if(android.os.Build.VERSION.SDK_INT>=30){
                var b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                v.setPadding(b.left+dp(18),b.top+dp(12),b.right+dp(18),b.bottom+dp(16));
            }
            return i;
        });
        TextView title=label("차량 QR 스캔",22);
        root.addView(title,new LinearLayout.LayoutParams(-1,dp(48)));
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        preview=new TextureView(this);
        preview.setSurfaceTextureListener(this);
        preview.setContentDescription("QR 스캔용 카메라");
        frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        View box=new View(this);
        android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
        border.setColor(Color.TRANSPARENT);border.setStroke(dp(2),Color.WHITE);border.setCornerRadius(dp(12));
        box.setBackground(border);
        frame.addView(box,new FrameLayout.LayoutParams(dp(208),dp(208),Gravity.CENTER));
        root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        hint=label("차량 화면의 휴대폰 연결 QR을 비춰 주세요.",15);
        root.addView(hint,new LinearLayout.LayoutParams(-1,dp(68)));
        Button gallery=new Button(this);gallery.setText("QR 이미지 열기");
        gallery.setOnClickListener(v->pickImage());
        root.addView(gallery,new LinearLayout.LayoutParams(-1,dp(52)));
        Button close=new Button(this);close.setText("취소");
        close.setOnClickListener(v->finish());
        root.addView(close,new LinearLayout.LayoutParams(-1,dp(52)));
        setContentView(root);
        if(state==null&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);
        t.setGravity(Gravity.CENTER);return t;
    }
    @Override protected void onResume(){super.onResume();active=true;startCamera();}
    @Override protected void onPause(){active=false;stopCamera();super.onPause();}
    @Override protected void onDestroy(){stopCamera();decoder.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int r,String[] perms,int[] grants){
        super.onRequestPermissionsResult(r,perms,grants);
        if(r==CAMERA_PERMISSION){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startCamera();
            else hint.setText("카메라 권한 없이도 ‘QR 이미지 열기’를 사용할 수 있어요.");
        }
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){startCamera();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){stopCamera();return true;}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}

    private void startCamera(){
        if(!active||completed||imagePending||camera!=null||!preview.isAvailable())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            hint.setText("카메라 권한을 허용하거나 QR 이미지를 열어 주세요.");return;
        }
        try{
            Camera.CameraInfo info=new Camera.CameraInfo();
            int chosen=-1;
            for(int i=0;i<Camera.getNumberOfCameras();i++){
                Camera.getCameraInfo(i,info);
                if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){chosen=i;break;}
            }
            if(chosen<0)throw new IllegalStateException();
            camera=Camera.open(chosen);
            Camera.Parameters params=camera.getParameters();
            if(params.getSupportedFocusModes()!=null&&params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            Camera.Size best=null;
            for(Camera.Size size:params.getSupportedPreviewSizes()){
                if(size.width<=1280&&(best==null||size.width*size.height>best.width*best.height))best=size;
            }
            if(best!=null)params.setPreviewSize(best.width,best.height);
            camera.setParameters(params);
            int rotation=getWindowManager().getDefaultDisplay().getRotation();
            camera.setDisplayOrientation((info.orientation-rotation*90+360)%360);
            camera.setPreviewTexture(preview.getSurfaceTexture());
            camera.startPreview();
            hint.setText("차량 화면의 휴대폰 연결 QR을 비춰 주세요.");
            nextFrame(camera,++cameraVersion);
        }catch(Exception e){stopCamera();hint.setText("카메라를 열 수 없어요. ‘QR 이미지 열기’를 사용해 주세요.");}
    }
    private void nextFrame(Camera instance,int version){
        if(!active||completed||instance!=camera||version!=cameraVersion||imagePending)return;
        try{instance.setOneShotPreviewCallback((bytes,c)->{
            if(!active||c!=camera||version!=cameraVersion||imagePending)return;
            Camera.Size size=c.getParameters().getPreviewSize();
            byte[] frame=bytes.clone();
            decoder.execute(()->{
                String text=null;
                try{text=VehicleQrCode.frame(frame,size.width,size.height);}catch(Exception ignored){}
                String decoded=text;
                runOnUiThread(()->{
                    if(!active||c!=camera||version!=cameraVersion||imagePending||completed)return;
                    if(decoded!=null&&accept(decoded))return;
                    if(decoded!=null)hint.setText("차량 설정의 ‘휴대폰 연결 QR’을 비춰 주세요.");
                    preview.postDelayed(()->nextFrame(c,version),160);
                });
            });
        });}catch(RuntimeException e){stopCamera();hint.setText("카메라를 열 수 없어요. QR 이미지를 열어 주세요.");}
    }
    private void stopCamera(){
        cameraVersion++;
        Camera prior=camera;camera=null;
        if(prior!=null){
            try{prior.setPreviewCallback(null);prior.stopPreview();}catch(RuntimeException ignored){}
            try{prior.release();}catch(RuntimeException ignored){}
        }
    }
    private boolean accept(String text){
        String id=VehicleQrCode.parse(text);
        if(id==null)return false;
        if(completed)return true;
        completed=true;
        setResult(RESULT_OK,new Intent().putExtra("vehicle",id));
        finish();return true;
    }
    private void pickImage(){
        if(imagePending)return;
        try{
            imagePending=true;stopCamera();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),PICK_IMAGE);
        }catch(RuntimeException e){imagePending=false;hint.setText("이미지 선택을 열 수 없어요.");startCamera();}
    }
    @Override protected void onActivityResult(int r,int result,Intent data){
        super.onActivityResult(r,result,data);
        if(r!=PICK_IMAGE)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){imagePending=false;startCamera();return;}
        Uri uri=data.getData();
        imagePending=true;stopCamera();
        hint.setText("이미지의 QR을 확인하고 있어요.");
        decoder.execute(()->{
            String text=null;Bitmap bmp=null;
            try{
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
                try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,o);}
                if(o.outWidth>0&&o.outHeight>0){
                    int sample=1;
                    while(Math.max(o.outWidth,o.outHeight)/sample>2000)sample*=2;
                    o.inJustDecodeBounds=false;o.inSampleSize=sample;
                    try(InputStream in=getContentResolver().openInputStream(uri)){bmp=BitmapFactory.decodeStream(in,null,o);}
                    if(bmp!=null&&bmp.getWidth()<=2200&&bmp.getHeight()<=2200){
                        int w=bmp.getWidth(),h=bmp.getHeight();int[] pixels=new int[w*h];
                        bmp.getPixels(pixels,0,w,0,0,w,h);
                        text=VehicleQrCode.image(pixels,w,h);
                    }
                }
            }catch(Exception ignored){}finally{if(bmp!=null)bmp.recycle();}
            String decoded=text;
            runOnUiThread(()->{
                imagePending=false;
                if(isFinishing()||isDestroyed())return;
                if(decoded!=null&&accept(decoded))return;
                startCamera();hint.setText("차량 연결 QR을 읽지 못했어요. QR이 선명하게 보이는 이미지를 선택해 주세요.");
            });
        });
    }
}
