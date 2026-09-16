package br.com.turmadorango.motoboy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class DeliveryCallManager {
    static final String CALL_URL = "https://turmadorango.com.br/includes/motoboy/chamado_api.php";
    static final String CHANNEL_CALLS = "tdr_motoboy_calls_v1";
    private static final long POLL_MS = 2500L;
    private static final long LOGGED_OUT_MS = 8000L;
    private static DeliveryCallManager instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean polling = false;
    private int currentCallId = 0;
    private String currentToken = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running || polling) return;
            polling = true;
            new Thread(() -> {
                long next = POLL_MS;
                try { next = pollOnce(); }
                catch (Exception ignored) { next = 6000L; }
                finally {
                    polling = false;
                    if (running) handler.postDelayed(pollRunnable, Math.max(1500L, next));
                }
            }, "tdr-delivery-call-poll").start();
        }
    };

    private DeliveryCallManager(Context context) {
        this.context = context.getApplicationContext();
        createChannel();
    }

    public static synchronized void start(Context context) {
        if (instance == null) instance = new DeliveryCallManager(context);
        instance.running = true;
        instance.handler.removeCallbacks(instance.pollRunnable);
        instance.handler.postDelayed(instance.pollRunnable, 900L);
    }

    public static synchronized void kick(Context context) {
        start(context);
        if (instance != null) {
            instance.handler.removeCallbacks(instance.pollRunnable);
            instance.handler.postDelayed(instance.pollRunnable, 250L);
        }
    }

    private long pollOnce() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CALL_URL + "?action=current&t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);
            String cookie = CookieManager.getInstance().getCookie("https://turmadorango.com.br/");
            if (cookie != null && !cookie.trim().isEmpty()) conn.setRequestProperty("Cookie", cookie);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                clearCurrentNotification();
                return LOGGED_OUT_MS;
            }
            if (code < 200 || code >= 300) return 6000L;

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
            JSONObject data = new JSONObject(body.toString());
            JSONObject offer = data.optJSONObject("offer");
            if (offer == null || offer.optInt("id", 0) <= 0) {
                clearCurrentNotification();
                return POLL_MS;
            }
            showOffer(offer);
            return POLL_MS;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel calls = new NotificationChannel(
                CHANNEL_CALLS,
                "Chamadas de entrega",
                NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Chamadas de novas entregas disponíveis para aceitar.");
        calls.enableVibration(true);
        calls.enableLights(true);
        calls.setShowBadge(true);
        nm.createNotificationChannel(calls);
    }

    private void showOffer(JSONObject offer) {
        int callId = offer.optInt("id", 0);
        String token = offer.optString("token", "");
        if (callId <= 0 || token.isEmpty()) return;
        if (callId == currentCallId && token.equals(currentToken)) return;

        clearCurrentNotification();
        currentCallId = callId;
        currentToken = token;

        String street = offer.optString("rua", "Rua não informada");
        String district = offer.optString("bairro", "Bairro não informado");
        int seconds = Math.max(1, offer.optInt("seconds_left", 20));
        int pedidoId = offer.optInt("pedido_id", 0);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(
                context,
                41000 + (callId % 8000),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent accept = new Intent(context, DeliveryCallReceiver.class);
        accept.setAction(DeliveryCallReceiver.ACTION_ACCEPT);
        accept.putExtra("call_id", callId);
        accept.putExtra("pedido_id", pedidoId);
        accept.putExtra("token", token);
        PendingIntent acceptPi = PendingIntent.getBroadcast(
                context,
                51000 + (callId % 8000),
                accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent decline = new Intent(context, DeliveryCallReceiver.class);
        decline.setAction(DeliveryCallReceiver.ACTION_DECLINE);
        decline.putExtra("call_id", callId);
        decline.putExtra("pedido_id", pedidoId);
        decline.putExtra("token", token);
        PendingIntent declinePi = PendingIntent.getBroadcast(
                context,
                61000 + (callId % 8000),
                decline,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String shortText = street + " • " + district;
        String longText = "📍 " + street + "\n🏘 " + district + "\n\nVocê tem " + seconds + " segundos para aceitar antes da chamada ir para o próximo motoboy.";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_CALLS)
                : new Notification.Builder(context);

        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("🏍 NOVA CHAMADA DE ENTREGA")
                .setContentText(shortText)
                .setStyle(new Notification.BigTextStyle().bigText(longText))
                .setContentIntent(openPi)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_EVENT)
                .setWhen(System.currentTimeMillis())
                .setTimeoutAfter(seconds * 1000L)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "PASSAR", declinePi).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send, "ACEITAR", acceptPi).build());

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MAX);
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(notificationId(callId), b.build());
    }

    private void clearCurrentNotification() {
        if (currentCallId > 0) {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(notificationId(currentCallId));
        }
        currentCallId = 0;
        currentToken = "";
    }

    static int notificationId(int callId) {
        return 42000 + (Math.abs(callId) % 15000);
    }

    static void showResultNotification(Context context, String title, String text) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 69001, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_CALLS)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        nm.notify(69001, b.build());
    }
}
