package io.github.foxdenlabs.vivarium;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Notifications on behalf of a tool. Every tool gets its own channel, named
 * after it, so Android's settings can silence one tool without the rest.
 *
 * Tapping one opens the link it carries in the browser, or else opens the
 * tool, which hears about the tap through vivarium://tool/<id>?nc=...
 */
final class Notifier {

    private static final String PREFIX = "tool-";

    private Notifier() {}

    /** "granted", "denied" (Android or the channel says no), or "prompt" (never asked on 13+). */
    static String systemState(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "prompt";
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled() ? "granted" : "denied";
    }

    static void post(Context ctx, String id, JSONObject n) throws Abilities.Failure {
        if (!"granted".equals(systemState(ctx))) throw new Abilities.Failure("blocked", "notifications are off for Vivarium");
        JSONObject meta = toolMeta(ctx, id);
        String name = meta.optString("name", id);
        String channel = ensureChannel(ctx, id, name);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = ctx.getSystemService(NotificationManager.class).getNotificationChannel(channel);
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE)
                throw new Abilities.Failure("blocked", "notifications from " + name + " are off in Android settings");
        }

        String title = cut(n.optString("title", name), 120);
        String body = cut(n.optString("body", ""), 600);
        String tag = cut(n.optString("tag", ""), 64);
        String key = PREFIX + id + ":" + (tag.isEmpty() ? "~" + System.nanoTime() : tag);
        int nid = key.hashCode();
        String data = n.has("data") ? cut(String.valueOf(n.opt("data")), 2000) : "";

        int color = parseColor(meta.optString("accent", ""), 0xfff97316);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(IconCompat.createWithBitmap(mark()))
            .setColor(color)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(name)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setOnlyAlertOnce(false)
            .setSilent(n.optBoolean("silent", false));

        Bitmap large = image(n.optString("icon", ""));
        if (large == null) large = toolIcon(ctx, id);
        if (large != null) b.setLargeIcon(large);

        String url = web(n.optString("url", ""));
        b.setContentIntent(url != null ? openUrl(ctx, url, nid) : openTool(ctx, id, tag, data, "", nid));

        JSONArray actions = n.optJSONArray("actions");
        if (actions != null) {
            for (int i = 0; i < Math.min(2, actions.length()); i++) {
                JSONObject a = actions.optJSONObject(i);
                if (a == null) continue;
                String label = cut(a.optString("title", ""), 30);
                if (label.isEmpty()) continue;
                String au = web(a.optString("url", ""));
                PendingIntent pi = au != null ? openUrl(ctx, au, nid * 31 + i + 1)
                    : openTool(ctx, id, tag, data, cut(a.optString("action", ""), 40), nid * 31 + i + 1);
                b.addAction(0, label, pi);
            }
        }

        try {
            NotificationManagerCompat.from(ctx).notify(key, nid, b.build());
        } catch (SecurityException e) {
            throw new Abilities.Failure("blocked", "notifications are off for Vivarium");
        }
    }

    static void cancel(Context ctx, String id, String tag) {
        if (tag == null || tag.isEmpty()) return;
        String key = PREFIX + id + ":" + tag;
        NotificationManagerCompat.from(ctx).cancel(key, key.hashCode());
    }

    /** The tool is gone: its notifications and its channel go too. */
    static void forget(Context ctx, String id) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        try {
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                String t = s.getTag();
                if (t != null && t.startsWith(PREFIX + id + ":")) nm.cancel(t, s.getId());
            }
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 26) {
            try { nm.deleteNotificationChannel(PREFIX + id); } catch (Exception ignored) {}
        }
    }

    private static String ensureChannel(Context ctx, String id, String name) {
        String cid = PREFIX + id;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            NotificationChannel ch = nm.getNotificationChannel(cid);
            if (ch == null) {
                ch = new NotificationChannel(cid, name, NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Notifications from " + name);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            } else if (!name.contentEquals(ch.getName())) {
                ch.setName(name);
                nm.createNotificationChannel(ch);
            }
        }
        return cid;
    }

    private static PendingIntent openUrl(Context ctx, String url, int req) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent openTool(Context ctx, String id, String tag, String data, String action, int req) {
        Uri.Builder u = new Uri.Builder().scheme("vivarium").authority("tool").appendPath(id)
            .appendQueryParameter("nc", "1");
        if (!tag.isEmpty()) u.appendQueryParameter("tag", tag);
        if (!data.isEmpty()) u.appendQueryParameter("data", data);
        if (!action.isEmpty()) u.appendQueryParameter("action", action);
        Intent i = new Intent(Intent.ACTION_VIEW, u.build());
        i.setClass(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String web(String url) {
        if (url == null || url.isEmpty()) return null;
        Uri u = Uri.parse(url);
        String s = u.getScheme();
        return ("https".equals(s) || "http".equals(s)) && u.getHost() != null ? url : null;
    }

    private static Bitmap image(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.substring(0, comma).contains(";base64") || dataUrl.length() > 1_000_000) return null;
        try {
            byte[] raw = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }

    // Written by the shelf (the same picture a pinned shortcut gets).
    private static Bitmap toolIcon(Context ctx, String id) {
        File f = new File(ctx.getFilesDir(), "icons/" + id + ".png");
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /** Name and accent from the shelf's library.json, for when only an id is known. */
    static JSONObject toolMeta(Context ctx, String id) {
        try (FileInputStream in = new FileInputStream(new File(ctx.getFilesDir(), "library.json"))) {
            JSONObject lib = new JSONObject(new String(ToolServer.readAll(in), StandardCharsets.UTF_8));
            JSONArray tools = lib.optJSONArray("tools");
            for (int i = 0; tools != null && i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t != null && id.equals(t.optString("id"))) return t;
            }
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    private static int parseColor(String hex, int fallback) {
        try { return hex.startsWith("#") ? Color.parseColor(hex) : fallback; } catch (Exception e) { return fallback; }
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    // The rising chevrons, as a status bar icon (Android only uses the alpha).
    private static final String[] MARK = {
        ".....O.....",
        "....O.O....",
        "...O...O...",
        "..O.....O..",
        ".O...C...O.",
        "O...C.C...O",
        "...C...C...",
        "..C.....C..",
        ".....G.....",
        "....G.G...."
    };
    private static Bitmap markBmp;

    private static synchronized Bitmap mark() {
        if (markBmp != null) return markBmp;
        int cell = 8, w = 11, h = 10, pad = cell;
        Bitmap bmp = Bitmap.createBitmap(w * cell + pad * 2, h * cell + pad * 2 + cell * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        int oy = pad + cell;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (MARK[y].charAt(x) == '.') continue;
                c.drawRect(pad + x * cell + 1, oy + y * cell + 1, pad + (x + 1) * cell - 1, oy + (y + 1) * cell - 1, p);
            }
        }
        markBmp = bmp;
        return bmp;
    }
}
