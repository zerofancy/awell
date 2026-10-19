package top.ntutn.awall;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Real SAF fixture hosted under the test APK's UID. Never accesses user storage. */
public class FixtureDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID,
                Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE};
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = null;
            if (column.equals(Root.COLUMN_ROOT_ID) || column.equals(Root.COLUMN_DOCUMENT_ID)) value = "root";
            if (column.equals(Root.COLUMN_TITLE)) value = "Test photos";
            row.add(column, value);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        return documents(projection, new String[]{id});
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order)
            throws FileNotFoundException {
        String[] children;
        switch (parent) {
            case "root": children = new String[]{"root/one.jpg", "root/child", "root/video.mp4"}; break;
            case "root/child": children = new String[]{"root/child/two.png", "root/child/deep"}; break;
            case "root/child/deep": children = new String[]{"root/child/deep/three.webp"}; break;
            case "empty": children = new String[0]; break;
            default: throw new FileNotFoundException(parent);
        }
        return documents(projection, children);
    }

    private Cursor documents(String[] projection, String[] ids) throws FileNotFoundException {
        String[] columns = projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS};
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String id : ids) {
            if (id.equals("missing")) throw new FileNotFoundException(id);
            String mime = Document.MIME_TYPE_DIR;
            if (id.endsWith(".jpg")) mime = "image/jpeg";
            if (id.endsWith(".png")) mime = "image/png";
            if (id.endsWith(".webp")) mime = "image/webp";
            if (id.endsWith(".mp4")) mime = "video/mp4";
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                Object value = null;
                switch (column) {
                    case Document.COLUMN_DOCUMENT_ID: value = id; break;
                    case Document.COLUMN_DISPLAY_NAME: value = id.substring(id.lastIndexOf('/') + 1); break;
                    case Document.COLUMN_MIME_TYPE: value = mime; break;
                    case Document.COLUMN_FLAGS: value = 0; break;
                }
                row.add(column, value);
            }
        }
        return cursor;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        return child.startsWith(parent + "/");
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!mode.equals("r")) throw new FileNotFoundException("Read only");
        String extension = id.substring(id.lastIndexOf('.') + 1);
        File file = new File(getContext().getCacheDir(), "fixture." + extension);
        Bitmap.CompressFormat format = extension.equals("jpg") ? Bitmap.CompressFormat.JPEG
                : extension.equals("webp") ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.PNG;
        if (!file.exists()) {
            Bitmap bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.GREEN);
            try (FileOutputStream stream = new FileOutputStream(file)) {
                bitmap.compress(format, 100, stream);
            } catch (IOException error) {
                throw new FileNotFoundException(error.getMessage());
            } finally { bitmap.recycle(); }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
