package br.com.turmadorango.motoboy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String START_URL = "https://turmadorango.com.br/includes/motoboy/";
    private static final String UPDATE_URL = "https://turmadorango.com.br/includes/motoboy/app-version.php";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final int REQ_LOCATION = 1401;

    private WebView webView;
    private ProgressBar progressBar;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private long updateDownloadId = -1L;
    private Uri pendingInstallUri;
    private boolean updateIsForced = false;
    private boolean updateCheckStarted = false;
    private AlertDialog forcedUpdateDialog;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != updateDownloadId || id < 0) return;
            handleCompletedDownload(id);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF050505);
        getWindow().setNavigationBarColor(0xFF050505);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050505);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);

        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = android.view.Gravity.TOP;
        root.addView(progressBar, progressParams);
        setContentView(root);

        registerUpdateReceiver();
        configureWebView();
        if (savedInstanceState == null) webView.loadUrl(START_URL);
        else webView.restoreState(savedInstanceState);

        checkForUpdate();
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return handleUrl(request.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return handleUrl(url); }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { progressBar.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                String versionLabel = "Versão " + BuildConfig.VERSION_NAME;
                String js = "(function(){var e=document.getElementById('tdrAppVersion');if(e)e.textContent=" + JSONObject.quote(versionLabel) + ";})();";
                view.evaluateJavascript(js, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView popup = new WebView(MainActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openPopupUrl(request.getUrl().toString(), popup); return true;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openPopupUrl(url, popup); return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    private void registerUpdateReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updateReceiver, filter);
        receiverRegistered = true;
    }

    private void checkForUpdate() {
        if (updateCheckStarted) return;
        updateCheckStarted = true;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(UPDATE_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return;
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();
                JSONObject data = new JSONObject(body.toString());
                int remoteCode = data.optInt("version_code", 0);
                String apkUrl = data.optString("apk_url", "").trim();
                if (remoteCode > BuildConfig.VERSION_CODE && !apkUrl.isEmpty()) {
                    runOnUiThread(() -> presentUpdate(data));
                }
            } catch (Exception ignored) {
                // Sem internet ou endpoint indisponível: o app continua funcionando normalmente.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "tdr-update-check").start();
    }

    private void presentUpdate(JSONObject data) {
        if (updateDownloadId >= 0 || pendingInstallUri != null) return;
        updateIsForced = data.optBoolean("force_update", true);
        String version = data.optString("version_name", "nova");
        String changelog = data.optString("changelog", "Melhorias e correções no aplicativo.").trim();
        String message = "Nova versão " + version + " disponível.\n\n" + changelog;

        if (updateIsForced) {
            forcedUpdateDialog = new AlertDialog.Builder(this)
                    .setTitle("Atualização do aplicativo")
                    .setMessage(message + "\n\nO download será iniciado automaticamente.")
                    .setCancelable(false)
                    .create();
            forcedUpdateDialog.show();
            startUpdateDownload(data);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Atualização disponível")
                    .setMessage(message)
                    .setPositiveButton("Atualizar agora", (d, w) -> startUpdateDownload(data))
                    .setNegativeButton("Depois", null)
                    .show();
        }
    }

    private void startUpdateDownload(JSONObject data) {
        if (updateDownloadId >= 0) return;
        try {
            String apkUrl = data.getString("apk_url");
            int remoteCode = data.optInt("version_code", 0);
            String remoteName = data.optString("version_name", String.valueOf(remoteCode));
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IllegalStateException("Armazenamento não disponível");
            String fileName = "TurmaDoRango-Motoboy-v" + remoteCode + ".apk";
            File old = new File(dir, fileName);
            if (old.exists()) old.delete();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("Turma do Rango Motoboy " + remoteName);
            request.setDescription("Baixando atualização do aplicativo...");
            request.setMimeType(APK_MIME);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            updateDownloadId = dm.enqueue(request);
            Toast.makeText(this, "Atualização encontrada. Baixando...", Toast.LENGTH_LONG).show();
            if (forcedUpdateDialog != null) forcedUpdateDialog.setMessage("Baixando a atualização " + remoteName + "...\n\nQuando terminar, o Android abrirá a instalação.");
        } catch (Exception e) {
            updateDownloadId = -1L;
            if (forcedUpdateDialog != null) forcedUpdateDialog.dismiss();
            showDownloadError();
        }
    }

    private void handleCompletedDownload(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(query)) {
            if (c == null || !c.moveToFirst()) {
                showDownloadError();
                return;
            }
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                showDownloadError();
                return;
            }
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            showDownloadError();
            return;
        }
        pendingInstallUri = uri;
        if (forcedUpdateDialog != null) {
            forcedUpdateDialog.dismiss();
            forcedUpdateDialog = null;
        }
        requestInstall(uri);
    }

    private void showDownloadError() {
        updateDownloadId = -1L;
        if (forcedUpdateDialog != null) {
            forcedUpdateDialog.dismiss();
            forcedUpdateDialog = null;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Não foi possível atualizar")
                .setMessage("Confira a internet e abra o aplicativo novamente para tentar de novo.")
                .setPositiveButton("OK", null);
        b.setCancelable(!updateIsForced);
        b.show();
    }

    private void requestInstall(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle("Permitir atualização")
                    .setMessage("Na próxima tela, permita que o Turma do Rango instale atualizações. Essa autorização é necessária apenas para atualizar o app fora da Play Store.")
                    .setPositiveButton("Abrir configuração", (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        }
                    });
            if (!updateIsForced) b.setNegativeButton("Depois", null);
            b.setCancelable(!updateIsForced);
            b.show();
            return;
        }
        installApk(uri);
    }

    private void installApk(Uri uri) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, APK_MIME);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "O instalador do Android não foi encontrado.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir a atualização.", Toast.LENGTH_LONG).show();
        }
    }

    private void openPopupUrl(String url, WebView popup) {
        if (isInternal(url)) webView.loadUrl(url); else openExternal(url);
        popup.destroy();
    }

    private boolean handleUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        if (isInternal(url)) return false;
        openExternal(url);
        return true;
    }

    private boolean isInternal(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            if (host == null) return false;
            return host.equalsIgnoreCase("turmadorango.com.br") || host.equalsIgnoreCase("www.turmadorango.com.br");
        } catch (Exception e) { return false; }
    }

    private void openExternal(String url) {
        try {
            if (url.startsWith("intent://")) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                try { startActivity(intent); }
                catch (ActivityNotFoundException e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && !fallback.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                    else Toast.makeText(this, "Aplicativo necessário não encontrado.", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir este link.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && pendingGeoCallback != null) {
            boolean granted = false;
            for (int result : grantResults) if (result == PackageManager.PERMISSION_GRANTED) { granted = true; break; }
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
            if (!granted) Toast.makeText(this, "Ative a localização para o rastreamento do motoboy.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onBackPressed() {
        if (updateIsForced && (updateDownloadId >= 0 || pendingInstallUri != null)) return;
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (pendingInstallUri != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls())) {
            Uri uri = pendingInstallUri;
            new Handler(Looper.getMainLooper()).postDelayed(() -> installApk(uri), 450);
        }
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
