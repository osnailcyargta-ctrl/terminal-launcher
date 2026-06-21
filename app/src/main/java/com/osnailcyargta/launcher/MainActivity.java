package com.osnailcyargta.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("launcher://launch")) {
                    Uri uri = request.getUrl();
                    String pkg = uri.getQueryParameter("pkg");
                    String name = uri.getQueryParameter("name");
                    if (pkg != null) launchByPackage(pkg, name);
                    return true;
                }
                return false;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void launchByPackage(String pkg, String name) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            // Fallback: open Play Store for that package
            try {
                Intent storeIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=" + Uri.encode(name != null ? name : pkg)));
                startActivity(storeIntent);
            } catch (Exception e) {
                Intent webIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(name != null ? name : pkg)));
                startActivity(webIntent);
            }
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void launchApp(String pkgOrName) {
            launchByPackage(pkgOrName, pkgOrName);
        }
    }

    @Override
    public void onBackPressed() {
        // Swallow back press — it's a launcher
    }
}
