package io.github.foxdenlabs.vivarium;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/*
 * What a tool may ask for once it has been allowed to read other sites.
 *
 *   fetch   one anonymous GET: no cookies, no cache, no logins. The page as a
 *           stranger would get it.
 *   render  the page loaded in a hidden WebView on its own profile, so shop
 *           cookies never sit next to tool storage. Images and fonts are not
 *           downloaded. Returns the HTML after the page's own scripts ran.
 *
 * Neither ever talks to this device or the local network.
 */
final class Abilities {

    static final class Failure extends Exception {
        final String code;
        Failure(String code, String msg) { super(msg); this.code = code; }
    }

    interface Done {
        void ok(JSONObject value);
        void fail(String code, String message);
    }

    /** Which hosts a tool may read: "any", or a list where each entry covers its subdomains too. */
    static final class Allow {
        final boolean any;
        final String[] hosts;
        Allow(boolean any, String[] hosts) { this.any = any; this.hosts = hosts == null ? new String[0] : hosts; }

        static Allow from(Object o) {
            if ("any".equals(o)) return new Allow(true, null);
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                String[] h = new String[a.length()];
                for (int i = 0; i < a.length(); i++) h[i] = a.optString(i, "").toLowerCase(Locale.ROOT);
                return new Allow(false, h);
            }
            return new Allow(false, null);
        }

