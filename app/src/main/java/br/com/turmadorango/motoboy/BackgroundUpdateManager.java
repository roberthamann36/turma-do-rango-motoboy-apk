package br.com.turmadorango.motoboy;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class BackgroundUpdateManager {
    private static final String UPDATE_URL = "https://turmadorango.com.br/includes/app2/app-version.php";
    private static final String PREFS = "tdr_bg_update";
    private static final String CHANNEL = "tdr_motoboy_updates_v2";
    private static final long CHECK_INTERVAL = 2 * 60 * 1000L;
    private static BackgroundUpdateManager instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checking;
    private boolean receiverRegistered;
    private boolean installerLaunching;
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);

    private final Runnable checkRunnable = new Runnable() {
        @Override public void run() {
            checkNow();
            handler.postDelayed(this, CHECK_INTERVAL);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long expected = prefs.getLong("download_id", -1L);
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id > 0 && id == expected) handleDownloadCompleted(id);
        }
    };

    private BackgroundUpdateManager(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        createChannel();
        registerReceiver();
        handler.postDelayed(checkRunnable, 5000L);
        long existing = prefs.getLong("download_id", -1L);
        if (existing > 0) handler.postDelayed(() -> inspectExisting(existing), 2500L);
    }

    public static synchronized void start(Context context) {
        if (instance == null) instance = new BackgroundUpdateManager(context);
    }

    public static void onActivityResumed(Activity activity) {
        start(activity.getApplicationContext());
        if (instance == null) return;
        instance.resumedActivity = new WeakReference<>(activity);
        instance.checkNow();
        long ready = instance.prefs.getLong("ready_download_id", -1L);
        if (ready > 0) instance.handler.postDelayed(() -> instance.tryInstall(activity, ready), 550L);
    }

    public static void onActivityPaused(Activity activity) {
        if (instance == null) return;
        Activity current = instance.resumedActivity.get();
        if (current == activity) instance.resumedActivity = new WeakReference<>(null);
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(receiver, f);
            receiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Atualizações do aplicativo", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Download automático das atualizações do aplicativo do motoboy.");
        nm.createNotificationChannel(ch);
    }

    private void checkNow() {
        if (checking) return;
        checking = true;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(UPDATE_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "UniBoyEntregas/" + BuildConfig.VERSION_NAME);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return;

                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) body.append(line);
                }
                JSONObject data = new JSONObject(body.toString());
                int remoteCode = data.optInt("version_code", 0);
                String apkUrl = data.optString("apk_url", "").trim();
                if (remoteCode <= BuildConfig.VERSION_CODE || apkUrl.isEmpty()) return;

                long existing = prefs.getLong("download_id", -1L);
                int existingCode = prefs.getInt("remote_code", 0);
                if (existing > 0 && existingCode == remoteCode) {
                    inspectExisting(existing);
                    return;
                }
                enqueue(data);
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
                checking = false;
            }
        }, "tdr-bg-update-check").start();
    }

    private void enqueue(JSONObject data) {
        try {
            int remoteCode = data.optInt("version_code", 0);
            String remoteName = data.optString("version_name", String.valueOf(remoteCode));
            String apkUrl = data.optString("apk_url", "");
            File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return;
            String fileName = "UNIBOY-Entregas-v" + remoteCode + ".apk";
            File old = new File(dir, fileName);
            if (old.exists()) old.delete();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("UNIBOY ENTREGAS " + remoteName);
            request.setDescription("Baixando atualização automaticamente...");
            request.setMimeType("application/vnd.android.package-archive");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(request);
            prefs.edit()
                    .putLong("download_id", id)
                    .putInt("remote_code", remoteCode)
                    .putString("remote_name", remoteName)
                    .remove("ready_download_id")
                    .apply();
        } catch (Exception ignored) {}
    }

    private void inspectExisting(long id) {
        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c == null || !c.moveToFirst()) return;
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) handleDownloadCompleted(id);
            else if (status == DownloadManager.STATUS_FAILED) {
                prefs.edit().remove("download_id").remove("ready_download_id").apply();
            }
        } catch (Exception ignored) {}
    }

    private void handleDownloadCompleted(long id) {
        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = null;
        try { uri = dm.getUriForDownloadedFile(id); } catch (Exception ignored) {}
        if (uri == null) return;

        prefs.edit().putLong("ready_download_id", id).apply();
        Activity a = resumedActivity.get();
        if (a != null && !a.isFinishing()) {
            final Activity target = a;
            handler.postDelayed(() -> tryInstall(target, id), 500L);
        } else {
            showReadyNotification();
        }
    }

    private void showReadyNotification() {
        try {
            Intent open = new Intent(app, LauncherActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, 8841, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String version = prefs.getString("remote_name", "nova versão");
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(app, CHANNEL)
                    : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Atualização " + version + " baixada")
                    .setContentText("A atualização foi baixada. Abra o app para concluir a instalação.")
                    .setStyle(new Notification.BigTextStyle().bigText("O download foi concluído automaticamente em segundo plano. Quando o aplicativo estiver aberto, o Android iniciará a instalação."))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_DEFAULT);
            ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).notify(8841, b.build());
        } catch (Exception ignored) {}
    }

    private void tryInstall(Activity activity, long id) {
        if (installerLaunching || activity == null || activity.isFinishing()) return;
        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri;
        try { uri = dm.getUriForDownloadedFile(id); } catch (Exception e) { return; }
        if (uri == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()));
                activity.startActivity(perm);
            } catch (Exception ignored) {}
            return;
        }

        installerLaunching = true;
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
            prefs.edit().remove("ready_download_id").remove("download_id").apply();
        } catch (Exception ignored) {
            installerLaunching = false;
            showReadyNotification();
        }
    }
}
