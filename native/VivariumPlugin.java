package io.github.foxdenlabs.vivarium;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.net.CookieHandler;
import java.util.Collections;
import org.json.JSONObject;

/*
 * The few things the launcher can't do from JavaScript:
 *   pinShortcut      a home screen icon that opens straight into one tool
 *   disableShortcut  greys out that icon when the tool is removed
 *   setKeepAwake     screen stays on (a tool asked for a wake lock)
 *   setImmersive     hide the system bars while a tool is open
 *   openExternal     a link a tool wants opened in the real browser
 *
 * And the abilities a tool can be allowed, which the shelf asks for on the
 * tool's behalf after checking its grants (tools themselves can't reach
 * any of this):
 *   fetchPage, renderPage                 read other sites (Abilities)
 *   postNotification, closeNotification   notifications (Notifier)
 *   setWake, clearWake, getWakes, wakeNow  background wake-ups (WakeJob)
 */
@CapacitorPlugin(
    name = "Vivarium",
    permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications") }
)
public class VivariumPlugin extends Plugin {

    private static volatile VivariumPlugin instance;
    private static volatile String openTool;

    @Override
    public void load() {
        instance = this;
        // Capacitor makes the WebView's cookie jar the process default; the
        // only Java networking in here is fetchPage, which must stay anonymous.
        CookieHandler.setDefault(null);
        WakeJob.restore(getContext());
    }

    /** Called by WakeJob: if the tool is open on screen right now, wake that one instead. */
    static boolean deliverWake(String id) {
        VivariumPlugin p = instance;
        if (p == null || !MainActivity.resumed || !id.equals(openTool)) return false;
        JSObject d = new JSObject();
        d.put("id", id);
        try { d.put("network", Abilities.network(p.getContext())); } catch (Exception ignored) {}
        p.notifyListeners("wake", d);
        return true;
    }

    private static JSObject js(JSONObject o) {
        try { return JSObject.fromJSONObject(o); } catch (Exception e) { return new JSObject(); }
    }

    private Abilities.Done reply(PluginCall call) {
        return new Abilities.Done() {
            @Override public void ok(JSONObject value) { call.resolve(js(value)); }
            @Override public void fail(String code, String message) { call.reject(message, code); }
        };
    }

    private static boolean okId(String id) {
        return id != null && id.matches("[a-z0-9-]{1,63}");
    }

    /* ----- reading other sites ----- */

    @PluginMethod
    public void fetchPage(PluginCall call) {
        Abilities.fetch(getContext(), call.getData(), Abilities.Allow.from(call.getData().opt("allow")), reply(call));
    }

    @PluginMethod
    public void renderPage(PluginCall call) {
        Abilities.render(getContext(), call.getData(), Abilities.Allow.from(call.getData().opt("allow")), reply(call));
    }

    @PluginMethod
    public void network(PluginCall call) {
        JSObject r = js(Abilities.network(getContext()));
        r.put("render", Abilities.canRender());
        call.resolve(r);
    }

    /* ----- notifications ----- */

