package br.com.turmadorango.motoboy;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class DeliveryCallManager {
    static final String CALL_URL = "https://turmadorango.com.br/includes/motoboy/chamado_api.php";
    static final String CHANNEL_CALLS = "tdr_motoboy_calls_v4";
    private static final long POLL_MS = 1200L;
    private static DeliveryCallManager instance;
    private static MediaPlayer callPlayer;
    private static Vibrator callVibrator;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean polling;
    private int currentCallId;
    private String currentToken = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running || polling) return;
            polling = true;
            new Thread(() -> {
                long next = POLL_MS;
                try { next = pollOnce(); }
                catch (Exception ignored) { next = 3000L; }
                finally {
                    polling = false;
                    if (running) handler.postDelayed(pollRunnable, Math.max(900L, next));
                }
            }, "tdr-call-monitor").start();
        }
    };

    private DeliveryCallManager(Context c) {
        context = c.getApplicationContext();
        createChannel();
    }

    public static synchronized void start(Context c) {
        if (instance == null) instance = new DeliveryCallManager(c);
        instance.running = true;
        instance.handler.removeCallbacks(instance.pollRunnable);
        instance.handler.postDelayed(instance.pollRunnable, 300L);
    }

    public static synchronized void kick(Context c) {
        start(c);
        if (instance != null) {
            instance.handler.removeCallbacks(instance.pollRunnable);
            instance.handler.postDelayed(instance.pollRunnable, 80L);
        }
    }

    private String appToken() {
        return context.getSharedPreferences("tdr_app_auth", Context.MODE_PRIVATE)
                .getString("app_token", "").trim();
    }

    private long pollOnce() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CALL_URL + "?action=current&t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(6500);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);

            String token = appToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("X-TDR-App-Token", token);
            } else {
                String cookie = CookieManager.getInstance().getCookie("https://turmadorango.com.br/includes/motoboy/");
                if (cookie != null && !cookie.trim().isEmpty()) conn.setRequestProperty("Cookie", cookie);
            }

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                clearCurrentNotification();
                return token.isEmpty() ? 2500L : 4000L;
            }
            if (code < 200 || code >= 300) return 3000L;

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
            JSONObject data = new JSONObject(body.toString());
            JSONObject offer = data.optJSONObject("offer");
            if (!data.optBoolean("ok", false) || offer == null || offer.optInt("id", 0) <= 0) {
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
        NotificationChannel ch = new NotificationChannel(CHANNEL_CALLS, "Chamadas de entrega", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Chamadas urgentes de novas entregas disponíveis.");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 900});
        ch.enableLights(true);
        ch.setLightColor(0xFFFFC400);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(sound, attrs);
        } catch (Exception ignored) {}
        nm.createNotificationChannel(ch);
    }

    private void showOffer(JSONObject offer) {
        int callId = offer.optInt("id", 0);
        String offerToken = offer.optString("token", "");
        if (callId <= 0 || offerToken.isEmpty()) return;
        if (callId == currentCallId && offerToken.equals(currentToken)) return;

        clearCurrentNotification();
        currentCallId = callId;
        currentToken = offerToken;

        String street = offer.optString("rua", "Rua não informada");
        String district = offer.optString("bairro", "Bairro não informado");
        int seconds = Math.max(1, offer.optInt("seconds_left", 20));
        int pedidoId = offer.optInt("pedido_id", 0);

        Intent open = buildCallScreenIntent(offer);
        PendingIntent openPi = PendingIntent.getActivity(context, 41000 + (callId % 8000), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent accept = new Intent(context, DeliveryCallReceiver.class);
        accept.setAction(DeliveryCallReceiver.ACTION_ACCEPT);
        accept.putExtra("call_id", callId);
        accept.putExtra("pedido_id", pedidoId);
        accept.putExtra("token", offerToken);
        PendingIntent acceptPi = PendingIntent.getBroadcast(context, 51000 + (callId % 8000), accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent decline = new Intent(context, DeliveryCallReceiver.class);
        decline.setAction(DeliveryCallReceiver.ACTION_DECLINE);
        decline.putExtra("call_id", callId);
        decline.putExtra("pedido_id", pedidoId);
        decline.putExtra("token", offerToken);
        PendingIntent declinePi = PendingIntent.getBroadcast(context, 61000 + (callId % 8000), decline,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String shortText = street + " • " + district;
        String longText = "📍 " + street + "\n🏘 " + district + "\n\n" + seconds + " segundos para aceitar.";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_CALLS)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("🏍 NOVA CHAMADA DE ENTREGA")
                .setContentText(shortText)
                .setStyle(new Notification.BigTextStyle().bigText(longText))
                .setContentIntent(openPi)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFFFFC400)
                .setWhen(System.currentTimeMillis())
                .setTimeoutAfter(seconds * 1000L)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "PASSAR", declinePi).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send, "ACEITAR", acceptPi).build());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MAX);
            b.setDefaults(Notification.DEFAULT_ALL);
        }

        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(notificationId(callId), b.build());
        startCallAlert(context);

        final int expectedCall = callId;
        handler.postDelayed(() -> {
            if (currentCallId == expectedCall) clearCurrentNotification();
        }, seconds * 1000L + 500L);

        if (isAppForeground()) {
            try { context.startActivity(open); } catch (Exception ignored) {}
        }
    }

    private Intent buildCallScreenIntent(JSONObject offer) {
        Intent i = new Intent(context, DeliveryCallActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("call_id", offer.optInt("id", 0));
        i.putExtra("pedido_id", offer.optInt("pedido_id", 0));
        i.putExtra("token", offer.optString("token", ""));
        i.putExtra("rua", offer.optString("rua", "Rua não informada"));
        i.putExtra("bairro", offer.optString("bairro", "Bairro não informado"));
        i.putExtra("seconds_left", Math.max(1, offer.optInt("seconds_left", 20)));
        return i;
    }

    private boolean isAppForeground() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (context.getPackageName().equals(p.processName)
                        && p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void clearCurrentNotification() {
        if (currentCallId > 0) {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(notificationId(currentCallId));
        }
        currentCallId = 0;
        currentToken = "";
        stopCallAlert();
    }

    public static synchronized void stopCurrentAlert(Context c, int callId) {
        stopCallAlert();
        try {
            ((NotificationManager)c.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(notificationId(callId));
        } catch (Exception ignored) {}
        if (instance != null && (callId <= 0 || instance.currentCallId == callId)) {
            instance.currentCallId = 0;
            instance.currentToken = "";
        }
    }

    private static synchronized void startCallAlert(Context c) {
        stopCallAlert();
        Context app = c.getApplicationContext();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            MediaPlayer mp = new MediaPlayer();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mp.setAudioAttributes(attrs);
            mp.setDataSource(app, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            callPlayer = mp;
        } catch (Exception ignored) {}
        try {
            callVibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = new long[]{0, 650, 250, 650, 250, 1000};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                callVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                callVibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}
    }

    private static synchronized void stopCallAlert() {
        try {
            if (callPlayer != null) {
                if (callPlayer.isPlaying()) callPlayer.stop();
                callPlayer.release();
            }
        } catch (Exception ignored) {}
        callPlayer = null;
        try { if (callVibrator != null) callVibrator.cancel(); } catch (Exception ignored) {}
        callVibrator = null;
    }

    static int notificationId(int callId) {
        return 42000 + (Math.abs(callId) % 15000);
    }

    static void showResultNotification(Context c, String title, String text) {
        NotificationManager nm = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 69001, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(c, CHANNEL_CALLS)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        nm.notify(69001, b.build());
    }
}
