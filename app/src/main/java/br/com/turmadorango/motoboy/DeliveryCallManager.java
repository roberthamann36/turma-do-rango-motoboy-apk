package br.com.turmadorango.motoboy;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
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
    private static final String REALTIME_URL = "https://turmadorango.com.br/includes/motoboy/realtime_api.php";
    static final String CHANNEL_CALLS = "tdr_motoboy_calls_v3";
    private static final long POLL_MS = 1800L;
    private static final long LOGGED_OUT_MS = 7000L;
    private static DeliveryCallManager instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean polling = false;
    private int currentCallId = 0;
    private String currentToken = "";
    private Ringtone ringtone;
    private Vibrator vibrator;
    private Runnable stopSoundRunnable;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running || polling) return;
            polling = true;
            new Thread(() -> {
                long next = POLL_MS;
                try { next = pollOnce(); }
                catch (Exception ignored) { next = 5000L; }
                finally {
                    polling = false;
                    if (running) handler.postDelayed(pollRunnable, Math.max(1200L, next));
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
        instance.handler.postDelayed(instance.pollRunnable, 500L);
    }

    public static synchronized void kick(Context context) {
        start(context);
        if (instance != null) {
            instance.handler.removeCallbacks(instance.pollRunnable);
            instance.handler.postDelayed(instance.pollRunnable, 150L);
        }
    }

    public static synchronized void onRealtimeOffer(Context context, JSONObject offer) {
        if (instance == null) instance = new DeliveryCallManager(context);
        if (offer != null && offer.optInt("id", 0) > 0 && !offer.optString("token", "").isEmpty()) {
            instance.showOffer(offer);
        } else {
            instance.clearCurrentNotification();
        }
    }

    static synchronized void stopCurrentAlert(Context context, int callId) {
        if (instance == null) instance = new DeliveryCallManager(context);
        if (callId <= 0 || instance.currentCallId == callId) instance.clearCurrentNotification();
    }

    private long pollOnce() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(REALTIME_URL + "?t=" + System.currentTimeMillis());
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
            if (code < 200 || code >= 300) return 5000L;

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
            JSONObject data = new JSONObject(body.toString());
            if (!data.optBoolean("ok", false) || !data.optBoolean("logged", false)) {
                clearCurrentNotification();
                return LOGGED_OUT_MS;
            }
            JSONObject offer = data.optJSONObject("offer");
            if (offer == null || offer.optInt("id", 0) <= 0) {
                clearCurrentNotification();
                return Math.max(1200L, data.optLong("poll_ms", POLL_MS));
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
        NotificationChannel calls = new NotificationChannel(CHANNEL_CALLS, "Chamadas de entrega", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Chamadas urgentes de novas entregas disponíveis para aceitar.");
        calls.enableVibration(true);
        calls.setVibrationPattern(new long[]{0, 650, 250, 650, 250, 900});
        calls.enableLights(true);
        calls.setLightColor(0xFFFFC400);
        calls.setShowBadge(true);
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            calls.setSound(sound, attrs);
        } catch (Exception ignored) {}
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

        Intent open = buildCallScreenIntent(offer);
        PendingIntent openPi = PendingIntent.getActivity(context, 41000 + (callId % 8000), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent accept = new Intent(context, DeliveryCallReceiver.class);
        accept.setAction(DeliveryCallReceiver.ACTION_ACCEPT);
        accept.putExtra("call_id", callId);
        accept.putExtra("pedido_id", pedidoId);
        accept.putExtra("token", token);
        PendingIntent acceptPi = PendingIntent.getBroadcast(context, 51000 + (callId % 8000), accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent decline = new Intent(context, DeliveryCallReceiver.class);
        decline.setAction(DeliveryCallReceiver.ACTION_DECLINE);
        decline.putExtra("call_id", callId);
        decline.putExtra("pedido_id", pedidoId);
        decline.putExtra("token", token);
        PendingIntent declinePi = PendingIntent.getBroadcast(context, 61000 + (callId % 8000), decline,
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

        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(notificationId(callId), b.build());
        startAudibleAlert(seconds);

        if (isAppForeground()) {
            try { context.startActivity(open); } catch (Exception ignored) {}
        }
    }

    private void startAudibleAlert(int seconds) {
        stopAudibleAlert();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(context, uri);
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.setLooping(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                ringtone.play();
            }
        } catch (Exception ignored) {}

        try {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = new long[]{0, 700, 250, 700, 250, 1000};
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                else vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}

        stopSoundRunnable = this::stopAudibleAlert;
        handler.postDelayed(stopSoundRunnable, Math.max(1000L, seconds * 1000L + 500L));
    }

    private void stopAudibleAlert() {
        if (stopSoundRunnable != null) {
            handler.removeCallbacks(stopSoundRunnable);
            stopSoundRunnable = null;
        }
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Exception ignored) {}
        ringtone = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        vibrator = null;
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
            String pkg = context.getPackageName();
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (pkg.equals(p.processName) && p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void clearCurrentNotification() {
        stopAudibleAlert();
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
        if (instance == null) instance = new DeliveryCallManager(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 69001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_CALLS)
                : new Notification.Builder(context);
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
