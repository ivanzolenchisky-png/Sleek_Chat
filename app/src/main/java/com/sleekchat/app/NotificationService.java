package com.sleekchat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class NotificationService extends Service {

    private static final String TAG = "SleekChatWS";
    private static final String WS_URL = "wss://89.125.73.177:3443/ws";

    private static final String CHANNEL_MESSAGES = "messages";
    private static final String CHANNEL_CALLS = "calls";
    private static final int FOREGROUND_NOTIF_ID = 1;

    private OkHttpClient client;
    private WebSocket webSocket;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger notifIdCounter = new AtomicInteger(100);
    private boolean stopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        client = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification());
        connect();
        return START_STICKY;
    }

    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setContentTitle("Sleek Chat")
                .setContentText("Ожидание сообщений и звонков")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel messages = new NotificationChannel(
                CHANNEL_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_DEFAULT);
        messages.setDescription("Новые сообщения в чатах Sleek Chat");
        nm.createNotificationChannel(messages);

        NotificationChannel calls = new NotificationChannel(
                CHANNEL_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Входящие звонки Sleek Chat");
        calls.enableVibration(true);
        calls.setBypassDnd(true);
        nm.createNotificationChannel(calls);
    }

    private String currentCookie() {
        try {
            CookieManager cm = CookieManager.getInstance();
            return cm.getCookie("https://89.125.73.177:3443/");
        } catch (Exception e) {
            return null;
        }
    }

    private void connect() {
        if (stopping) return;
        String cookie = currentCookie();
        if (cookie == null || !cookie.contains("session=")) {
            retryHandler.postDelayed(this::connect, 5000);
            return;
        }

        Request request = new Request.Builder()
                .url(WS_URL)
                .addHeader("Cookie", cookie)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "WS подключён");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleIncoming(text);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, @Nullable Response response) {
                Log.w(TAG, "WS обрыв, переподключение через 5с: " + t.getMessage());
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (stopping) return;
        retryHandler.postDelayed(this::connect, 5000);
    }

    private void handleIncoming(String rawJson) {
        try {
            JSONObject msg = new JSONObject(rawJson);
            String type = msg.optString("type", "");
            switch (type) {
                case "call:invite":
                    showCallNotification(msg);
                    break;
                case "chat:message":
                    showMessageNotification(msg);
                    break;
                default:
                    break;
            }
        } catch (JSONException e) {
            Log.w(TAG, "Не удалось разобрать WS-сообщение: " + e.getMessage());
        }
    }

    private void showCallNotification(JSONObject msg) {
        JSONObject from = msg.optJSONObject("from");
        String callerName = from != null
                ? from.optString("displayName", from.optString("username", "Кто-то"))
                : "Кто-то";

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, notifIdCounter.incrementAndGet(), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_CALLS)
                .setContentTitle("Входящий звонок")
                .setContentText(callerName)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(notifIdCounter.incrementAndGet(), builder.build());
    }

    private void showMessageNotification(JSONObject msg) {
        JSONObject from = msg.optJSONObject("from");
        String senderName = from != null
                ? from.optString("displayName", from.optString("username", "Кто-то"))
                : "Кто-то";
        String chatName = msg.optString("chatName", null);
        String preview = msg.optString("preview", "");

        String title = (chatName != null && !chatName.isEmpty() && !"null".equals(chatName))
                ? chatName
                : senderName;
        String text = (chatName != null && !chatName.isEmpty() && !"null".equals(chatName))
                ? senderName + ": " + preview
                : preview;

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openApp.putExtra("openChatId", msg.optString("chatId", null));
        openApp.putExtra("openChatType", msg.optString("chatType", "private"));
        openApp.putExtra("openChatCompanion", msg.optString("companion", null));
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, notifIdCounter.incrementAndGet(), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManager nm = getSystemService(NotificationManager.class);
        String chatId = msg.optString("chatId", "default");
        nm.notify(chatId.hashCode(), builder.build());
    }

    @Override
    public void onDestroy() {
        stopping = true;
        retryHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) webSocket.close(1000, "service_stopped");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
