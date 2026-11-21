package io.github.foxdenlabs.vivarium;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/*
 * "Open with Vivarium" and "Share to Vivarium" for .html files.
 *
 * The file behind the intent is copied into files/inbox/ right away, while
 * this app still holds the read grant (it expires with the intent). The
 * launcher sweeps the inbox on start, on resume and on appUrlOpen, imports
 * whatever it finds and deletes it. Nothing here parses or trusts the file.
 */
final class Inbox {

    private static final String DONE = "vivarium.inbox.done";
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private Inbox() {}

    static void accept(Context ctx, Intent intent) {
        if (intent == null || intent.getBooleanExtra(DONE, false)) return;
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action)) {
            Uri u = intent.getData();
            if (u != null && isFileish(u)) uris.add(u);
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = streamExtra(intent);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = streamListExtra(intent);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        intent.putExtra(DONE, true);

        File dir = new File(ctx.getFilesDir(), "inbox");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        long stamp = System.currentTimeMillis();
        int i = 0;
        for (Uri u : uris) {
            if (u == null || !isFileish(u)) continue;
            String name = displayName(ctx, u);
            File out = new File(dir, stamp + "-" + (i++) + "-" + name);
            if (!copy(ctx.getContentResolver(), u, out)) out.delete();
        }
    }

    private static boolean isFileish(Uri u) {
        String s = u.getScheme();
        return "content".equals(s) || "file".equals(s);
    }

    @SuppressWarnings("deprecation")
    private static Uri streamExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> streamListExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    }

    private static String displayName(Context ctx, Uri u) {
        String name = null;
        if ("content".equals(u.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {}
        }
        if (name == null) name = u.getLastPathSegment();
        if (name == null) name = "tool.html";
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.length() > 80) name = name.substring(name.length() - 80);
        if (name.isEmpty()) name = "tool.html";
        return name;
    }

    private static boolean copy(ContentResolver cr, Uri u, File out) {
        try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
            if (in == null) return false;
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) return false;
                os.write(buf, 0, n);
            }
            return total > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
