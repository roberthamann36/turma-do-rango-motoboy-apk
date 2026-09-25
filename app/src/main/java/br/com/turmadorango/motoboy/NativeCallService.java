package br.com.turmadorango.motoboy;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Serviço foreground exclusivo para as chamadas de entrega. */
public class NativeCallService extends Service {
    private static final String POLL_URL = "https://turmadorango.com.br/includes/motoboy/app_call_poll.php";
    private static final String CHANNEL_MONITOR = "tdr_call_monitor_v1";
    private static final String CHANNEL_CALL = "tdr_call_native_v1";
    private static final int FOREGROUND_ID = 6010;
    private static final long DEFAULT_POLL_MS = 800L;

    private static NativeCallService instance;
    private Handler handler;
    private volatile boolean working;
    private volatile boolean destroyed;
    private int currentCallId;
    private String currentOfferToken = "";
    private MediaPlayer player;
    private Vibrator vibrator;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (destroyed || working) return;
            working = true;
            new Thread(() -> {
                long next = DEFAULT_POLL_MS;
                try { next = pollOnce(); }
                catch (Exception ignored) { next = 1800L; }
                finally {
                    working = false;
                    if (!destroyed && handler != null) {
                        long delay = Math.max(600L, next);
                        handler.postDelayed(pollRunnable, delay);
                    }
                }
            }, "tdr-native-call-service").start();
        }
    };

    public static void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, NativeCallService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {}
    }

    public static void stopCurrentAlert(Context context, int callId) {
        NativeCallService s = instance;
        if (s != null) s.stopAlertFor(callId);
        try {
            ((NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(DeliveryCallManager.notificationId(callId));
        } catch (Exception ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_ID, monitorNotification("Chamadas ativas • aguardando entregas"));
        destroyed = false;
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, 120L);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        stopAlertFor(0);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR, "Monitor de chamadas", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Mantém o motoboy conectado para receber chamadas de entrega.");
        monitor.setShowBadge(false);
        nm.createNotificationChannel(monitor);

        NotificationChannel call = new NotificationChannel(
                CHANNEL_CALL, "Chamadas de entrega", NotificationManager.IMPORTANCE_HIGH);
        call.setDescription("Avisos urgentes de novas entregas disponíveis.");
        call.enableVibration(true);
        call.setVibrationPattern(new long[]{0,650,220,650,220,950});
        call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            call.setSound(sound, attrs);
        } catch (Exception ignored) {}
        nm.createNotificationChannel(call);
    }

    private Notification monitorNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 6010, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_MONITOR)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("UNIBOY ENTREGAS • Chamadas")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private void updateMonitor(String text) {
        try {
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(FOREGROUND_ID, monitorNotification(text));
        } catch (Exception ignored) {}
    }

    private String appToken() {
        return getSharedPreferences("tdr_app_auth", MODE_PRIVATE)
                .getString("app_token", "").trim();
    }

    private long pollOnce() throws Exception {
        String appToken = appToken();
        if (appToken.isEmpty()) {
            NativeAuthSync.syncNow(this);
            updateMonitor("Conectando o recebimento de chamadas...");
            return 1200L;
        }

        HttpURLConnection conn = null;
        try {
            String endpoint = POLL_URL + "?app_token=" + URLEncoder.encode(appToken, "UTF-8")
                    + "&app_version=" + URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")
                    + "&t=" + System.currentTimeMillis();
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-TDR-App-Token", appToken);
            conn.setRequestProperty("X-TDR-App-Version", BuildConfig.VERSION_NAME);
            conn.setRequestProperty("User-Agent", "UniBoyEntregas/" + BuildConfig.VERSION_NAME);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                stopAlertFor(0);
                NativeAuthSync.invalidateAndSync(this);
                updateMonitor("Reconectando chamadas...");
                return 1200L;
            }
            if (code < 200 || code >= 300) {
                updateMonitor("Falta de comunicação • tentando novamente");
                return 1800L;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
            JSONObject data = new JSONObject(body.toString());
            if (!data.optBoolean("ok", false) || !data.optBoolean("logged", false)) {
                NativeAuthSync.invalidateAndSync(this);
                updateMonitor("Reconectando chamadas...");
                return 1200L;
            }

            updateMonitor("Chamadas ativas • conectado");
            JSONObject offer = data.optJSONObject("offer");
            if (offer == null || offer.optInt("id", 0) <= 0 || offer.optString("token", "").isEmpty()) {
                if (currentCallId > 0) stopAlertFor(currentCallId);
                return Math.max(600L, data.optLong("poll_ms", DEFAULT_POLL_MS));
            }
            showOffer(offer);
            return Math.max(600L, data.optLong("poll_ms", DEFAULT_POLL_MS));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void showOffer(JSONObject offer) {
        int callId = offer.optInt("id", 0);
        String offerToken = offer.optString("token", "");
        if (callId <= 0 || offerToken.isEmpty()) return;
        if (callId == currentCallId && offerToken.equals(currentOfferToken)) return;

        stopAlertFor(0);
        currentCallId = callId;
        currentOfferToken = offerToken;

        int pedidoId = offer.optInt("pedido_id", 0);
        String rua = offer.optString("rua", "Rua não informada");
        String bairro = offer.optString("bairro", "Bairro não informado");
        int seconds = Math.max(1, offer.optInt("seconds_left", 20));

        Intent open = new Intent(this, DeliveryCallActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("call_id", callId);
        open.putExtra("pedido_id", pedidoId);
        open.putExtra("token", offerToken);
        open.putExtra("rua", rua);
        open.putExtra("bairro", bairro);
        open.putExtra("seconds_left", seconds);
        PendingIntent openPi = PendingIntent.getActivity(this, 41000 + (callId % 8000), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent accept = new Intent(this, DeliveryCallReceiver.class);
        accept.setAction(DeliveryCallReceiver.ACTION_ACCEPT);
        accept.putExtra("call_id", callId);
        accept.putExtra("pedido_id", pedidoId);
        accept.putExtra("token", offerToken);
        PendingIntent acceptPi = PendingIntent.getBroadcast(this, 51000 + (callId % 8000), accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent decline = new Intent(this, DeliveryCallReceiver.class);
        decline.setAction(DeliveryCallReceiver.ACTION_DECLINE);
        decline.putExtra("call_id", callId);
        decline.putExtra("pedido_id", pedidoId);
        decline.putExtra("token", offerToken);
        PendingIntent declinePi = PendingIntent.getBroadcast(this, 61000 + (callId % 8000), decline,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String shortText = rua + " • " + bairro;
        String longText = "📍 " + rua + "\n🏘 " + bairro + "\n\n" + seconds + " segundos para aceitar.";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_CALL)
                : new Notification.Builder(this);
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
                .setWhen(System.currentTimeMillis())
                .setTimeoutAfter(seconds * 1000L)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "PASSAR", declinePi).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send, "ACEITAR", acceptPi).build());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MAX);
            b.setDefaults(Notification.DEFAULT_ALL);
        }

        startAlert();
        try {
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(DeliveryCallManager.notificationId(callId), b.build());
        } catch (Exception ignored) {}

        updateMonitor("CHAMADA RECEBIDA • pedido #" + pedidoId);
        if (isAppForeground()) {
            try { startActivity(open); } catch (Exception ignored) {}
        }

        final int expected = callId;
        handler.postDelayed(() -> {
            if (currentCallId == expected) stopAlertFor(expected);
        }, seconds * 1000L + 700L);
    }

    private boolean isAppForeground() {
        try {
            ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (getPackageName().equals(p.processName)
                        && p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private synchronized void startAlert() {
        stopMediaOnly();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            player.setAudioAttributes(attrs);
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) {}
        try {
            vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = new long[]{0,700,220,700,220,1000};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            else vibrator.vibrate(pattern, 0);
        } catch (Exception ignored) {}
    }

    private synchronized void stopMediaOnly() {
        try {
            if (player != null) {
                if (player.isPlaying()) player.stop();
                player.release();
            }
        } catch (Exception ignored) {}
        player = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        vibrator = null;
    }

    private synchronized void stopAlertFor(int callId) {
        if (callId > 0 && currentCallId > 0 && callId != currentCallId) return;
        int old = currentCallId;
        stopMediaOnly();
        currentCallId = 0;
        currentOfferToken = "";
        if (old > 0) {
            try {
                ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE))
                        .cancel(DeliveryCallManager.notificationId(old));
            } catch (Exception ignored) {}
        }
    }
}