    @PluginMethod
    public void notifyState(PluginCall call) {
        JSObject r = new JSObject();
        r.put("state", Notifier.systemState(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotify(PluginCall call) {
        String st = Notifier.systemState(getContext());
        if (!"prompt".equals(st) || Build.VERSION.SDK_INT < 33) {
            JSObject r = new JSObject();
            r.put("state", st);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notifications", call, "notifyAsked");
    }

    @PermissionCallback
    private void notifyAsked(PluginCall call) {
        String st = Notifier.systemState(getContext());
        JSObject r = new JSObject();
        r.put("state", "prompt".equals(st) ? "denied" : st);
        call.resolve(r);
    }

    @PluginMethod
    public void postNotification(PluginCall call) {
        String id = call.getString("id");
        JSObject n = call.getObject("n", new JSObject());
        if (!okId(id)) { call.reject("bad id", "bad-id"); return; }
        try {
            Notifier.post(getContext(), id, n);
            call.resolve();
        } catch (Abilities.Failure f) {
            call.reject(f.getMessage(), f.code);
        } catch (Exception e) {
            call.reject("could not notify", "failed");
        }
    }

    @PluginMethod
    public void closeNotification(PluginCall call) {
        String id = call.getString("id");
        if (okId(id)) Notifier.cancel(getContext(), id, call.getString("tag", ""));
        call.resolve();
    }

    /* ----- background wake-ups ----- */

    @PluginMethod
    public void setWake(PluginCall call) {
        String id = call.getString("id");
        if (!okId(id)) { call.reject("bad id", "bad-id"); return; }
        call.resolve(js(WakeJob.set(getContext(), id, call.getInt("every", 60))));
    }

    @PluginMethod
    public void clearWake(PluginCall call) {
        String id = call.getString("id");
        if (okId(id)) WakeJob.clear(getContext(), id);
        call.resolve();
    }

    @PluginMethod
    public void getWakes(PluginCall call) {
        JSObject r = new JSObject();
        r.put("wakes", js(WakeJob.all(getContext())));
        call.resolve(r);
    }

    @PluginMethod
    public void wakeNow(PluginCall call) {
        String id = call.getString("id");
        if (!okId(id)) { call.reject("bad id", "bad-id"); return; }
        WakeJob.runNow(getContext(), id);
        call.resolve();
    }

    @PluginMethod
    public void setOpenTool(PluginCall call) {
        String id = call.getString("id");
        openTool = okId(id) ? id : null;
        call.resolve();
    }

    /** The tool left the shelf: no more wake-ups, notifications or channel. */
    @PluginMethod
    public void forgetTool(PluginCall call) {
        String id = call.getString("id");
        if (okId(id)) {
            WakeJob.clear(getContext(), id);
            Notifier.forget(getContext(), id);
            if (id.equals(openTool)) openTool = null;
        }
        call.resolve();
    }

    /* ----- battery: Samsung and friends stop background work for "optimized" apps ----- */

    @PluginMethod
    public void batteryState(PluginCall call) {
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        JSObject r = new JSObject();
        r.put("unrestricted", pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName()));
        call.resolve(r);
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        Context ctx = getContext();
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void pinShortcut(PluginCall call) {
        Context ctx = getContext();
        String id = call.getString("id");
        String name = call.getString("name", "Tool");
        String icon = call.getString("icon");
        if (id == null || !id.matches("[a-z0-9-]{1,63}")) {
            call.reject("bad id");
            return;
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            call.reject("unsupported");
            return;
        }

        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("vivarium://tool/" + id));
        open.setClass(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        IconCompat ic = null;
        if (icon != null) {
            try {
                byte[] png = Base64.decode(icon, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp != null) ic = IconCompat.createWithAdaptiveBitmap(bmp);
            } catch (Exception ignored) {}
        }
        if (ic == null) ic = IconCompat.createWithResource(ctx, ctx.getApplicationInfo().icon);

        String label = name.length() > 24 ? name.substring(0, 24) : name;
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(ctx, "tool-" + id)
            .setShortLabel(label)
            .setLongLabel(name)
            .setIcon(ic)
            .setIntent(open)
            .build();

        boolean asked;
        try {
            // Already pinned: refresh its label and icon in place.
            ShortcutManagerCompat.updateShortcuts(ctx, Collections.singletonList(info));
            asked = ShortcutManagerCompat.requestPinShortcut(ctx, info, null);
        } catch (Exception e) {
            call.reject("failed");
            return;
        }
        JSObject ret = new JSObject();
        ret.put("asked", asked);
        call.resolve(ret);
    }

    @PluginMethod
    public void disableShortcut(PluginCall call) {
        String id = call.getString("id");
        if (id != null) {
            try {
                ShortcutManagerCompat.disableShortcuts(getContext(),
                    Collections.singletonList("tool-" + id), "This tool was removed from Vivarium");
            } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void setKeepAwake(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        final Activity a = getActivity();
        a.runOnUiThread(() -> {
            if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    @PluginMethod
    public void setImmersive(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        final Activity a = getActivity();
        a.runOnUiThread(() -> {
            Window w = a.getWindow();
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
            if (on) {
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsetsCompat.Type.systemBars());
            } else {
                c.show(WindowInsetsCompat.Type.systemBars());
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void openExternal(PluginCall call) {
        String url = call.getString("url", "");
        Uri u = Uri.parse(url);
        String s = u.getScheme();
        if (!"http".equals(s) && !"https".equals(s) && !"mailto".equals(s)) {
            call.reject("scheme");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("nothing can open that");
        }
    }
}
