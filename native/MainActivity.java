package io.github.foxdenlabs.vivarium;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import com.getcapacitor.BridgeActivity;

/*
 * Copied over Capacitor's generated MainActivity by the workflow (the android
 * project is regenerated on every build, same as the Den).
 */
public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(VivariumPlugin.class);
        super.onCreate(savedInstanceState);
        if (bridge == null) return;

        bridge.setWebViewClient(new ToolHostClient(bridge));

        // A tool that registers a service worker gets its requests answered
        // here too; everything else falls through to Capacitor as before.
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    WebResourceResponse r = ToolServer.serve(getApplicationContext(), request.getUrl());
                    if (r != null) return r;
                    return bridge != null ? bridge.getLocalServer().shouldInterceptRequest(request) : null;
                }
            });
        } catch (Exception ignored) {}
    }

    // Also runs once from BridgeActivity.load() with the launch intent, so a
    // cold "Open with" lands in the inbox before the page asks for it.
    @Override
    protected void onNewIntent(Intent intent) {
        Inbox.accept(getApplicationContext(), intent);
        super.onNewIntent(intent);
    }
}