        boolean covers(String host) {
            if (host == null) return false;
            if (any) return true;
            host = host.toLowerCase(Locale.ROOT);
            for (String g : hosts) {
                if (g.isEmpty()) continue;
                if (host.equals(g) || host.endsWith("." + g)) return true;
            }
            return false;
        }
    }

    private static final ExecutorService NET = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_REDIRECTS = 6;
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");
    private static final Pattern SKIP = Pattern.compile(
        "\\.(?:png|jpe?g|gif|webp|avif|svg|ico|bmp|woff2?|ttf|otf|eot|mp4|webm|m3u8|mp3|ogg|wav)(?:$|[?#])", Pattern.CASE_INSENSITIVE);

    private Abilities() {}

    /* ═════════════════ shared ═════════════════ */

    private static String ua;

    /** Chrome's own reduced user agent, without the WebView and device markers. */
    static synchronized String userAgent(Context ctx) {
        if (ua != null) return ua;
        String major = "130";
        try {
            Matcher m = CHROME.matcher(WebSettings.getDefaultUserAgent(ctx));
            if (m.find()) major = m.group(1);
        } catch (Exception ignored) {}
        ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
            major + ".0.0.0 Mobile Safari/537.36";
        return ua;
    }

    static String acceptLanguage() {
        String tag = Locale.getDefault().toLanguageTag();
        String lang = Locale.getDefault().getLanguage();
        StringBuilder b = new StringBuilder(tag);
        if (!lang.isEmpty() && !lang.equals(tag)) b.append(',').append(lang).append(";q=0.9");
        if (!"en".equals(lang)) b.append(",en;q=0.7");
        return b.toString();
    }

    static JSONObject network(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            boolean online = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            o.put("online", online);
            o.put("metered", cm.isActiveNetworkMetered());
            o.put("wifi", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        } catch (Exception e) {
            try { o.put("online", true); o.put("metered", true); o.put("wifi", false); } catch (Exception ignored) {}
        }
        return o;
    }

    static boolean isLocalName(String host) {
        if (host == null || host.isEmpty()) return true;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa");
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() ||
            a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0) return true;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;     // carrier NAT
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return true;
            if (b0 >= 224) return true;
        } else if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;                  // unique local
        }
        return false;
    }

    /** A literal IP in the URL that points inside this device or network. */
    private static boolean isPrivateLiteral(String host) {
        if (host == null) return true;
        String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (!h.matches("[0-9.]+|[0-9a-fA-F:]+:[0-9a-fA-F:.]*")) return false;
        try { return isPrivate(InetAddress.getByName(h)); } catch (Exception e) { return true; }
    }

    private static void checkTarget(URL u, Allow allow) throws Failure {
        String p = u.getProtocol();
        if (!"https".equals(p) && !"http".equals(p)) throw new Failure("bad-url", "only http and https");
        String host = u.getHost();
        if (isLocalName(host)) throw new Failure("blocked-host", "local addresses are off limits");
        if (!allow.covers(host)) throw new Failure("not-allowed", host + " is not allowed for this tool");
        InetAddress[] all;
        try { all = InetAddress.getAllByName(host); } catch (Exception e) { throw new Failure("network", "could not find " + host); }
        for (InetAddress a : all) if (isPrivate(a)) throw new Failure("blocked-host", "local addresses are off limits");
    }

    /* ═════════════════ fetch ═════════════════ */

    static void fetch(Context ctx, JSONObject args, Allow allow, Done done) {
        final Context app = ctx.getApplicationContext();
        NET.execute(() -> {
            try {
                done.ok(fetchNow(app, args, allow));
            } catch (Failure f) {
                done.fail(f.code, f.getMessage());
            } catch (Exception e) {
                done.fail("network", e.getClass().getSimpleName());
            }
        });
    }

    private static JSONObject fetchNow(Context ctx, JSONObject args, Allow allow) throws Exception {
        // Capacitor installs the WebView's cookie jar as the process default.
        // These requests must never carry anyone's cookies.
        if (CookieHandler.getDefault() != null) CookieHandler.setDefault(null);

        String raw = args.optString("url", "");
        boolean b64 = "base64".equals(args.optString("as", "text"));
        int max = clamp(args.optInt("maxBytes", b64 ? 2 << 20 : 4 << 20), 1024, 8 << 20);
        int timeout = clamp(args.optInt("timeout", 20000), 3000, 45000);
        JSONObject hdrs = args.optJSONObject("headers");
        long t0 = System.currentTimeMillis();

        URL u;
        try { u = new URL(raw); } catch (Exception e) { throw new Failure("bad-url", "not a URL"); }
        HttpURLConnection c = null;
        int status = 0;
        for (int hop = 0; ; hop++) {
            checkTarget(u, allow);
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(Math.min(timeout, 15000));
            c.setReadTimeout(timeout);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", userAgent(ctx));
            c.setRequestProperty("Accept", hdrs != null && hdrs.has("Accept") ? hdrs.optString("Accept") :
                (b64 ? "image/avif,image/webp,image/*,*/*;q=0.8" :
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
            c.setRequestProperty("Accept-Language", hdrs != null && hdrs.has("Accept-Language") ?
                hdrs.optString("Accept-Language") : acceptLanguage());
            status = c.getResponseCode();
            if (status >= 300 && status < 400 && status != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Failure("network", "redirect without a target");
                if (hop >= MAX_REDIRECTS) throw new Failure("network", "too many redirects");
                u = new URL(u, loc);
                continue;
            }
            break;
        }

        try {
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = new byte[0];
            boolean cut = false;
            if (in != null) {
                try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = s.read(buf)) != -1) {
                        if (out.size() + n > max) { out.write(buf, 0, max - out.size()); cut = true; break; }
                        out.write(buf, 0, n);
                    }
                    body = out.toByteArray();
                }
            }
            String type = c.getContentType();
            JSONObject r = new JSONObject();
            r.put("ok", status >= 200 && status < 300);
            r.put("status", status);
            r.put("url", u.toString());
            r.put("type", type == null ? "" : type);
            r.put("bytes", body.length);
            r.put("cut", cut);
            r.put("ms", System.currentTimeMillis() - t0);
            String lm = c.getHeaderField("Last-Modified");
            if (lm != null) r.put("modified", lm);
            if (b64) r.put("base64", Base64.encodeToString(body, Base64.NO_WRAP));
            else r.put("text", decode(body, type));
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String decode(byte[] body, String type) {
        Charset cs = null;
        Matcher m = type == null ? null : CHARSET.matcher(type);
        if (m != null && m.find()) cs = charset(m.group(1));
        if (cs == null) {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher mm = META_CHARSET.matcher(head);
            if (mm.find()) cs = charset(mm.group(1));
        }
        return new String(body, cs == null ? StandardCharsets.UTF_8 : cs);
    }

    private static Charset charset(String name) {
        try { return Charset.forName(name); } catch (Exception e) { return null; }
    }

    /* ═════════════════ render ═════════════════ */

    private static final String PROFILE = "vivarium-reader";
    private static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private static boolean rendering = false;

    static boolean canRender() {
        try { return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE); } catch (Throwable t) { return false; }
    }

    /** One page at a time, always on the main thread. */
    static void render(Context ctx, JSONObject args, Allow allow, Done done) {
        final Context app = ctx.getApplicationContext();
        MAIN.post(() -> {
            queue.add(() -> renderNow(app, args, allow, (v, code, msg) -> {
                if (v != null) done.ok(v); else done.fail(code, msg);
                rendering = false;
                MAIN.post(Abilities::next);
            }));
            next();
        });
    }

    private static void next() {
        if (rendering) return;
        Runnable r = queue.poll();
        if (r == null) return;
        rendering = true;
        r.run();
    }

    private interface Finish { void end(JSONObject v, String code, String msg); }

    @SuppressLint("SetJavaScriptEnabled")
    private static void renderNow(Context ctx, JSONObject args, Allow allow, Finish fin) {
        if (!canRender()) { fin.end(null, "unsupported", "this WebView can't keep a separate profile"); return; }
        final String raw = args.optString("url", "");
        final int settle = clamp(args.optInt("settle", 2500), 0, 10000);
        final int timeout = clamp(args.optInt("timeout", 30000), 5000, 60000);
        final String waitFor = args.optString("waitFor", "");
        final long t0 = System.currentTimeMillis();

        // The host checks resolve DNS, so they run off the main thread first.
        NET.execute(() -> {
            try {
                checkTarget(new URL(raw), allow);
            } catch (Failure f) {
                MAIN.post(() -> fin.end(null, f.code, f.getMessage()));
                return;
            } catch (Exception e) {
                MAIN.post(() -> fin.end(null, "bad-url", "not a URL"));
                return;
            }
            MAIN.post(() -> {
                final WebView wv;
                try {
                    wv = new WebView(ctx);
                    WebViewCompat.setProfile(wv, PROFILE);
                } catch (Throwable t) {
                    fin.end(null, "unsupported", "could not start a reader");
                    return;
                }
                final boolean[] over = { false };
                final int[] status = { 200 };
                final Runnable[] killer = new Runnable[1];

                final Runnable cleanup = () -> {
                    MAIN.removeCallbacks(killer[0]);
                    try { wv.stopLoading(); wv.setWebViewClient(new WebViewClient()); wv.destroy(); } catch (Exception ignored) {}
                };
                final Finish once = (v, code, msg) -> {
                    if (over[0]) return;
                    over[0] = true;
                    cleanup.run();
                    fin.end(v, code, msg);
                };
                killer[0] = () -> once.end(null, "timeout", "the page took too long");
                MAIN.postDelayed(killer[0], timeout);

                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setBlockNetworkImage(true);
                s.setLoadsImagesAutomatically(false);
                s.setMediaPlaybackRequiresUserGesture(true);
                s.setSupportMultipleWindows(false);
                s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setGeolocationEnabled(false);
                s.setUserAgentString(userAgent(ctx));

                final Runnable harvest = () -> wv.evaluateJavascript(
                    "(function(){return JSON.stringify({u:location.href,t:document.title," +
                        "h:'<!DOCTYPE html>'+document.documentElement.outerHTML})})()",
                    (res) -> {
                        try {
                            Object str = new JSONTokener(res).nextValue();
                            JSONObject page = new JSONObject(String.valueOf(str));
                            String html = page.optString("h", "");
                            JSONObject r = new JSONObject();
                            r.put("ok", status[0] < 400);
                            r.put("status", status[0]);
                            r.put("url", page.optString("u", raw));
                            r.put("title", page.optString("t", ""));
                            r.put("text", html.length() > (6 << 20) ? html.substring(0, 6 << 20) : html);
                            r.put("bytes", html.length());
                            r.put("ms", System.currentTimeMillis() - t0);
                            once.end(r, null, null);
                        } catch (Exception e) {
                            once.end(null, "failed", "could not read the rendered page");
                        }
                    });

                final boolean[] started = { false };
                final Runnable afterLoad = () -> {
                    if (over[0] || started[0]) return;
                    started[0] = true;
                    if (waitFor.isEmpty()) { MAIN.postDelayed(harvest, settle); return; }
                    final long until = System.currentTimeMillis() + Math.max(settle, 8000);
                    final String probe = "!!document.querySelector(" + JSONObject.quote(waitFor) + ")";
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (over[0]) return;
                            wv.evaluateJavascript(probe, (r) -> {
                                if ("true".equals(r) || System.currentTimeMillis() > until) MAIN.postDelayed(harvest, 300);
                                else MAIN.postDelayed(this, 350);
                            });
                        }
                    });
                };

                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                        Uri u = req.getUrl();
                        String sc = u.getScheme();
                        if (!"https".equals(sc) && !"http".equals(sc)) return true;
                        if (isLocalName(u.getHost()) || isPrivateLiteral(u.getHost())) return true;
                        return req.isForMainFrame() && !allow.covers(u.getHost());
                    }

                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                        Uri u = req.getUrl();
                        String sc = u.getScheme();
                        if (!"https".equals(sc) && !"http".equals(sc)) return blank(403);
                        if (isLocalName(u.getHost()) || isPrivateLiteral(u.getHost())) return blank(403);
                        if (!req.isForMainFrame() && SKIP.matcher(u.getPath() == null ? "" : u.getPath()).find()) return blank(200);
                        return null;
                    }

                    @Override
                    public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                        if (req.isForMainFrame()) status[0] = resp.getStatusCode();
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                        if (req.isForMainFrame()) once.end(null, "network", String.valueOf(err.getDescription()));
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        afterLoad.run();
                    }

                    @Override
                    public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                        if (!over[0]) {
                            over[0] = true;
                            MAIN.removeCallbacks(killer[0]);
                            try { view.destroy(); } catch (Exception ignored) {}
                            fin.end(null, "failed", "the page crashed its reader");
                        }
                        return true;
                    }
                });
                wv.onResume();
                wv.loadUrl(raw);
            });
        });
    }

    private static WebResourceResponse blank(int code) {
        return new WebResourceResponse("text/plain", "utf-8", code, code == 200 ? "OK" : "Blocked",
            null, new ByteArrayInputStream(new byte[0]));
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
