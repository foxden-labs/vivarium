package io.github.foxdenlabs.vivarium;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.util.Collections;
import org.json.JSONObject;

/*
 * Runs one tool with nobody looking: a hidden WebView on the tool's own
 * origin, so it sees its own storage and nothing else. The shim notices it is
 * alone and talks over VivariumWake, which only exists in this WebView and
 * only for this tool's origin. Every request is checked against grants.json
 * here, because there is no shelf to ask.
 */
final class WakeRunner {

    interface Done { void done(boolean ok); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long LIMIT = 120_000;

    private WakeRunner() {}

    @SuppressLint("SetJavaScriptEnabled")
    static void run(Context ctx, String id, Done done) {
        final Context app = ctx.getApplicationContext();
        if (!new File(app.getFilesDir(), "tools/" + id + "/tool.html").isFile() ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            done.done(false);
            return;
        }
        final JSONObject grant = WakeJob.readJson(app, "grants.json").optJSONObject(id);
        if (grant == null || !grant.optBoolean("wake", false)) { done.done(false); return; }
        final String origin = "https://" + id + ToolServer.SUFFIX;

        final WebView wv;
        try { wv = new WebView(app); } catch (Throwable t) { done.done(false); return; }
        final boolean[] over = { false };
        final Runnable[] killer = new Runnable[1];
        final Done end = (ok) -> {
            if (over[0]) return;
            over[0] = true;
            MAIN.removeCallbacks(killer[0]);
            MAIN.postDelayed(() -> {
                try {
                    WebViewCompat.removeWebMessageListener(wv, "VivariumWake");
                    wv.stopLoading();
                    wv.destroy();
                } catch (Exception ignored) {}
                done.done(ok);
            }, 700);
        };
        killer[0] = () -> end.done(false);
        MAIN.postDelayed(killer[0], LIMIT);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return ToolServer.serve(app, req.getUrl());
            }

            // Stays on its own page. Nowhere to navigate to with nobody watching.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !id.equals(ToolServer.toolId(req.getUrl()));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                over[0] = true;
                MAIN.removeCallbacks(killer[0]);
                try { view.destroy(); } catch (Exception ignored) {}
                done.done(false);
                return true;
            }
        });

        try {
            WebViewCompat.addWebMessageListener(wv, "VivariumWake", Collections.singleton(origin),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (!isMainFrame || !origin.equals(sourceOrigin.toString().replaceAll("/$", ""))) return;
                    String data = message.getData();
                    if (data == null) return;
                    JSONObject m;
                    try { m = new JSONObject(data); } catch (Exception e) { return; }
                    if ("done".equals(m.optString("op"))) { end.done(true); return; }
                    handle(app, id, grant, m, reply);
                });
        } catch (Throwable t) {
            end.done(false);
            return;
        }
        wv.onResume();
        wv.loadUrl(origin + "/");
    }

    private static void handle(Context app, String id, JSONObject grant, JSONObject m, JavaScriptReplyProxy reply) {
        final int seq = m.optInt("seq", 0);
        final String op = m.optString("op", "");
        final JSONObject args = m.optJSONObject("args") != null ? m.optJSONObject("args") : new JSONObject();
        final Abilities.Done answer = new Abilities.Done() {
            @Override public void ok(JSONObject value) { send(reply, seq, true, value, null, null); }
            @Override public void fail(String code, String message) { send(reply, seq, false, null, code, message); }
        };
        Abilities.Allow allow = Abilities.Allow.from(grant.opt("net"));
        try {
            switch (op) {
                case "info": {
                    JSONObject v = new JSONObject();
                    v.put("perms", perms(app, grant));
                    v.put("network", Abilities.network(app));
                    v.put("render", Abilities.canRender());
                    answer.ok(v);
                    break;
                }
                case "network":
                    answer.ok(Abilities.network(app));
                    break;
                case "fetch":
                case "render": {
                    String host = Uri.parse(args.optString("url", "")).getHost();
                    if (!allow.covers(host)) { answer.fail("not-allowed", "not allowed to read " + host); break; }
                    if (op.equals("fetch")) Abilities.fetch(app, args, allow, answer);
                    else Abilities.render(app, args, allow, answer);
                    break;
                }
                case "notify":
                    if (!grant.optBoolean("notify", false)) { answer.fail("not-allowed", "notifications not allowed"); break; }
                    Notifier.post(app, id, args);
                    answer.ok(new JSONObject());
                    break;
                case "notify-close":
                    Notifier.cancel(app, id, args.optString("tag", ""));
                    answer.ok(new JSONObject());
                    break;
                case "wake-get":
                    answer.ok(wakeEntry(app, id));
                    break;
                case "wake-every":
                    answer.ok(WakeJob.set(app, id, args.optInt("minutes", 60)));
                    break;
                case "wake-stop":
                    WakeJob.clear(app, id);
                    answer.ok(new JSONObject());
                    break;
                case "request": {
                    JSONObject v = new JSONObject();
                    v.put("state", perms(app, grant).optString(args.optString("name", ""), "denied"));
                    answer.ok(v);
                    break;
                }
                default:
                    answer.fail("unsupported", op + " is not available in the background");
            }
        } catch (Abilities.Failure f) {
            answer.fail(f.code, f.getMessage());
        } catch (Exception e) {
            answer.fail("failed", e.getClass().getSimpleName());
        }
    }

    static JSONObject perms(Context app, JSONObject grant) {
        JSONObject p = new JSONObject();
        try {
            Object net = grant.opt("net");
            p.put("sites", "any".equals(net) ? "granted" : "prompt");
            p.put("notifications", grant.optBoolean("notify", false) && "granted".equals(Notifier.systemState(app)) ? "granted" : "denied");
            p.put("background", grant.optBoolean("wake", false) ? "granted" : "denied");
        } catch (Exception ignored) {}
        return p;
    }

    static JSONObject wakeEntry(Context app, String id) {
        JSONObject e = WakeJob.all(app).optJSONObject(id);
        return e != null ? e : new JSONObject();
    }

    private static void send(JavaScriptReplyProxy reply, int seq, boolean ok, JSONObject value, String code, String msg) {
        final JSONObject r = new JSONObject();
        try {
            r.put("seq", seq);
            r.put("ok", ok);
            if (value != null) r.put("value", value);
            if (code != null) r.put("error", code);
            if (msg != null) r.put("message", msg);
        } catch (Exception ignored) {}
        MAIN.post(() -> { try { reply.postMessage(r.toString()); } catch (Exception ignored) {} });
    }
}
