package com.dsh.phoneapproval;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Long-polls the DSH host plugin and mirrors pending approvals as notifications. */
public class ApprovalService extends Service {

    public static final String ACTION_START = "com.dsh.phoneapproval.START";
    public static final String ACTION_STOP = "com.dsh.phoneapproval.STOP";
    public static final String ACTION_ANSWER = "com.dsh.phoneapproval.ANSWER";
    public static final String ACTION_STATE = "com.dsh.phoneapproval.STATE";
    public static final String ACTION_SNAPSHOT = "com.dsh.phoneapproval.SNAPSHOT";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_OUTCOME = "outcome";
    public static final String EXTRA_PENDING_JSON = "pending_json";
    public static final String EXTRA_STATUS = "status";

    private static final String CHANNEL_PENDING = "pending";
    private static final String CHANNEL_STATUS = "status";
    private static final int STATUS_NOTIFICATION_ID = 1;

    private volatile boolean connected;
    private Thread loopThread;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> notificationIds = new HashMap<>();
    private int nextNotificationId = 100;
    private long lastVersion = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_PENDING, "Pending approvals", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            connected = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SNAPSHOT.equals(action)) {
            // Freshly opened activity asks for the current pending list; the
            // loop keeps lastPendingJson current even while no activity is up.
            Intent state = new Intent(ACTION_STATE).setPackage(getPackageName())
                    .putExtra(EXTRA_PENDING_JSON, lastPendingJson)
                    .putExtra(EXTRA_STATUS, statusText);
            sendBroadcast(state);
            if (!connected) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ANSWER.equals(action) && intent != null) {
            String id = intent.getStringExtra(EXTRA_ID);
            String outcome = intent.getStringExtra(EXTRA_OUTCOME);
            if (id != null && outcome != null) {
                final String fid = id;
                final String foutcome = outcome;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        postAnswer(fid, foutcome);
                    }
                }).start();
            }
            return START_STICKY;
        }
        // ACTION_START (or restart)
        SharedPreferences prefs = prefs();
        String server = prefs.getString("server", "");
        String token = prefs.getString("token", "");
        if (server.isEmpty() || token.isEmpty()) {
            setStatus("server/token not configured");
            return START_NOT_STICKY;
        }
        startForeground(STATUS_NOTIFICATION_ID, buildStatusNotification("connecting…"));
        if (!connected) {
            connected = true;
            lastVersion = -1;
            loopThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "dsh-poll");
            loopThread.start();
        }
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("dsh", Context.MODE_PRIVATE);
    }

    private void loop() {
        while (connected) {
            try {
                String server = prefs().getString("server", "").trim();
                String token = prefs().getString("token", "").trim();
                if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
                String url = server + "/api/poll?wait=25&since=" + lastVersion;
                String body = http("GET", url, token, null);
                JSONObject json = new JSONObject(body);
                if (!connected) break;
                lastVersion = json.getLong("version");
                JSONArray pending = json.getJSONArray("pending");
                reconcile(pending);
                setStatus(pending.length() == 0 ? "connected · idle" : "connected · " + pending.length() + " pending");
            } catch (Exception error) {
                if (!connected) break;
                setStatus("offline: " + messageOf(error));
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void reconcile(JSONArray pending) throws org.json.JSONException {
        Set<String> liveIds = new HashSet<>();
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.getJSONObject(i);
            String id = item.getString("id");
            liveIds.add(id);
            items.add(item);
            Integer known = notificationIds.get(id);
            int nid = known != null ? known : allocateNotificationId(id);
            showApprovalNotification(nid, item);
        }
        // cancel notifications whose approvals are gone (answered on the PC or expired)
        NotificationManager nm = getSystemService(NotificationManager.class);
        List<String> dead = new ArrayList<>();
        for (String id : notificationIds.keySet()) {
            if (!liveIds.contains(id)) dead.add(id);
        }
        for (String id : dead) {
            nm.cancel(notificationIds.remove(id));
        }
        nm.notify(STATUS_NOTIFICATION_ID, buildStatusNotification(statusText));
        broadcastState(items);
    }

    private int allocateNotificationId(String id) {
        int nid = nextNotificationId++;
        notificationIds.put(id, nid);
        return nid;
    }

    private String statusText = "connecting…";

    private void setStatus(final String text) {
        statusText = text;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (connected) {
                    getSystemService(NotificationManager.class)
                            .notify(STATUS_NOTIFICATION_ID, buildStatusNotification(text));
                }
                broadcastEmptyState(text);
            }
        });
    }

    private Notification buildStatusNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_STATUS)
                .setContentTitle("DSH Approvals")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openPi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) builder.setColor(0xFF1565C0);
        return builder.build();
    }

    private volatile String lastPendingJson = "[]";

    private void showApprovalNotification(int nid, JSONObject item) {
        String id = item.optString("id");
        String tool = item.optString("toolName", "tool");
        String reason = item.optString("reason");
        String command = item.optString("command");
        StringBuilder big = new StringBuilder();
        if (command != null && !command.isEmpty()) big.append(command).append('\n');
        if (reason != null && !reason.isEmpty()) big.append(reason);
        if (big.length() > 3000) big.setLength(3000);
        String firstLine = command != null && !command.isEmpty()
                ? command.split("\n", 2)[0]
                : (reason != null && !reason.isEmpty() ? reason.split("\n", 2)[0] : tool + " requests permission");

        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_PENDING)
                .setContentTitle("Approve: " + tool)
                .setContentText(firstLine)
                .setStyle(new Notification.BigTextStyle().bigText(big.length() == 0 ? tool : big))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(false)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(PendingIntent.getActivity(this, nid,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        builder.addAction(new Notification.Action.Builder(null, "Allow",
                answerPending("allow-once", id, nid + 1)).build());
        builder.addAction(new Notification.Action.Builder(null, "Deny",
                answerPending("reject", id, nid + 2)).build());

        nm.notify(nid, builder.build());
    }

    private PendingIntent answerPending(String outcome, String id, int requestCode) {
        Intent intent = new Intent(this, ApprovalService.class).setAction(ACTION_ANSWER)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_OUTCOME, outcome);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void postAnswer(String id, String outcome) {
        try {
            String server = prefs().getString("server", "").trim();
            String token = prefs().getString("token", "").trim();
            if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
            JSONObject payload = new JSONObject();
            payload.put("id", id);
            payload.put("outcome", outcome);
            http("POST", server + "/api/answer", token, payload.toString());
            setStatus("answered " + (outcome.equals("allow-once") ? "allow" : "deny") + " · " + id);
        } catch (Exception error) {
            setStatus("answer failed: " + messageOf(error));
        }
    }

    private void broadcastState(List<JSONObject> items) {
        JSONArray array = new JSONArray(items);
        lastPendingJson = array.toString();
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra(EXTRA_PENDING_JSON, lastPendingJson)
                .putExtra(EXTRA_STATUS, statusText);
        sendBroadcast(intent);
    }

    private void broadcastEmptyState(String text) {
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, text);
        sendBroadcast(intent);
    }

    static String http(String method, String url, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(40000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) buffer.write(chunk, 0, read);
            stream.close();
        }
        conn.disconnect();
        String text = buffer.toString("UTF-8");
        if (status >= 400) {
            throw new java.io.IOException("HTTP " + status + " " + (text.length() > 120 ? text.substring(0, 120) : text));
        }
        return text;
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (loopThread != null) loopThread.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
