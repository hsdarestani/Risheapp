package store.rishe.event;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

public final class EventSyncWorker extends Worker {
    public static final String PREFS = "event_rishe_session";
    public static final String TOKEN_KEY = "device_token";
    private static final String QUEUE_KEY = "background_sales_v1";
    private static final String RESULTS_KEY = "background_sync_results_v1";
    private static final String UNIQUE_WORK = "event-rishe-background-sync";
    private static final String API_ROOT = "https://rishe.smarbiz.sbs/api/event-rishe";
    private static final Object LOCK = new Object();

    public EventSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(EventSyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request);
    }

    public static void mirrorSale(Context context, String saleJson) throws Exception {
        JSONObject sale = new JSONObject(saleJson == null ? "{}" : saleJson);
        String uuid = sale.optString("client_uuid", "").trim();
        if (uuid.isEmpty()) throw new IllegalArgumentException("client_uuid is required");
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            JSONObject queue = objectFrom(prefs.getString(QUEUE_KEY, "{}"));
            JSONObject current = queue.optJSONObject(uuid);
            JSONObject item = current == null ? new JSONObject() : current;
            item.put("payload", sale);
            item.put("status", "pending_sync");
            item.put("error_message", "");
            item.put("updated_at", System.currentTimeMillis());
            if (!item.has("retry_count")) item.put("retry_count", 0);
            queue.put(uuid, item);
            prefs.edit().putString(QUEUE_KEY, queue.toString()).commit();
        }
        schedule(context);
    }

    public static void retrySale(Context context, String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            JSONObject queue = objectFrom(prefs.getString(QUEUE_KEY, "{}"));
            JSONObject item = queue.optJSONObject(uuid);
            if (item != null) {
                try {
                    item.put("status", "pending_sync");
                    item.put("error_message", "");
                    item.put("updated_at", System.currentTimeMillis());
                    queue.put(uuid, item);
                    prefs.edit().putString(QUEUE_KEY, queue.toString()).commit();
                } catch (Exception ignored) {
                }
            }
        }
        schedule(context);
    }

    public static void forgetSale(Context context, String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            JSONObject queue = objectFrom(prefs.getString(QUEUE_KEY, "{}"));
            JSONObject results = objectFrom(prefs.getString(RESULTS_KEY, "{}"));
            queue.remove(uuid);
            results.remove(uuid);
            prefs.edit()
                    .putString(QUEUE_KEY, queue.toString())
                    .putString(RESULTS_KEY, results.toString())
                    .commit();
        }
    }

    public static String resultsJson(Context context) {
        synchronized (LOCK) {
            return prefs(context).getString(RESULTS_KEY, "{}");
        }
    }

    public static void acknowledgeResults(Context context, String idsJson) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            JSONObject results = objectFrom(prefs.getString(RESULTS_KEY, "{}"));
            try {
                JSONArray ids = new JSONArray(idsJson == null ? "[]" : idsJson);
                for (int i = 0; i < ids.length(); i++) results.remove(ids.optString(i));
                prefs.edit().putString(RESULTS_KEY, results.toString()).commit();
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean hasPending(Context context) {
        synchronized (LOCK) {
            JSONObject queue = objectFrom(prefs(context).getString(QUEUE_KEY, "{}"));
            Iterator<String> keys = queue.keys();
            while (keys.hasNext()) {
                JSONObject item = queue.optJSONObject(keys.next());
                if (item != null && "pending_sync".equals(item.optString("status"))) return true;
            }
            return false;
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = prefs(context);
        String token = prefs.getString(TOKEN_KEY, "");
        if (token == null || token.isEmpty()) return Result.failure();

        JSONObject queue;
        JSONArray sales = new JSONArray();
        synchronized (LOCK) {
            queue = objectFrom(prefs.getString(QUEUE_KEY, "{}"));
            Iterator<String> keys = queue.keys();
            while (keys.hasNext() && sales.length() < 100) {
                String uuid = keys.next();
                JSONObject item = queue.optJSONObject(uuid);
                if (item == null || !"pending_sync".equals(item.optString("status"))) continue;
                JSONObject payload = item.optJSONObject("payload");
                if (payload != null) sales.put(payload);
            }
        }
        if (sales.length() == 0) return Result.success();

        try {
            JSONObject body = new JSONObject();
            body.put("sales", sales);
            NetworkResponse response = performRequest(token, body.toString());

            if (response.status == 401 || response.status == 403) {
                markBatchFailed(context, sales, "نشست فروش منقضی شده؛ داخل اپ دوباره وارد شوید.");
                return Result.failure();
            }
            if (response.status == 408 || response.status == 425 || response.status == 429 || response.status >= 500) {
                return Result.retry();
            }
            if (response.status < 200 || response.status >= 300) {
                markBatchFailed(context, sales, errorMessage(response.body, "ارسال پس‌زمینه توسط سرور رد شد."));
                return Result.success();
            }

            JSONObject data = new JSONObject(response.body);
            JSONArray synced = data.optJSONArray("synced");
            JSONArray failed = data.optJSONArray("failed");
            synchronized (LOCK) {
                JSONObject liveQueue = objectFrom(prefs.getString(QUEUE_KEY, "{}"));
                JSONObject results = objectFrom(prefs.getString(RESULTS_KEY, "{}"));

                if (synced != null) {
                    for (int i = 0; i < synced.length(); i++) {
                        JSONObject row = synced.optJSONObject(i);
                        if (row == null) continue;
                        String uuid = row.optString("client_uuid", "");
                        if (uuid.isEmpty()) continue;
                        liveQueue.remove(uuid);
                        JSONObject result = new JSONObject();
                        result.put("status", "synced");
                        result.put("synced_at", System.currentTimeMillis());
                        result.put("server", row);
                        results.put(uuid, result);
                    }
                }

                if (failed != null) {
                    for (int i = 0; i < failed.length(); i++) {
                        JSONObject row = failed.optJSONObject(i);
                        if (row == null) continue;
                        String uuid = row.optString("client_uuid", "");
                        if (uuid.isEmpty()) continue;
                        String message = row.optString("message", "ارسال فروش انجام نشد.");
                        JSONObject item = liveQueue.optJSONObject(uuid);
                        if (item != null) {
                            item.put("status", "sync_failed");
                            item.put("error_message", message);
                            item.put("retry_count", item.optInt("retry_count", 0) + 1);
                            item.put("updated_at", System.currentTimeMillis());
                            liveQueue.put(uuid, item);
                        }
                        JSONObject result = new JSONObject();
                        result.put("status", "sync_failed");
                        result.put("error_message", message);
                        result.put("updated_at", System.currentTimeMillis());
                        results.put(uuid, result);
                    }
                }

                trimResults(results, 500);
                prefs.edit()
                        .putString(QUEUE_KEY, liveQueue.toString())
                        .putString(RESULTS_KEY, results.toString())
                        .commit();
            }

            if (hasPending(context)) schedule(context);
            return Result.success();
        } catch (Exception ignored) {
            return Result.retry();
        }
    }

    private static void markBatchFailed(Context context, JSONArray sales, String message) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            JSONObject queue = objectFrom(prefs.getString(QUEUE_KEY, "{}"));
            JSONObject results = objectFrom(prefs.getString(RESULTS_KEY, "{}"));
            try {
                for (int i = 0; i < sales.length(); i++) {
                    JSONObject sale = sales.optJSONObject(i);
                    if (sale == null) continue;
                    String uuid = sale.optString("client_uuid", "");
                    JSONObject item = queue.optJSONObject(uuid);
                    if (item != null) {
                        item.put("status", "sync_failed");
                        item.put("error_message", message);
                        item.put("retry_count", item.optInt("retry_count", 0) + 1);
                        queue.put(uuid, item);
                    }
                    JSONObject result = new JSONObject();
                    result.put("status", "sync_failed");
                    result.put("error_message", message);
                    result.put("updated_at", System.currentTimeMillis());
                    results.put(uuid, result);
                }
                trimResults(results, 500);
                prefs.edit()
                        .putString(QUEUE_KEY, queue.toString())
                        .putString(RESULTS_KEY, results.toString())
                        .commit();
            } catch (Exception ignored) {
            }
        }
    }

    private static NetworkResponse performRequest(String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_ROOT + "/sync").openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(25000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "Event-Rishe-Android-Worker/2.5");
        connection.setRequestProperty("X-Rishe-Event-Token", token);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = readAll(input);
        connection.disconnect();
        return new NetworkResponse(status, responseBody.trim().isEmpty() ? "{}" : responseBody);
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "{}";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String errorMessage(String body, String fallback) {
        try {
            String message = new JSONObject(body == null ? "{}" : body).optString("message", fallback);
            return message.isEmpty() ? fallback : message;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static JSONObject objectFrom(String value) {
        try {
            return new JSONObject(value == null || value.isEmpty() ? "{}" : value);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void trimResults(JSONObject results, int max) {
        while (results.length() > max) {
            Iterator<String> keys = results.keys();
            if (!keys.hasNext()) break;
            results.remove(keys.next());
        }
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
