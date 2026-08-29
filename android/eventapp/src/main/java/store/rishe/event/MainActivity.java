package store.rishe.event;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://rishe.store/rishe-event-app/";
    private static final String FALLBACK_APP_URL = "https://rishe.store/?rishe_event_app=1";

    private WebView webView;
    private boolean triedFallback = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#173C2F"));
        getWindow().setNavigationBarColor(Color.parseColor("#F4F2E9"));

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
        settings.setUserAgentString(settings.getUserAgentString() + " RisheEventAndroid/1.2");
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

                Uri uri = Uri.parse(url == null ? "" : url);
                if (isEventAppUri(uri)) {
                    verifyAndLockEventScreen(view);
                    return;
                }

                if (isLoginUri(uri)) {
                    return;
                }

                if (isTrustedHttpUri(uri)) {
                    loadEventApp();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    if (!triedFallback) {
                        triedFallback = true;
                        webView.loadUrl(FALLBACK_APP_URL);
                    } else {
                        showFirstRunOfflinePage();
                    }
                }
            }
        });

        // Never restore the old Rishe app. Every launch starts on the dedicated event POS.
        loadEventApp();
    }

    private void verifyAndLockEventScreen(WebView view) {
        view.evaluateJavascript(
                "document.getElementById('rishe-event-app') ? 'ok' : 'wrong'",
                result -> {
                    if ("\"ok\"".equals(result)) {
                        triedFallback = false;
                        injectKioskCss(view);
                        view.clearHistory();
                    } else if (!triedFallback) {
                        triedFallback = true;
                        view.loadUrl(FALLBACK_APP_URL);
                    } else {
                        showWrongEndpointPage();
                    }
                }
        );
    }

    private void injectKioskCss(WebView view) {
        String script = "(function(){"
                + "var old=document.getElementById('rishe-event-apk-lock');if(old)old.remove();"
                + "var s=document.createElement('style');s.id='rishe-event-apk-lock';"
                + "s.textContent='.event-app__nav{display:none!important}'"
                + "+'[data-screen=\\\"queue\\\"]{display:none!important}'"
                + "+'.event-app{padding-bottom:0!important}';"
                + "document.head.appendChild(s);"
                + "var sale=document.querySelector('[data-screen=\\\"sale\\\"]');"
                + "if(sale){sale.classList.add('is-active');sale.style.display='block';}"
                + "})();";
        view.evaluateJavascript(script, null);
    }

    private void loadEventApp() {
        if (webView == null) return;
        webView.loadUrl(triedFallback ? FALLBACK_APP_URL : APP_URL);
    }

    private boolean handleNavigation(Uri uri) {
        if (isEventAppUri(uri) || isLoginUri(uri)) {
            return false;
        }

        if (isTrustedHttpUri(uri)) {
            loadEventApp();
            return true;
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
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

    private boolean isTrustedHttpUri(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean trustedHost = host.equals("rishe.store") || host.equals("www.rishe.store");
        return (scheme.equals("http") || scheme.equals("https")) && trustedHost;
    }

    private boolean isLoginUri(Uri uri) {
        if (!isTrustedHttpUri(uri)) return false;
        String path = uri.getPath() == null ? "/" : uri.getPath();
        return path.equals("/wp-login.php");
    }

    private boolean isEventAppUri(Uri uri) {
        if (!isTrustedHttpUri(uri)) return false;
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (path.startsWith("/rishe-event-app/")) return true;
        return (path.equals("/") || path.isEmpty()) && "1".equals(uri.getQueryParameter("rishe_event_app"));
    }

    private void showWrongEndpointPage() {
        String html = "<!doctype html><html lang='fa' dir='rtl'><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;box-sizing:border-box;"
                + "background:#f4f2e9;color:#173c2f;font-family:sans-serif;text-align:center}.c{max-width:430px;background:#fff;"
                + "border:1px solid #d8d3c5;border-radius:24px;padding:30px}button{border:0;border-radius:14px;background:#173c2f;"
                + "color:#fff;padding:13px 22px;font:inherit;font-weight:700}</style></head><body><div class='c'>"
                + "<h1>ایونت ریشه</h1><p>صفحه فروش ایونت روی سایت پیدا نشد. افزونه ریشه را بررسی کنید.</p>"
                + "<button onclick=\"location.href='" + APP_URL + "'\">تلاش دوباره</button></div></body></html>";
        webView.loadDataWithBaseURL(APP_URL, html, "text/html", "UTF-8", null);
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
