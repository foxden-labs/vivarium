package io.github.foxdenlabs.vivarium;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Serves every imported tool from its own made-up origin:
 *
 *   https://<id>.vivarium.localhost/                 the tool itself
 *   https://<id>.vivarium.localhost/__vivarium/...   shared helpers (shim, font, keeper)
 *
 * Nothing ever goes over the network. Each <id> is a different hostname, so
 * the WebView gives every tool its own localStorage, IndexedDB, cookies and
 * caches, and no tool can read another's or the launcher's (https://localhost).
 *
 * The tool file lives at files/tools/<id>/tool.html, written by the launcher
 * through the Filesystem plugin (Directory.Data is getFilesDir()). It is never
 * modified on disk: the helper tags are spliced in here, per request.
 */
final class ToolServer {

    static final String SUFFIX = ".vivarium.localhost";
    private static final Pattern ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern HELPER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern HEAD = Pattern.compile("<head(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML = Pattern.compile("<html(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE = Pattern.compile("^\\s*<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final String INJECT =
        "<link rel=\"stylesheet\" href=\"/__vivarium/fonts.css\" data-vivarium>" +
        "<script src=\"/__vivarium/shim.js\" data-vivarium></script>";

    private ToolServer() {}

    /** The tool id if this URL belongs to a tool origin, otherwise null. */
    static String toolId(Uri url) {
        if (url == null || !"https".equals(url.getScheme())) return null;
        String host = url.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (!host.endsWith(SUFFIX)) return null;
        String id = host.substring(0, host.length() - SUFFIX.length());
        return ID.matcher(id).matches() ? id : null;
    }

    static boolean isToolUrl(Uri url) {
        return toolId(url) != null;
    }

    /** A response for any tool-origin URL (404 included), or null if the URL is not ours. */
    static WebResourceResponse serve(Context ctx, Uri url) {
        String id = toolId(url);
        if (id == null) return null;
        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";

        try {
            if (path.startsWith("/__vivarium/")) {
                String name = path.substring("/__vivarium/".length());
                if (!HELPER.matcher(name).matches() || name.contains("..")) return notFound();
                InputStream in;
                try {
                    in = ctx.getAssets().open("public/vivarium/" + name);
                } catch (IOException e) {
                    return notFound();
                }
                return ok(mimeFor(name), readAll(in));
            }

            if (path.equals("/") || path.equals("/index.html")) {
                File f = new File(ctx.getFilesDir(), "tools/" + id + "/tool.html");
                if (!f.isFile()) return missing(id);
                String html = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
                return ok("text/html", inject(html).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return error();
        }
        return notFound();
    }

    /* Helpers go right after <head>, so they run before any of the tool's
       own scripts. The charset rides on the response, so pushing the tool's
       <meta charset> further down does not matter. */
    static String inject(String html) {
        Matcher m = HEAD.matcher(html);
        if (!m.find()) {
            m = HTML.matcher(html);
            if (!m.find()) {
                m = DOCTYPE.matcher(html);
                if (!m.find()) return INJECT + html;
            }
        }
        return html.substring(0, m.end()) + INJECT + html.substring(m.end());
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        h.put("Access-Control-Allow-Origin", "*");
        // Only the shelf (or the tool itself) may put a tool page in a frame.
        // Stops one tool from loading another tool, or another tool's keeper,
        // inside itself.
        h.put("Content-Security-Policy", "frame-ancestors 'self' https://localhost");
        return h;
    }

    private static WebResourceResponse ok(String mime, byte[] body) {
        String enc = mime.startsWith("text/") ? "utf-8" : null;
        return new WebResourceResponse(mime, enc, 200, "OK", headers(), new ByteArrayInputStream(body));
    }

    private static WebResourceResponse notFound() {
        byte[] b = "Not found".getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(), new ByteArrayInputStream(b));
    }

    private static WebResourceResponse error() {
        byte[] b = "Could not read the tool".getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse("text/plain", "utf-8", 500, "Error", headers(), new ByteArrayInputStream(b));
    }

    private static WebResourceResponse missing(String id) {
        String page = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
            "<body style=\"margin:0;background:#000;color:#5e5d5a;font:12px/1.6 monospace;display:grid;place-items:center;height:100vh\">" +
            "<div>No file for " + id + "</div>";
        return new WebResourceResponse("text/html", "utf-8", 404, "Not Found", headers(),
            new ByteArrayInputStream(page.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
