package store.rishe.event;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String LOCAL_APP_URL = "file:///android_asset/event/index.html";
    private static final String API_ROOT = "https://rishe.smarbiz.sbs/api/event-rishe";
    private static final String PREFS = EventSyncWorker.PREFS;
    private static final String TOKEN_KEY = EventSyncWorker.TOKEN_KEY;
    private static final long MAX_IMAGE_BYTES = 12L * 1024L * 1024L;

    private WebView webView;
    private SharedPreferences preferences;
    private File productImageDirectory;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#173C2F"));
        getWindow().setNavigationBarColor(Color.parseColor("#F4F2E9"));
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        productImageDirectory = new File(getFilesDir(), "event_product_images");
        if (!productImageDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            productImageDirectory.mkdirs();
        }

        if (EventSyncWorker.hasPending(this)) {
            EventSyncWorker.schedule(this);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " RisheEventAndroid/2.6");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        webView.addJavascriptInterface(new NativeApi(), "AndroidApi");
        webView.setWebViewClient(new OfflineImageWebViewClient());
        webView.loadUrl(LOCAL_APP_URL);
    }

    private final class OfflineImageWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request != null && "GET".equalsIgnoreCase(request.getMethod())) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (isImageRequest(url, request.getRequestHeaders())) {
                    WebResourceResponse cached = productImageResponse(url);
                    if (cached != null) return cached;
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String script = "(function(){"
                    + "if(!document.getElementById('rishe-compact-products')){var st=document.createElement('style');st.id='rishe-compact-products';"
                    + "st.textContent='"
                    + ".products{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:6px!important;margin-top:8px!important;}"
                    + ".product{display:grid!important;grid-template-columns:46px minmax(0,1fr) 34px!important;grid-template-rows:auto auto!important;grid-template-areas:\"media title add\" \"media meta add\"!important;align-items:center!important;column-gap:7px!important;row-gap:1px!important;min-height:58px!important;padding:6px!important;border-radius:12px!important;overflow:hidden!important;}"
                    + ".product-media{grid-area:media!important;width:46px!important;height:46px!important;aspect-ratio:auto!important;border-radius:9px!important;align-self:center!important;}"
                    + ".product-media:before{font-size:18px!important;}"
                    + ".product strong{grid-area:title!important;font-size:13px!important;line-height:1.45!important;min-height:0!important;-webkit-line-clamp:1!important;font-weight:900!important;margin:0!important;align-self:end!important;}"
                    + ".product-meta{grid-area:meta!important;display:flex!important;align-items:center!important;justify-content:flex-start!important;gap:5px!important;margin:0!important;align-self:start!important;min-width:0!important;}"
                    + ".price{font-size:14px!important;line-height:1.35!important;font-weight:900!important;color:var(--green)!important;white-space:nowrap!important;}"
                    + ".stock{font-size:8px!important;padding:2px 4px!important;line-height:1.2!important;max-width:46px!important;overflow:hidden!important;text-overflow:ellipsis!important;}"
                    + ".product button{grid-area:add!important;width:34px!important;height:42px!important;min-height:42px!important;padding:0!important;border-radius:10px!important;font-size:0!important;box-shadow:none!important;align-self:center!important;}"
                    + ".product button:after{content:\"+\";font-size:22px!important;font-weight:900!important;line-height:1!important;}"
                    + ".search{margin-top:7px!important;}"
                    + "@media(min-width:620px){.products{grid-template-columns:repeat(3,minmax(0,1fr))!important;}}"
                    + "@media(max-width:350px){.product{grid-template-columns:42px minmax(0,1fr) 30px!important;min-height:54px!important;padding:5px!important;column-gap:5px!important}.product-media{width:42px!important;height:42px!important}.product strong{font-size:11.5px!important}.price{font-size:12.5px!important}.stock{display:none!important}.product button{width:30px!important;height:38px!important;min-height:38px!important}}';"
                    + "document.head.appendChild(st);}"
                    + "if(window.__risheImageWarmup)return;window.__risheImageWarmup=true;"
                    + "function warm(){document.querySelectorAll('.product-media img').forEach(function(i){"
                    + "try{i.loading='eager';i.decoding='async';if(i.dataset.risheWarm!=='1'){i.dataset.risheWarm='1';var s=i.src;i.src='';i.src=s;}}catch(e){}"
                    + "});}"
                    + "warm();new MutationObserver(warm).observe(document.documentElement,{childList:true,subtree:true});"
                    + "})();";
            view.evaluateJavascript(script, null);
        }
    }

    private boolean isImageRequest(String url, Map<String, String> headers) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return false;
        String accept = headers == null ? "" : headers.get("Accept");
        if (accept != null && accept.toLowerCase(Locale.US).contains("image/")) return true;
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".avif");
    }

    private WebResourceResponse productImageResponse(String url) {
        try {
            String key = sha256(url);
            File data = new File(productImageDirectory, key + ".bin");
            File meta = new File(productImageDirectory, key + ".mime");
            if (data.isFile() && data.length() > 0) {
                return imageResponse(data, readMime(meta, mimeFromUrl(url)));
            }
            if (!hasNetwork()) return null;

            File temp = new File(productImageDirectory, key + "." + Thread.currentThread().getId() + ".tmp");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
                connection.setRequestProperty("User-Agent", "Event-Rishe-Android/2.6");
                connection.setUseCaches(false);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) return null;
                long declaredLength = connection.getContentLengthLong();
                if (declaredLength > MAX_IMAGE_BYTES) return null;

                String mime = connection.getContentType();
                if (mime != null) {
                    int semicolon = mime.indexOf(';');
                    if (semicolon >= 0) mime = mime.substring(0, semicolon);
                    mime = mime.trim().toLowerCase(Locale.US);
                }
                if (mime == null || !mime.startsWith("image/")) mime = mimeFromUrl(url);

                long total = 0;
                byte[] buffer = new byte[16 * 1024];
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_IMAGE_BYTES) throw new IllegalStateException("Image too large");
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                }
                if (total < 1) return null;
                if (!data.exists() && !temp.renameTo(data)) return null;
                if (temp.exists() && data.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
                writeMime(meta, mime);
                return imageResponse(data, mime);
            } catch (Exception ignored) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return data.isFile() && data.length() > 0
                        ? imageResponse(data, readMime(meta, mimeFromUrl(url))) : null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private WebResourceResponse imageResponse(File file, String mime) throws Exception {
        return new WebResourceResponse(mime, null, new FileInputStream(file));
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) result.append(String.format(Locale.US, "%02x", b & 0xff));
        return result.toString();
    }

    private String mimeFromUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".avif")) return "image/avif";
        return "image/jpeg";
    }

    private void writeMime(File file, String mime) {
        try (OutputStream output = new FileOutputStream(file, false)) {
            output.write((mime == null ? "image/jpeg" : mime).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String readMime(File file, String fallback) {
        if (!file.isFile()) return fallback;
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 128)];
            int count = input.read(bytes);
            String value = count > 0 ? new String(bytes, 0, count, StandardCharsets.UTF_8).trim() : "";
            return value.startsWith("image/") ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public final class NativeApi {
        @JavascriptInterface
        public boolean hasSession() {
            return !preferences.getString(TOKEN_KEY, "").isEmpty();
        }

        @JavascriptInterface
        public void logout() {
            preferences.edit().remove(TOKEN_KEY).commit();
        }

        @JavascriptInterface
        public void mirrorSale(String saleJson) {
            try {
                EventSyncWorker.mirrorSale(MainActivity.this, saleJson);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void retrySale(String clientUuid) {
            EventSyncWorker.retrySale(MainActivity.this, clientUuid);
        }

        @JavascriptInterface
        public void forgetSale(String clientUuid) {
            EventSyncWorker.forgetSale(MainActivity.this, clientUuid);
        }

        @JavascriptInterface
        public String backgroundResults() {
            return EventSyncWorker.resultsJson(MainActivity.this);
        }

        @JavascriptInterface
        public void acknowledgeBackgroundResults(String idsJson) {
            EventSyncWorker.acknowledgeResults(MainActivity.this, idsJson);
        }

        @JavascriptInterface
        public void scheduleBackgroundSync() {
            if (EventSyncWorker.hasPending(MainActivity.this)) EventSyncWorker.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public void login(String requestId, String username, String password) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("username", username == null ? "" : username);
                    body.put("password", password == null ? "" : password);
                    NetworkResponse response = performRequest("POST", "/device-login", body.toString(), "");
                    if (response.status < 200 || response.status >= 300) {
                        callback(requestId, false, response.status, normalizeError(response.body, "ورود انجام نشد."));
                        return;
                    }

                    JSONObject data = new JSONObject(response.body);
                    String token = data.optString("device_token", "");
                    if (token.isEmpty()) {
                        callback(requestId, false, 500, jsonMessage("توکن ورود از سرور دریافت نشد."));
                        return;
                    }

                    NetworkResponse validation = performRequest("GET", "/bootstrap", "", token);
                    if (validation.status < 200 || validation.status >= 300) {
                        preferences.edit().remove(TOKEN_KEY).commit();
                        callback(requestId, false, validation.status,
                                normalizeError(validation.body, "ورود تأیید شد، اما نشست فروش توسط سرور پذیرفته نشد."));
                        return;
                    }

                    if (!preferences.edit().putString(TOKEN_KEY, token).commit()) {
                        callback(requestId, false, 500, jsonMessage("ذخیره نشست ورود روی گوشی انجام نشد."));
                        return;
                    }
                    if (EventSyncWorker.hasPending(MainActivity.this)) EventSyncWorker.schedule(MainActivity.this);

                    JSONObject success = new JSONObject();
                    success.put("login", data);
                    success.put("bootstrap", new JSONObject(validation.body));
                    callback(requestId, true, response.status, success.toString());
                } catch (Exception error) {
                    preferences.edit().remove(TOKEN_KEY).commit();
                    callback(requestId, false, 0, jsonMessage(networkMessage(error)));
                }
            }).start();
        }

        @JavascriptInterface
        public void request(String requestId, String method, String path, String body) {
            new Thread(() -> {
                String token = preferences.getString(TOKEN_KEY, "");
                if (token.isEmpty()) {
                    callback(requestId, false, 401, jsonMessage("ابتدا وارد حساب فروشنده شوید."));
                    return;
                }
                try {
                    String safePath = path != null && path.startsWith("/") ? path : "/";
                    NetworkResponse response = performRequest(
                            method == null ? "GET" : method.toUpperCase(Locale.US),
                            safePath,
                            body == null ? "" : body,
                            token
                    );
                    boolean ok = response.status >= 200 && response.status < 300;
                    if (!ok && (response.status == 401 || response.status == 403)) {
                        preferences.edit().remove(TOKEN_KEY).commit();
                    }
                    callback(requestId, ok, response.status,
                            ok ? response.body : normalizeError(response.body, "ارتباط با سرور انجام نشد."));
                } catch (Exception error) {
                    callback(requestId, false, 0, jsonMessage(networkMessage(error)));
                }
            }).start();
        }
    }

    private NetworkResponse performRequest(String method, String path, String body, String token) throws Exception {
        URL url = new URL(API_ROOT + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(22000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "Event-Rishe-Android/2.6");
        connection.setUseCaches(false);
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("X-Rishe-Event-Token", token);
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            connection.setDoOutput(true);
            byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = readAll(input);
        connection.disconnect();
        return new NetworkResponse(status, responseBody.trim().isEmpty() ? "{}" : responseBody);
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "{}";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private String normalizeError(String body, String fallback) {
        try {
            JSONObject source = new JSONObject(body == null ? "{}" : body);
            String message = source.optString("message", fallback);
            return jsonMessage(message.isEmpty() ? fallback : message);
        } catch (Exception ignored) {
            return jsonMessage(fallback);
        }
    }

    private String networkMessage(Exception error) {
        String raw = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.US);
        if (raw.contains("unable to resolve host") || raw.contains("name or service")) {
            return "سرور واسط پیدا نشد. اینترنت یا DNS گوشی را بررسی کنید.";
        }
        if (raw.contains("timed out") || raw.contains("timeout")) {
            return "سرور واسط پاسخ نداد. دوباره تلاش کنید.";
        }
        if (raw.contains("certificate") || raw.contains("ssl") || raw.contains("handshake")) {
            return "اتصال امن به سرور واسط برقرار نشد.";
        }
        return "ارتباط با سرور واسط برقرار نشد. اینترنت را بررسی کنید.";
    }

    private String jsonMessage(String message) {
        try {
            JSONObject result = new JSONObject();
            result.put("message", message);
            return result.toString();
        } catch (Exception ignored) {
            return "{\"message\":\"خطای ارتباط\"}";
        }
    }

    private void callback(String requestId, boolean ok, int status, String payload) {
        String safeId = JSONObject.quote(requestId == null ? "" : requestId);
        String safePayload = JSONObject.quote(payload == null ? "{}" : payload);
        String forceUi = ok
                ? "var __l=document.getElementById('login');if(__l){__l.hidden=true;__l.style.setProperty('display','none','important');}"
                : "";
        String script = forceUi + "window.__nativeResult(" + safeId + "," + (ok ? "true" : "false") + "," + status + "," + safePayload + ");";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (EventSyncWorker.hasPending(this)) EventSyncWorker.schedule(this);
        if (webView != null) {
            webView.evaluateJavascript("if(window.__risheReconcileBackground){window.__risheReconcileBackground();}", null);
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private static final class NetworkResponse {
        final int status;
        final String body;

        NetworkResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}