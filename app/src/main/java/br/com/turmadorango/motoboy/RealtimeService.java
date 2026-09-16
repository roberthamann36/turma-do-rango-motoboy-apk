package br.com.turmadorango.motoboy;

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
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class RealtimeService extends Service {
    public static final String ACTION_CHANGED = "br.com.turmadorango.motoboy.REALTIME_CHANGED";
    public static final String ACTION_STOP = "br.com.turmadorango.motoboy.STOP_REALTIME";

    private static final String REALTIME_URL = "https://turmadorango.com.br/includes/motoboy/realtime_api.php";
    private static final String CHANNEL_SERVICE = "tdr_motoboy_service";
    private static final String CHANNEL_ALERTS = "tdr_motoboy_alerts";
    private static final int SERVICE_NOTIFICATION_ID = 6001;
    private static final long DEFAULT_POLL_MS = 4000L;
    private static final long LOGGED_OUT_POLL_MS = 10000L;

    private Handler handler;
    private SharedPreferences prefs;
    private volatile boolean working = false;
    private volatile boolean destroyed = false;
    private long nextPollMs = DEFAULT_POLL_MS;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (working) {
                schedule(nextPollMs);
                return;
            }
            working = true;
            new Thread(() -> {
                try { pollServer(); }
                finally {
                    working = false;
                    schedule(nextPollMs);
                }
            }, "tdr-realtime-poll").start();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("tdr_realtime", MODE_PRIVATE);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("service_enabled", false).apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        prefs.edit().putBoolean("service_enabled", true).apply();
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification());
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, 500L);
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void schedule(long delay) {
        if (destroyed || handler == null) return;
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, Math.max(1500L, delay));
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel svc = new NotificationChannel(
                CHANNEL_SERVICE,
                "Monitoramento de entregas",
                NotificationManager.IMPORTANCE_LOW);
        svc.setDescription("Mantém o aplicativo do motoboy conectado para receber atualizações.");
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS,
                "Entregas e alterações",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Avisos de novas entregas, mudanças de status e mensagens.");
        alerts.enableVibration(true);
        alerts.setShowBadge(true);
        nm.createNotificationChannel(alerts);
    }

    private Notification buildServiceNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 6001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RealtimeService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 6002, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_SERVICE)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Turma do Rango • Motoboy conectado")
                .setContentText("Monitorando entregas e alterações em tempo real")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Encerrar monitoramento",
                        stopPi).build());

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private void updateServiceNotification(String text) {
        try {
            NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            Intent open=new Intent(this,MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi=PendingIntent.getActivity(this,6001,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?new Notification.Builder(this,CHANNEL_SERVICE):new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_launcher)
             .setContentTitle("Turma do Rango • Motoboy conectado")
             .setContentText(text)
             .setContentIntent(pi)
             .setOngoing(true)
             .setOnlyAlertOnce(true)
             .setCategory(Notification.CATEGORY_SERVICE);
            if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O)b.setPriority(Notification.PRIORITY_LOW);
            nm.notify(SERVICE_NOTIFICATION_ID,b.build());
        } catch(Exception ignored) {}
    }

    private void pollServer() {
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
                nextPollMs = LOGGED_OUT_POLL_MS;
                prefs.edit().putBoolean("baseline_ready", false).apply();
                updateServiceNotification("Aguardando login do motoboy");
                return;
            }
            if (code < 200 || code >= 300) {
                nextPollMs = 8000L;
                return;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }

            JSONObject data = new JSONObject(body.toString());
            if (!data.optBoolean("ok", false) || !data.optBoolean("logged", false)) {
                nextPollMs = LOGGED_OUT_POLL_MS;
                prefs.edit().putBoolean("baseline_ready", false).apply();
                updateServiceNotification("Aguardando login do motoboy");
                return;
            }

            nextPollMs = Math.max(2500L, data.optLong("poll_ms", DEFAULT_POLL_MS));
            updateServiceNotification("Monitorando entregas e alterações em tempo real");
            int motoboyId = data.optInt("motoboy_id", 0);
            int previousMotoboy = prefs.getInt("motoboy_id", 0);
            if (previousMotoboy > 0 && motoboyId > 0 && previousMotoboy != motoboyId) {
                boolean enabled = prefs.getBoolean("service_enabled", true);
                prefs.edit().clear().putBoolean("service_enabled", enabled).putInt("motoboy_id", motoboyId).apply();
            } else if (motoboyId > 0 && previousMotoboy == 0) {
                prefs.edit().putInt("motoboy_id", motoboyId).apply();
            }

            JSONArray orders = data.optJSONArray("orders");
            if (orders == null) orders = new JSONArray();

            boolean baselineReady = prefs.getBoolean("baseline_ready", false);
            boolean anyChanged = false;
            Set<String> currentIds = new HashSet<>();
            SharedPreferences.Editor editor = prefs.edit();

            for (int i = 0; i < orders.length(); i++) {
                JSONObject order = orders.optJSONObject(i);
                if (order == null) continue;
                int id = order.optInt("id", 0);
                if (id <= 0) continue;
                String idStr = String.valueOf(id);
                currentIds.add(idStr);

                String key = "order_" + id;
                String currentState = order.optString("state", "");
                String previousRaw = prefs.getString(key, null);
                JSONObject previous = null;
                if (previousRaw != null) {
                    try { previous = new JSONObject(previousRaw); } catch (Exception ignored) {}
                }

                if (!baselineReady) {
                    editor.putString(key, order.toString());
                    continue;
                }

                if (previous == null) {
                    notifyOrder(order, "new");
                    anyChanged = true;
                } else {
                    String previousState = previous.optString("state", "");
                    if (!currentState.equals(previousState)) {
                        notifyOrder(order, detectChange(previous, order));
                        anyChanged = true;
                    }
                }
                editor.putString(key, order.toString());
            }

            if (!baselineReady) editor.putBoolean("baseline_ready", true);
            editor.putStringSet("current_ids", currentIds);
            editor.putString("last_revision", data.optString("revision", ""));
            editor.apply();

            if (anyChanged) {
                Intent changed = new Intent(ACTION_CHANGED);
                changed.setPackage(getPackageName());
                changed.putExtra("revision", data.optString("revision", ""));
                sendBroadcast(changed);
            }
        } catch (Exception ignored) {
            nextPollMs = 8000L;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String detectChange(JSONObject old, JSONObject now) {
        int oldChat = old.optInt("chat_last_id", 0);
        int newChat = now.optInt("chat_last_id", 0);
        if (newChat > oldChat && newChat > 0) return "chat";

        String oldDecision = old.optString("problema_decisao", "");
        String newDecision = now.optString("problema_decisao", "");
        if (!oldDecision.equals(newDecision)) return "problem_decision";

        boolean oldReturn = old.optBoolean("devolucao_pendente", false);
        boolean newReturn = now.optBoolean("devolucao_pendente", false);
        if (oldReturn != newReturn) return "return";

        String oldAck = old.optString("cliente_ack_em", "");
        String newAck = now.optString("cliente_ack_em", "");
        if (!oldAck.equals(newAck) && !newAck.isEmpty()) return "client_ack";

        String oldStatus = old.optString("status", "");
        String newStatus = now.optString("status", "");
        if (!oldStatus.equals(newStatus)) return "status";

        return "update";
    }

    private void notifyOrder(JSONObject order, String type) {
        int id = order.optInt("id", 0);
        String code = order.optString("codigo", String.valueOf(id));
        String client = order.optString("cliente", "Cliente");
        String status = order.optString("status", "").trim();
        String title;
        String text;

        switch (type) {
            case "new":
                title = "🏍 Nova entrega para você";
                text = "Pedido " + code + " • " + client;
                break;
            case "chat":
                String dir = order.optString("chat_direction", "cliente");
                title = "atendente".equals(dir) ? "🏪 Nova mensagem da loja" : "💬 Nova mensagem do cliente";
                String msg = order.optString("chat_message", "").trim();
                text = "Pedido " + code + (msg.isEmpty() ? "" : " • " + shorten(msg, 100));
                break;
            case "problem_decision":
                String d = order.optString("problema_decisao", "").trim();
                title = "✅ Problema da entrega atualizado";
                text = "Pedido " + code + (d.isEmpty() ? " • A loja atualizou a ocorrência" : " • " + humanize(d));
                break;
            case "return":
                title = order.optBoolean("devolucao_pendente", false) ? "⚠️ Devolução aguardando decisão" : "✅ Devolução atualizada";
                text = "Pedido " + code + " • " + client;
                break;
            case "client_ack":
                title = "👤 Cliente confirmou o recebimento";
                text = "Pedido " + code + " • O cliente viu o aviso e está indo receber";
                break;
            case "status":
                title = "🔄 Status da entrega atualizado";
                text = "Pedido " + code + (status.isEmpty() ? "" : " • " + status);
                break;
            default:
                title = "📦 Entrega atualizada";
                text = "Pedido " + code + " • " + client + (status.isEmpty() ? "" : " • " + status);
                break;
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("pedido_id", id);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                7000 + (id % 1000),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ALERTS)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis());

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_ALL);
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(10000 + (id % 100000), b.build());
    }

    private String shorten(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String humanize(String s) {
        if (s == null) return "";
        return s.replace('_', ' ').replace('-', ' ').trim();
    }
}
