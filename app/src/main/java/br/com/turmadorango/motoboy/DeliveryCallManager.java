package br.com.turmadorango.motoboy;

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

import org.json.JSONObject;

/**
 * Controlador ÚNICO da apresentação das chamadas.
 * O polling fica exclusivamente no EmbeddedCallMonitor; esta classe apenas
 * apresenta/encerra a oferta. Isso evita duas fontes tocando ao mesmo tempo.
 */
public final class DeliveryCallManager {
    static final String CALL_URL = "https://turmadorango.com.br/includes/motoboy/chamado_api.php";
    static final String CHANNEL_CALLS = "tdr_motoboy_calls_v7_fullscreen";

    private static DeliveryCallManager instance;
    private static MediaPlayer callPlayer;
    private static Vibrator callVibrator;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentCallId;
    private String currentToken = "";

    private DeliveryCallManager(Context c) {
        context = c.getApplicationContext();
        createChannel();
    }

    /** Inicializa apenas o controlador. Não faz polling. */
    public static synchronized void start(Context c) {
        if (instance == null) instance = new DeliveryCallManager(c);
    }

    /** Mantido por compatibilidade; agora apenas garante a inicialização. */
    public static synchronized void kick(Context c) {
        start(c);
    }

    /** Chamado pelo único monitor quando o servidor realmente entrega uma oferta. */
    public static synchronized void presentOffer(Context c, JSONObject offer) {
        start(c);
        if (instance == null || offer == null) return;
        JSONObject copy;
        try { copy = new JSONObject(offer.toString()); }
        catch (Exception e) { copy = offer; }
        final JSONObject finalOffer = copy;
        instance.handler.post(() -> instance.showOffer(finalOffer));
    }

    /** Encerra a tela/toque se o servidor informar que não existe mais oferta. */
    public static synchronized void clearCurrent(Context c) {
        start(c);
        if (instance != null) instance.handler.post(instance::clearCurrentNotification);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_CALLS,
                "Chamadas de entrega",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Chamadas urgentes de novas entregas disponíveis.");

        // Som e vibração são controlados manualmente para existir UMA única fonte.
        // Um canal com som + MediaPlayer causava sensação de dois toques simultâneos.
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.enableLights(true);
        ch.setLightColor(0xFFFFC400);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
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

        context.getSharedPreferences("tdr_app_auth", Context.MODE_PRIVATE)
                .edit()
                .putInt("last_offer_id", callId)
                .putLong("last_offer_at", System.currentTimeMillis())
                .apply();

        String street = offer.optString("rua", "Rua não informada");
        String district = offer.optString("bairro", "Bairro não informado");
        int seconds = Math.max(1, offer.optInt("seconds_left", 20));
        int pedidoId = offer.optInt("pedido_id", 0);

        Intent open = buildCallScreenIntent(offer);
        PendingIntent openPi = PendingIntent.getActivity(
                context,
                41000 + (callId % 8000),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent accept = new Intent(context, DeliveryCallReceiver.class);
        accept.setAction(DeliveryCallReceiver.ACTION_ACCEPT);
        accept.putExtra("call_id", callId);
        accept.putExtra("pedido_id", pedidoId);
        accept.putExtra("token", offerToken);
        PendingIntent acceptPi = PendingIntent.getBroadcast(
                context,
                51000 + (callId % 8000),
                accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent decline = new Intent(context, DeliveryCallReceiver.class);
        decline.setAction(DeliveryCallReceiver.ACTION_DECLINE);
        decline.putExtra("call_id", callId);
        decline.putExtra("pedido_id", pedidoId);
        decline.putExtra("token", offerToken);
        PendingIntent declinePi = PendingIntent.getBroadcast(
                context,
                61000 + (callId % 8000),
                decline,
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
                .setFullScreenIntent(openPi, true)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFFFFC400)
                .setWhen(System.currentTimeMillis())
                .setTimeoutAfter(seconds * 1000L)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "PASSAR",
                        declinePi).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_send,
                        "ACEITAR",
                        acceptPi).build());

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MAX);
            // Não usar DEFAULT_SOUND/DEFAULT_VIBRATE: o alerta manual já faz isso.
        }

        // Uma única fonte de toque/vibração.
        startCallAlert(context);

        try {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(notificationId(callId), b.build());
        } catch (Exception ignored) {}

        // Tenta abrir imediatamente sobre o que estiver na tela. Em Androids que
        // bloqueiam abertura direta em background, o fullScreenIntent acima assume.
        try { context.startActivity(open); } catch (Exception ignored) {}

        final int expectedCall = callId;
        handler.postDelayed(() -> {
            if (currentCallId == expectedCall) clearCurrentNotification();
        }, seconds * 1000L + 500L);
    }

    private Intent buildCallScreenIntent(JSONObject offer) {
        Intent i = new Intent(context, DeliveryCallActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        i.putExtra("call_id", offer.optInt("id", 0));
        i.putExtra("pedido_id", offer.optInt("pedido_id", 0));
        i.putExtra("token", offer.optString("token", ""));
        i.putExtra("rua", offer.optString("rua", "Rua não informada"));
        i.putExtra("bairro", offer.optString("bairro", "Bairro não informado"));
        i.putExtra("seconds_left", Math.max(1, offer.optInt("seconds_left", 20)));
        return i;
    }

    private void clearCurrentNotification() {
        if (currentCallId > 0) {
            try {
                ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                        .cancel(notificationId(currentCallId));
            } catch (Exception ignored) {}
        }
        currentCallId = 0;
        currentToken = "";
        stopCallAlert();
    }

    public static synchronized void stopCurrentAlert(Context c, int callId) {
        stopCallAlert();
        try {
            ((NotificationManager) c.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE))
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

        try {
            if (callVibrator != null) callVibrator.cancel();
        } catch (Exception ignored) {}
        callVibrator = null;
    }

    static int notificationId(int callId) {
        return 42000 + (Math.abs(callId) % 15000);
    }

    static void showResultNotification(Context c, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                c,
                69001,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
        }
        try { nm.notify(69001, b.build()); } catch (Exception ignored) {}
    }

    private DeliveryCallManager() { throw new AssertionError(); }
}
