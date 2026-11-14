package kr.autologpro.companion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.regex.Pattern;

public final class ReportProvider extends ContentProvider {
    private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    @Override public boolean onCreate() { return true; }

    private File report(Uri uri) throws FileNotFoundException {
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 2 || !TOKEN.matcher(parts.get(0)).matches() ||
            !MainActivity.FILE_NAME.matcher(parts.get(1)).matches() || getContext() == null) {
            throw new FileNotFoundException("Unknown report");
        }
        File path = new File(new File(new File(getContext().getCacheDir(), "reports"), parts.get(0)), parts.get(1));
        if (!path.isFile()) throw new FileNotFoundException("Report not available");
        return path;
    }

    @Override public String getType(Uri uri) { return MainActivity.reportMime(uri.getLastPathSegment()==null?"":uri.getLastPathSegment()); }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only report");
        return ParcelFileDescriptor.open(report(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        try {
            File file = report(uri);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; ++i) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            result.addRow(values);
        } catch (FileNotFoundException ignored) {}
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
