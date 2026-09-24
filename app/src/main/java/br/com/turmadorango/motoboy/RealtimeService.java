package br.com.turmadorango.motoboy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class RealtimeService extends Service implements LocationListener {
    public static final String ACTION_CHANGED = "br.com.turmadorango.motoboy.REALTIME_CHANGED";
    public static final String ACTION_STOP = "br.com.turmadorango.motoboy.STOP_REALTIME";

    private static final String REALTIME_URL = "https://turmadorango.com.br/includes/motoboy/realtime_api.php";
    private static final String UNIBOY_API = "https://turmadorango.com.br/includes/app2/motoboy/api.php";
    private static final int UNIBOY_CALL_NOTIFICATION_ID = 6202;
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
    private LocationManager locationManager;
    private volatile Location latestLocation;
    private boolean locationUpdatesStarted = false;
    private boolean locationForegroundPromoted = false;
    private long lastLocationPostMs = 0L;
    private String lastUniboyOfferKey = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (working) {
                schedule(nextPollMs);
                return;
            }
            working = true;
            new Thread(() -> {
                try { pollUniboyPresence(); pollServer(); }
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
        startBaseForeground();
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, 500L);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        try {
            if (locationManager != null && locationUpdatesStarted) locationManager.removeUpdates(this);
        } catch (Exception ignored) {}
        locationUpdatesStarted = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startBaseForeground() {
        Notification n = buildServiceNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                        SERVICE_NOTIFICATION_ID,
                        n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            try { startForeground(SERVICE_NOTIFICATION_ID, n); } catch (Exception ignored) {}
        }
    }

    private void promoteLocationForegroundIfAllowed() {
        if (locationForegroundPromoted || Build.VERSION.SDK_INT < 29) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification(), type);
            locationForegroundPromoted = true;
        } catch (Exception ignored) {}
    }

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
                "UNIBOY conectado",
                NotificationManager.IMPORTANCE_LOW);
        svc.setDescription("Mantém o UNIBOY conectado para receber chamadas e atualizar a proximidade.");
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS,
                "Chamadas e entregas UNIBOY",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Chamadas de restaurantes, Turma do Rango e atualizações de entrega.");
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
                .setContentTitle("UNIBOY ENTREGAS • conectado")
                .setContentText("Monitorando chamadas e localização para proximidade")
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
            Notification.Builder b=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O
                    ? new Notification.Builder(this,CHANNEL_SERVICE)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("UNIBOY ENTREGAS • conectado")
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
            String appToken = getSharedPreferences("tdr_app_auth", MODE_PRIVATE)
                    .getString("app_token", "").trim();
            String endpoint = REALTIME_URL + "?t=" + System.currentTimeMillis();
            if (!appToken.isEmpty()) {
                endpoint += "&app_token=" + URLEncoder.encode(appToken, "UTF-8");
            }

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);
            conn.setRequestProperty("X-TDR-App-Version", BuildConfig.VERSION_NAME);
            if (!appToken.isEmpty()) conn.setRequestProperty("X-TDR-App-Token", appToken);

            String cookie = CookieManager.getInstance().getCookie("https://turmadorango.com.br/");
            if (cookie != null && !cookie.trim().isEmpty()) conn.setRequestProperty("Cookie", cookie);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                nextPollMs = LOGGED_OUT_POLL_MS;
                prefs.edit().putBoolean("baseline_ready", false).apply();
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
                return;
            }

            nextPollMs = Math.max(2500L, data.optLong("poll_ms", DEFAULT_POLL_MS));
            boolean storeOpen = data.optBoolean("store_open", false);


            int motoboyId = data.optInt("motoboy_id", 0);
            int previousMotoboy = prefs.getInt("motoboy_id", 0);
            if (previousMotoboy > 0 && motoboyId > 0 && previousMotoboy != motoboyId) {
                boolean enabled = prefs.getBoolean("service_enabled", true);
                prefs.edit().clear().putBoolean("service_enabled", enabled).putInt("motoboy_id", motoboyId).apply();
            } else if (motoboyId > 0 && previousMotoboy == 0) {
                prefs.edit().putInt("motoboy_id", motoboyId).apply();
            }

            processAppNotifications(data.optJSONArray("app_notifications"));

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

            if (anyChanged) sendRefreshBroadcast(data.optString("revision", ""));
        } catch (Exception ignored) {
            nextPollMs = 8000L;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }


    private void ensureLocationUpdates() {
        if (locationUpdatesStarted) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            promoteLocationForegroundIfAllowed();
            if (locationManager == null) locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;

            Location best = null;
            try {
                Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gps != null) best = gps;
            } catch (Exception ignored) {}
            try {
                Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (net != null && (best == null || net.getTime() > best.getTime())) best = net;
            } catch (Exception ignored) {}
            try {
                Location passive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (passive != null && (best == null || passive.getTime() > best.getTime())) best = passive;
            } catch (Exception ignored) {}
            if (best != null) latestLocation = best;

            boolean registered = false;
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 8000L, 5f, this, Looper.getMainLooper());
                    registered = true;
                }
            } catch (Exception ignored) {}
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, this, Looper.getMainLooper());
                    registered = true;
                }
            } catch (Exception ignored) {}
            locationUpdatesStarted = registered;
        } catch (Exception ignored) {}
    }

    @Override public void onLocationChanged(Location location) {
        if (location != null) latestLocation = location;
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @SuppressWarnings("deprecation")
    @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

    private JSONObject uniboyRequest(String action, Location location) {
        HttpURLConnection conn = null;
        try {
            boolean postLocation = "location".equals(action) && location != null;
            String endpoint = UNIBOY_API + "?action=" + URLEncoder.encode(action, "UTF-8")
                    + "&version=" + URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")
                    + "&_=" + System.currentTimeMillis();
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(6500);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "UniBoyEntregas/" + BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE);

            String cookie = CookieManager.getInstance().getCookie("https://turmadorango.com.br/");
            if (cookie != null && !cookie.trim().isEmpty()) conn.setRequestProperty("Cookie", cookie);

            if (postLocation) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                String body = "latitude=" + URLEncoder.encode(String.valueOf(location.getLatitude()), "UTF-8")
                        + "&longitude=" + URLEncoder.encode(String.valueOf(location.getLongitude()), "UTF-8")
                        + "&accuracy=" + URLEncoder.encode(String.valueOf(location.hasAccuracy() ? location.getAccuracy() : 0f), "UTF-8")
                        + "&version=" + URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8");
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int code = conn.getResponseCode();
            String setCookie = conn.getHeaderField("Set-Cookie");
            if (setCookie != null && !setCookie.trim().isEmpty()) {
                CookieManager.getInstance().setCookie("https://turmadorango.com.br/", setCookie);
                CookieManager.getInstance().flush();
            }

            java.io.InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
            if (body.length() == 0) return null;
            return new JSONObject(body.toString());
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void stopLocationUpdatesIfRunning() {
        try {
            if (locationManager != null && locationUpdatesStarted) {
                locationManager.removeUpdates(this);
            }
        } catch (Exception ignored) {}
        locationUpdatesStarted = false;
        locationForegroundPromoted = false;
    }

    private void pollUniboyPresence() {
        try {
            JSONObject data = uniboyRequest("current", null);
            if (data == null) return;

            if (!data.optBoolean("ok", false)) {
                String msg = data.optString("message", "");
                if (msg.toLowerCase().contains("autentic")) {
                    updateServiceNotification("Aguardando login no UNIBOY");
                }
                stopLocationUpdatesIfRunning();
                cancelUniboyOfferNotification();
                return;
            }

            boolean online = data.optBoolean("online", true);
            if (!online) {
                stopLocationUpdatesIfRunning();
                updateServiceNotification("Desconectado • localização pausada");
                cancelUniboyOfferNotification();
                return;
            }

            ensureLocationUpdates();

            Location loc = latestLocation;
            long now = System.currentTimeMillis();
            if (loc != null && now - lastLocationPostMs >= 10000L) {
                JSONObject locationReply = uniboyRequest("location", loc);
                if (locationReply != null && locationReply.optBoolean("ok", false)) {
                    lastLocationPostMs = now;
                }
            }

            String locationText = latestLocation != null
                    ? "Conectado • localização de segurança ativa"
                    : "Conectado • aguardando permissão/localização";
            updateServiceNotification(locationText);

            JSONObject offer = data.optJSONObject("offer");
            if (offer == null) {
                cancelUniboyOfferNotification();
                return;
            }
            showUniboyOfferNotification(offer);
        } catch (Exception ignored) {}
    }

    private void cancelUniboyOfferNotification() {
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(UNIBOY_CALL_NOTIFICATION_ID);
        } catch (Exception ignored) {}
        lastUniboyOfferKey = "";
    }

    private void showUniboyOfferNotification(JSONObject offer) {
        try {
            String source = offer.optString("source", "app2");
            String id = offer.optString("id", "");
            String token = offer.optString("token", "");
            String key = source + ":" + id + ":" + token + ":" + offer.optString("status", "");
            if (key.equals(lastUniboyOfferKey)) return;
            lastUniboyOfferKey = key;

            String restaurant = offer.optString("restaurante", "Restaurante parceiro");
            String destination = offer.optString("destino", "");
            String pickup = offer.optString("coleta", "");
            String value = "";
            if (!"negociar".equals(offer.optString("valor_modo", "valor"))) {
                double v = offer.optDouble("valor", offer.optDouble("pagamento_motoboy_valor", 0d));
                value = String.format(java.util.Locale.US, " • R$ %.2f", v).replace('.', ',');
            }

            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 6202, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String title = "tdr".equals(source)
                    ? "🏍 Nova chamada • Turma do Rango"
                    : "🏍 Nova chamada • " + restaurant;
            String text = (destination == null || destination.trim().isEmpty() ? pickup : destination) + value;

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ALERTS)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            (pickup == null || pickup.isEmpty() ? "" : "Coleta: " + pickup + "\n")
                                    + (destination == null || destination.isEmpty() ? "" : "Entrega: " + destination)
                                    + value))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOnlyAlertOnce(false)
                    .setWhen(System.currentTimeMillis());
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                b.setPriority(Notification.PRIORITY_HIGH);
                b.setDefaults(Notification.DEFAULT_ALL);
            }
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(
                    UNIBOY_CALL_NOTIFICATION_ID, b.build());
        } catch (Exception ignored) {}
    }

    private void processAppNotifications(JSONArray items) {
        if (items == null) return;
        int maxId = 0;
        for (int i=0;i<items.length();i++) {
            JSONObject n=items.optJSONObject(i);
            if(n!=null) maxId=Math.max(maxId,n.optInt("id",0));
        }
        if (maxId <= 0) return;

        boolean ready=prefs.getBoolean("app_notif_baseline_ready",false);
        int last=prefs.getInt("last_app_notification_id",0);
        if(!ready){
            prefs.edit().putBoolean("app_notif_baseline_ready",true).putInt("last_app_notification_id",maxId).apply();
            return;
        }

        for(int i=items.length()-1;i>=0;i--){
            JSONObject n=items.optJSONObject(i);
            if(n==null) continue;
            int id=n.optInt("id",0);
            if(id>last) notifyAppNotification(n);
        }
        if(maxId>last) prefs.edit().putInt("last_app_notification_id",maxId).apply();
    }

    private void notifyAppNotification(JSONObject n) {
        int id=n.optInt("id",0);
        String title=n.optString("titulo","🔔 Nova notificação");
        String text=n.optString("mensagem","");
        Intent open=new Intent(this,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,8100+(Math.abs(id)%1000),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O
                ? new Notification.Builder(this,CHANNEL_ALERTS)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis());
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O){b.setPriority(Notification.PRIORITY_HIGH);b.setDefaults(Notification.DEFAULT_ALL);}
        try { ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(18000+(Math.abs(id)%10000),b.build()); } catch(Exception ignored) {}
    }

    private void sendRefreshBroadcast(String revision) {
        Intent changed = new Intent(ACTION_CHANGED);
        changed.setPackage(getPackageName());
        changed.putExtra("revision", revision);
        sendBroadcast(changed);
    }

    private String detectChange(JSONObject old, JSONObject now) {
        int oldChat = old.optInt("chat_last_id", 0);
        int newChat = now.optInt("chat_last_id", 0);
        if (newChat > oldChat && newChat > 0) return "chat";

        boolean oldPaid=old.optBoolean("pago",false);
        boolean newPaid=now.optBoolean("pago",false);
        String oldPayment=old.optString("forma_pagamento","");
        String newPayment=now.optString("forma_pagamento","");
        double oldTotal=old.optDouble("total",0);
        double newTotal=now.optDouble("total",0);
        if(oldPaid!=newPaid || !oldPayment.equals(newPayment) || Math.abs(oldTotal-newTotal)>0.009) return "payment";

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
            case "payment":
                if(order.optBoolean("pago",false)){
                    title="✅ Pedido pago";
                    text="Pedido "+code+" • Não receber valor do cliente.";
                }else{
                    title="💳 Pagamento atualizado";
                    text="Pedido "+code+" • "+order.optString("forma_pagamento","Pagamento")+" • "+order.optString("total_brl","");
                }
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
                this, 7000 + (id % 1000), open,
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
        try { ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(10000 + (id % 100000), b.build()); } catch(Exception ignored) {}
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
