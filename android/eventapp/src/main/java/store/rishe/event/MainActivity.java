package store.rishe.event;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String LOCAL_APP_URL = "file:///android_asset/event/index.html";
    private static final String API_ROOT = "https://rishe.smarbiz.sbs/api/event-rishe";
    private static final String PREFS = "event_rishe_session";
    private static final String TOKEN_KEY = "device_token";

    private WebView webView;
    private SharedPreferences preferences;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#173C2F"));
        getWindow().setNavigationBarColor(Color.parseColor("#F4F2E9"));
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

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
        settings.setUserAgentString(settings.getUserAgentString() + " RisheEventAndroid/2.1");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        webView.addJavascriptInterface(new NativeApi(), "AndroidApi");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl(LOCAL_APP_URL);
    }

    public final class NativeApi {
        @JavascriptInterface
        public boolean hasSession() {
            return !preferences.getString(TOKEN_KEY, "").isEmpty();
        }

        @JavascriptInterface
        public void logout() {
            preferences.edit().remove(TOKEN_KEY).apply();
        }

        @JavascriptInterface
        public void login(String requestId, String username, String password) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("username", username == null ? "" : username);
                    body.put("password", password == null ? "" : password);
                    NetworkResponse response = performRequest("POST", "/device-login", body.toString(), "");
                    if (response.status >= 200 && response.status < 300) {
                        JSONObject data = new JSONObject(response.body);
                        String token = data.optString("device_token", "");
                        if (token.isEmpty()) {
                            callback(requestId, false, 500, jsonMessage("توکن ورود از سرور دریافت نشد."));
                            return;
                        }
                        preferences.edit().putString(TOKEN_KEY, token).apply();
                        callback(requestId, true, response.status, response.body);
                        return;
                    }
                    callback(requestId, false, response.status, normalizeError(response.body, "ورود انجام نشد."));
                } catch (Exception error) {
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
                            method == null ? "GET" : method.toUpperCase(),
                            safePath,
                            body == null ? "" : body,
                            token
                    );
                    boolean ok = response.status >= 200 && response.status < 300;
                    if (!ok && (response.status == 401 || response.status == 403)) {
                        preferences.edit().remove(TOKEN_KEY).apply();
                    }
                    callback(
                            requestId,
                            ok,
                            response.status,
                            ok ? response.body : normalizeError(response.body, "ارتباط با سرور انجام نشد.")
                    );
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
        connection.setRequestProperty("User-Agent", "Event-Rishe-Android/2.1");
        connection.setUseCaches(false);
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("X-Rishe-Event-Token", token);
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
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        String responseBody = readAll(input);
        connection.disconnect();
        if (responseBody.trim().isEmpty()) {
            responseBody = "{}";
        }
        return new NetworkResponse(status, responseBody);
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "{}";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
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
        String raw = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
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
        String script = "window.__nativeResult(" + safeId + "," + (ok ? "true" : "false") + "," + status + "," + safePayload + ");";
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
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
