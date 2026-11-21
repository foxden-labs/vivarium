package io.github.foxdenlabs.vivarium;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

/*
 * Capacitor's own client, with one thing added in front: anything on a tool
 * origin is answered by ToolServer. Everything else (the launcher itself at
 * https://localhost, real websites) goes through Capacitor untouched.
 */
public class ToolHostClient extends BridgeWebViewClient {

    private final Context ctx;

    public ToolHostClient(Bridge bridge) {
        super(bridge);
        this.ctx = bridge.getContext().getApplicationContext();
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        WebResourceResponse r = ToolServer.serve(ctx, request.getUrl());
        return r != null ? r : super.shouldInterceptRequest(view, request);
    }

    // Capacitor would hand unknown hosts to the system browser. Tool origins
    // are ours, so they always load in place.
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (ToolServer.isToolUrl(request.getUrl())) return false;
        return super.shouldOverrideUrlLoading(view, request);
    }
}
