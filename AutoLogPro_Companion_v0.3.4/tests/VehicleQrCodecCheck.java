package kr.autologpro.companion;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
public class VehicleQrCodecCheck {
 public static void main(String[] args) throws Exception {
  String id="12345678-1234-1234-1234-123456789abc";
  String raw="autologpro://vehicle/v1/"+id;
  var m=new QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,384,384);
  int[] pixels=new int[384*384];
  byte[] yuv=new byte[384*384];
  for(int j=0;j<pixels.length;j++){boolean dark=m.get(j%384,j/384);pixels[j]=dark?0xff000000:0xffffffff;yuv[j]=(byte)(dark?0:255);}
  if(!id.equals(VehicleQrCode.parse(VehicleQrCode.image(pixels,384,384))))throw new AssertionError("image");
  if(!id.equals(VehicleQrCode.parse(VehicleQrCode.frame(yuv,384,384))))throw new AssertionError("camera frame");
  for(String bad:new String[]{null,"https://example.com",raw+"?key=bad"," "+raw,raw+"/extra","autologpro://report/v1/"+id})
   if(VehicleQrCode.parse(bad)!=null)throw new AssertionError("invalid QR");
  System.out.println("PASS: generated vehicle QR decoded from RGB image and camera luminance; invalid formats rejected.");
 }
}
