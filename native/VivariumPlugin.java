package io.github.foxdenlabs.vivarium;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
import java.util.Collections;

/*
 * The few things the launcher can't do from JavaScript:
 *   pinShortcut      a home screen icon that opens straight into one tool
 *   disableShortcut  greys out that icon when the tool is removed
 *   setKeepAwake     screen stays on (a tool asked for a wake lock)
 *   setImmersive     hide the system bars while a tool is open
 *   openExternal     a link a tool wants opened in the real browser
 */
@CapacitorPlugin(name = "Vivarium")
public class VivariumPlugin extends Plugin {

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
