package kr.autologpro.companion;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.regex.Pattern;

final class VehicleQrCode {
    private static final String PREFIX="autologpro://vehicle/v1/";
    private static final Pattern ID=Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    static boolean isId(String s){return s!=null&&ID.matcher(s).matches();}
    static String parse(String text){
        if(text==null||text.length()!=PREFIX.length()+36||!text.startsWith(PREFIX))return null;
        String id=text.substring(PREFIX.length());
        return isId(id)?id:null;
    }
    static String frame(byte[] data,int width,int height) throws com.google.zxing.ReaderException {
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
            new PlanarYUVLuminanceSource(data,width,height,0,0,width,height,false)))).getText();
    }
    static String image(int[] pixels,int width,int height) throws com.google.zxing.ReaderException {
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
            new RGBLuminanceSource(width,height,pixels)))).getText();
    }
}
