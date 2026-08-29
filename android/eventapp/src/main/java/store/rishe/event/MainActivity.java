package store.rishe.event;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://rishe.smarbiz.sbs/rishe-event-app/";
    private static final String TRUSTED_HOST = "rishe.smarbiz.sbs";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#173C2F"));
        getWindow().setNavigationBarColor(Color.parseColor("#F4F2E9"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " RisheEventAndroid/1.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showFirstRunOfflinePage();
                }
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(APP_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String path = uri.getPath() == null ? "/" : uri.getPath();

        if ((scheme.equals("http") || scheme.equals("https")) && host.equals(TRUSTED_HOST)) {
            if (path.startsWith("/rishe-event-app/") || path.equals("/wp-login.php")) {
                return false;
            }

            webView.loadUrl(APP_URL);
            return true;
        }

        if (scheme.equals("about") || scheme.equals("data")) {
            return false;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // Unsupported external links are ignored; the POS remains on its own screen.
        }
        return true;
    }

    private void showFirstRunOfflinePage() {
        String html = "<!doctype html><html lang='fa' dir='rtl'><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;box-sizing:border-box;"
                + "background:#f4f2e9;color:#173c2f;font-family:sans-serif;text-align:center}.c{max-width:430px;background:#fff;"
                + "border:1px solid #d8d3c5;border-radius:24px;padding:30px;box-shadow:0 16px 40px rgba(23,60,47,.12)}"
                + "button{border:0;border-radius:14px;background:#173c2f;color:#fff;padding:13px 22px;font:inherit;font-weight:700}</style>"
                + "</head><body><div class='c'><h1>ایونت ریشه</h1>"
                + "<p>برای راه‌اندازی اولیه، یک بار اینترنت را وصل کن و وارد حساب فروشنده شو. بعد از آن فروش آفلاین در خود دستگاه ذخیره می‌شود.</p>"
                + "<button onclick=\"location.href='" + APP_URL + "'\">تلاش دوباره</button></div></body></html>";
        webView.loadDataWithBaseURL(APP_URL, html, "text/html", "UTF-8", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        String current = webView == null ? "" : webView.getUrl();
        if (current != null && current.contains("/wp-login.php") && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
