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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String START_URL = "https://turmadorango.com.br/includes/app2/motoboy/";
    private static final String UPDATE_URL = "https://turmadorango.com.br/includes/app2/app-version.php";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final int REQ_LOCATION = 1401;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;

    private FrameLayout connectionOverlay;
    private TextView connectionStatusText;
    private final Handler webUiHandler = new Handler(Looper.getMainLooper());
    private Runnable webLoadTimeoutRunnable;
    private Runnable webRetryRunnable;
    private int webRetryAttempt = 0;
    private boolean mainFrameLoading = false;
    private long lastPageFinishedAt = 0L;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private FrameLayout updateOverlay;
    private TextView updateTitleText;
    private TextView updateStatusText;
    private TextView updatePercentText;
    private ProgressBar updateProgressBar;

    private final Handler updateUiHandler = new Handler(Looper.getMainLooper());
    private Runnable downloadProgressRunnable;

    private long updateDownloadId = -1L;
    private Uri pendingInstallUri;
    private boolean updateIsForced = false;
    private boolean updateCheckStarted = false;
    private boolean receiverRegistered = false;
    private boolean completionHandled = false;
    private boolean waitingUnknownSourcesPermission = false;
    private boolean installerLaunched = false;
    private String updateVersionLabel = "";

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != updateDownloadId || id < 0 || completionHandled) return;
            handleCompletedDownload(id);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF050505);
        getWindow().setNavigationBarColor(0xFF050505);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050505);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF050505);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        root.addView(progressBar, progressParams);

        createConnectionOverlay();
        createUpdateOverlay();
        setContentView(root);

        registerUpdateReceiver();
        configureWebView();

        // Sempre inicia por uma rota nova. Restaurar o estado antigo do WebView
        // podia trazer de volta uma tela branca ou a escolha de perfil já obsoleta.
        loadStartPage("abertura");

        checkForUpdate();
    }

    private void createConnectionOverlay() {
        connectionOverlay = new FrameLayout(this);
        connectionOverlay.setBackgroundColor(0xFF050505);
        connectionOverlay.setClickable(true);
        connectionOverlay.setFocusable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(24));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF111111);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), 0xFFFFC400);
        card.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("UNIBOY ENTREGAS");
        title.setTextColor(0xFFFFC400);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar loading = new ProgressBar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && loading.getIndeterminateDrawable() != null) {
            loading.getIndeterminateDrawable().setTint(0xFFFFC400);
        }
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        loadLp.topMargin = dp(18);
        card.addView(loading, loadLp);

        connectionStatusText = new TextView(this);
        connectionStatusText.setText("Abrindo seu painel...");
        connectionStatusText.setTextColor(Color.WHITE);
        connectionStatusText.setTextSize(15);
        connectionStatusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(14);
        card.addView(connectionStatusText, statusLp);

        TextView note = new TextView(this);
        note.setText("Se a conexão oscilar, o app tenta novamente sozinho.");
        note.setTextColor(0xFF999999);
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(8);
        card.addView(note, noteLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        connectionOverlay.addView(card, cardLp);

        root.addView(connectionOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showConnectionOverlay(String text) {
        runOnUiThread(() -> {
            if (connectionOverlay == null) return;
            if (connectionStatusText != null && text != null && !text.trim().isEmpty()) {
                connectionStatusText.setText(text);
            }
            connectionOverlay.setVisibility(View.VISIBLE);
            connectionOverlay.bringToFront();
            if (updateOverlay != null && updateOverlay.getVisibility() == View.VISIBLE) {
                updateOverlay.bringToFront();
            }
        });
    }

    private void hideConnectionOverlay() {
        runOnUiThread(() -> {
            if (connectionOverlay != null) connectionOverlay.setVisibility(View.GONE);
        });
    }

    private void cancelWebWatchdog() {
        if (webLoadTimeoutRunnable != null) {
            webUiHandler.removeCallbacks(webLoadTimeoutRunnable);
            webLoadTimeoutRunnable = null;
        }
    }

    private void cancelWebRetry() {
        if (webRetryRunnable != null) {
            webUiHandler.removeCallbacks(webRetryRunnable);
            webRetryRunnable = null;
        }
    }

    private void scheduleWebWatchdog() {
        cancelWebWatchdog();
        webLoadTimeoutRunnable = () -> {
            if (mainFrameLoading && !isFinishing()) {
                scheduleWebRecovery("A conexão demorou mais que o esperado.");
            }
        };
        webUiHandler.postDelayed(webLoadTimeoutRunnable, 20000L);
    }

    private void loadStartPage(String reason) {
        if (webView == null || isFinishing()) return;
        cancelWebRetry();
        cancelWebWatchdog();
        if (connectionOverlay != null) connectionOverlay.setOnClickListener(null);
        mainFrameLoading = true;
        String status = webRetryAttempt > 0
                ? "Reconectando automaticamente... tentativa " + webRetryAttempt
                : "Abrindo seu painel...";
        showConnectionOverlay(status);
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        try { webView.stopLoading(); } catch (Exception ignored) {}
        String sep = START_URL.contains("?") ? "&" : "?";
        webView.loadUrl(START_URL + sep + "_appopen=" + System.currentTimeMillis());
        scheduleWebWatchdog();
    }

    private void scheduleWebRecovery(String reason) {
        if (isFinishing()) return;
        mainFrameLoading = false;
        cancelWebWatchdog();
        cancelWebRetry();

        // Evita laço infinito de reconexão. Faz poucas tentativas automáticas e,
        // se a rede/servidor continuar indisponível, deixa o usuário tentar de novo.
        webRetryAttempt++;
        if (webRetryAttempt > 3) {
            showConnectionOverlay("Não foi possível carregar agora. Toque na tela para tentar novamente.");
            if (connectionOverlay != null) {
                connectionOverlay.setOnClickListener(v -> {
                    webRetryAttempt = 0;
                    loadStartPage("tentativa-manual");
                });
            }
            return;
        }

        showConnectionOverlay("Reconectando automaticamente... tentativa " + webRetryAttempt + " de 3");
        long delay = 1200L + (webRetryAttempt * 1200L);
        webRetryRunnable = () -> loadStartPage("recuperacao");
        webUiHandler.postDelayed(webRetryRunnable, delay);
    }

    private boolean isBlankUrl(String url) {
        return url == null || url.trim().isEmpty() || "about:blank".equalsIgnoreCase(url.trim());
    }

    private void validateRenderedPage() {
        if (webView == null || isFinishing()) return;
        String url = webView.getUrl();
        if (isBlankUrl(url)) {
            scheduleWebRecovery("Tela vazia.");
            return;
        }

        webView.evaluateJavascript(
                "(function(){try{return document.body?document.body.innerText.trim().length:-1}catch(e){return -1}})();",
                value -> {
                    if (isFinishing()) return;
                    int len = -1;
                    try {
                        String raw = String.valueOf(value).replace("\"", "").trim();
                        len = Integer.parseInt(raw);
                    } catch (Exception ignored) {}

                    if (len == 0 && webRetryAttempt == 0) {
                        // Algumas páginas montam o conteúdo por JavaScript logo após
                        // onPageFinished. Espera antes de considerar a tela vazia.
                        webUiHandler.postDelayed(() -> {
                            if (webView == null || isFinishing()) return;
                            webView.evaluateJavascript(
                                    "(function(){try{return document.body?document.body.innerText.trim().length:-1}catch(e){return -1}})();",
                                    second -> {
                                        try {
                                            String raw2 = String.valueOf(second).replace("\"", "").trim();
                                            if (Integer.parseInt(raw2) == 0) scheduleWebRecovery("Conteúdo vazio.");
                                            else {
                                                webRetryAttempt = 0;
                                                hideConnectionOverlay();
                                            }
                                        } catch (Exception ignored) {
                                            hideConnectionOverlay();
                                        }
                                    });
                        }, 1600L);
                    } else {
                        webRetryAttempt = 0;
                        hideConnectionOverlay();
                    }
                });
    }

    private void checkPageHealthOnResume() {
        if (webView == null || isFinishing()) return;
        webUiHandler.postDelayed(() -> {
            if (webView == null || isFinishing()) return;

            // Na primeira abertura o onResume acontece enquanto a página ainda está
            // carregando. Não reinicia o WebView nesse momento.
            if (mainFrameLoading || lastPageFinishedAt == 0L) return;

            String url = webView.getUrl();
            if (isBlankUrl(url)) {
                webRetryAttempt = 0;
                loadStartPage("retorno-tela-vazia");
            }
        }, 900L);
    }

    private void createUpdateOverlay() {
        updateOverlay = new FrameLayout(this);
        updateOverlay.setBackgroundColor(0xE6000000);
        updateOverlay.setVisibility(View.GONE);
        updateOverlay.setClickable(true);
        updateOverlay.setFocusable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(24));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(2), 0xFFFFC400);
        card.setBackground(cardBg);

        TextView brand = new TextView(this);
        brand.setText("UNIBOY ENTREGAS");
        brand.setTextColor(0xFFFFB900);
        brand.setTextSize(24);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        updateTitleText = new TextView(this);
        updateTitleText.setText("Atualizando aplicativo");
        updateTitleText.setTextColor(0xFF111111);
        updateTitleText.setTextSize(20);
        updateTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        updateTitleText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(10);
        card.addView(updateTitleText, titleLp);

        updateStatusText = new TextView(this);
        updateStatusText.setText("Preparando atualização...");
        updateStatusText.setTextColor(0xFF4B4B4B);
        updateStatusText.setTextSize(16);
        updateStatusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(12);
        card.addView(updateStatusText, statusLp);

        updateProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateProgressBar.setIndeterminate(false);
        updateProgressBar.setMax(100);
        updateProgressBar.setProgress(0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14));
        barLp.topMargin = dp(22);
        card.addView(updateProgressBar, barLp);

        updatePercentText = new TextView(this);
        updatePercentText.setText("0%");
        updatePercentText.setTextColor(0xFF111111);
        updatePercentText.setTextSize(28);
        updatePercentText.setTypeface(Typeface.DEFAULT_BOLD);
        updatePercentText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams percentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        percentLp.topMargin = dp(8);
        card.addView(updatePercentText, percentLp);

        TextView note = new TextView(this);
        note.setText("Não feche o aplicativo durante a atualização.");
        note.setTextColor(0xFF777777);
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(8);
        card.addView(note, noteLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        updateOverlay.addView(card, cardLp);

        root.addView(updateOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showUpdateOverlay(String status, int percent, boolean indeterminate) {
        runOnUiThread(() -> {
            updateOverlay.setVisibility(View.VISIBLE);
            updateTitleText.setText(updateVersionLabel.isEmpty()
                    ? "Atualizando aplicativo"
                    : "Atualizando para " + updateVersionLabel);
            updateStatusText.setText(status);
            updateProgressBar.setIndeterminate(indeterminate);
            if (!indeterminate) {
                int p = Math.max(0, Math.min(100, percent));
                updateProgressBar.setProgress(p);
                updatePercentText.setText(p + "%");
                updatePercentText.setVisibility(View.VISIBLE);
            } else {
                updatePercentText.setText("...");
                updatePercentText.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideUpdateOverlay() {
        runOnUiThread(() -> updateOverlay.setVisibility(View.GONE));
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
        s.setUserAgentString(s.getUserAgentString() + " UniBoyEntregas/" + BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                mainFrameLoading = true;
                scheduleWebWatchdog();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                mainFrameLoading = false;
                lastPageFinishedAt = System.currentTimeMillis();
                cancelWebWatchdog();
                cancelWebRetry();
                CookieManager.getInstance().flush();
                String versionLabel = "Versão " + BuildConfig.VERSION_NAME;
                String js = "(function(){var e=document.getElementById('tdrAppVersion');if(e)e.textContent="
                        + JSONObject.quote(versionLabel) + ";})();";
                view.evaluateJavascript(js, null);
                sanitizeUniboyWebUi();
                webUiHandler.postDelayed(MainActivity.this::validateRenderedPage, 220L);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    scheduleWebRecovery("Falha ao carregar o painel.");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame()
                        && errorResponse != null && errorResponse.getStatusCode() >= 500) {
                    scheduleWebRecovery("Servidor temporariamente indisponível.");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
            }

            @Override
            public boolean onCreateWindow(
                    WebView view,
                    boolean isDialog,
                    boolean isUserGesture,
                    Message resultMsg) {
                WebView popup = new WebView(MainActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openPopupUrl(request.getUrl().toString(), popup);
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openPopupUrl(url, popup);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    private void sanitizeUniboyWebUi() {
        if (webView == null) return;
        String js = "(function(){try{"
                + "var st=document.getElementById('u2NativeBrandShield');"
                + "if(!st){st=document.createElement('style');st.id='u2NativeBrandShield';"
                + "st.textContent='body.tdr-site>header,body.tdr-site>footer,body.tdr-site>#preloader,body.tdr-site #preloader,body.tdr-site #logo,body.tdr-site .main-menu{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important;margin:0!important;padding:0!important;border:0!important;overflow:hidden!important}body.tdr-site{padding-top:0!important;margin-top:0!important}';"
                + "(document.head||document.documentElement).appendChild(st);}"
                + "var els=document.querySelectorAll('body *');"
                + "for(var i=0;i<els.length;i++){var e=els[i];"
                + "if(e.children.length===0&&e.textContent&&/Turma\\s+do\\s+Rango/i.test(e.textContent)){e.textContent=e.textContent.replace(/Turma\\s+do\\s+Rango/gi,'UNIBOY');}"
                + "if(e.getAttribute){['alt','title','aria-label'].forEach(function(a){var v=e.getAttribute(a);if(v&&/Turma\\s+do\\s+Rango/i.test(v))e.setAttribute(a,v.replace(/Turma\\s+do\\s+Rango/gi,'UNIBOY'));});}}"
                + "}catch(e){}})();";
        try { webView.evaluateJavascript(js, null); } catch (Exception ignored) {}
    }

    private void registerUpdateReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
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
                conn.setRequestProperty("User-Agent",
                        "UniBoyEntregas/" + BuildConfig.VERSION_NAME);

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), StandardCharsets.UTF_8));
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
                // Se o servidor estiver indisponível, o app continua normalmente.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "tdr-update-check").start();
    }

    private void presentUpdate(JSONObject data) {
        if (updateDownloadId >= 0 || pendingInstallUri != null) return;

        updateIsForced = data.optBoolean("force_update", true);
        updateVersionLabel = data.optString("version_name", "nova versão").trim();
        if (updateVersionLabel.isEmpty()) updateVersionLabel = "nova versão";

        showUpdateOverlay("Nova versão encontrada. Preparando o download...", 0, true);

        // O download sempre começa automaticamente. A opção obrigatória apenas impede
        // que o usuário volte ao app sem concluir a atualização.
        updateUiHandler.postDelayed(() -> startUpdateDownload(data), 450L);
    }

    private void startUpdateDownload(JSONObject data) {
        if (updateDownloadId >= 0) return;

        try {
            String apkUrl = data.getString("apk_url");
            int remoteCode = data.optInt("version_code", 0);
            String remoteName = data.optString("version_name", String.valueOf(remoteCode));

            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IllegalStateException("Armazenamento não disponível");

            String fileName = "UNIBOY-Entregas-v" + remoteCode + ".apk";
            File old = new File(dir, fileName);
            if (old.exists()) old.delete();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("UNIBOY ENTREGAS " + remoteName);
            request.setDescription("Baixando atualização do aplicativo...");
            request.setMimeType(APK_MIME);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(
                    this, Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            completionHandled = false;
            installerLaunched = false;
            waitingUnknownSourcesPermission = false;
            updateDownloadId = dm.enqueue(request);

            showUpdateOverlay("Baixando atualização...", 0, false);
            startDownloadProgressMonitor();
        } catch (Exception e) {
            updateDownloadId = -1L;
            showDownloadError();
        }
    }

    private void startDownloadProgressMonitor() {
        stopDownloadProgressMonitor();

        downloadProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (updateDownloadId < 0 || completionHandled) return;

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query()
                        .setFilterById(updateDownloadId);

                try (Cursor c = dm.query(query)) {
                    if (c == null || !c.moveToFirst()) {
                        updateUiHandler.postDelayed(this, 350L);
                        return;
                    }

                    int status = c.getInt(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS));
                    long downloaded = c.getLong(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                    int percent = 0;
                    if (total > 0) {
                        percent = (int) Math.min(100L, (downloaded * 100L) / total);
                    }

                    if (status == DownloadManager.STATUS_PENDING) {
                        showUpdateOverlay("Preparando download...", percent, total <= 0);
                    } else if (status == DownloadManager.STATUS_RUNNING) {
                        showUpdateOverlay("Baixando atualização...", percent, total <= 0);
                    } else if (status == DownloadManager.STATUS_PAUSED) {
                        showUpdateOverlay("Download pausado. Aguardando conexão...", percent, total <= 0);
                    } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        showUpdateOverlay("Download concluído. Conferindo arquivo...", 100, false);
                        handleCompletedDownload(updateDownloadId);
                        return;
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        showDownloadError();
                        return;
                    }
                } catch (Exception ignored) {
                    // Mantém o monitoramento até o DownloadManager responder novamente.
                }

                updateUiHandler.postDelayed(this, 350L);
            }
        };

        updateUiHandler.post(downloadProgressRunnable);
    }

    private void stopDownloadProgressMonitor() {
        if (downloadProgressRunnable != null) {
            updateUiHandler.removeCallbacks(downloadProgressRunnable);
            downloadProgressRunnable = null;
        }
    }

    private void handleCompletedDownload(long id) {
        if (completionHandled) return;
        completionHandled = true;
        stopDownloadProgressMonitor();

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);

        try (Cursor c = dm.query(query)) {
            if (c == null || !c.moveToFirst()) {
                showDownloadError();
                return;
            }

            int status = c.getInt(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                completionHandled = false;
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
        updateDownloadId = -1L;
        showUpdateOverlay("Download concluído. Preparando instalação...", 100, false);
        updateUiHandler.postDelayed(() -> requestInstall(uri), 650L);
    }

    private void showDownloadError() {
        stopDownloadProgressMonitor();
        updateDownloadId = -1L;
        completionHandled = false;

        runOnUiThread(() -> {
            updateVersionLabel = updateVersionLabel.isEmpty()
                    ? "atualização"
                    : updateVersionLabel;
            showUpdateOverlay("Não foi possível baixar. Verifique a internet e tente novamente.", 0, false);

            new AlertDialog.Builder(this)
                    .setTitle("Falha na atualização")
                    .setMessage("Não foi possível baixar a atualização. Confira sua conexão com a internet.")
                    .setPositiveButton("Tentar novamente", (d, w) -> {
                        hideUpdateOverlay();
                        updateCheckStarted = false;
                        checkForUpdate();
                    })
                    .setNegativeButton(updateIsForced ? "Fechar app" : "Agora não", (d, w) -> {
                        if (updateIsForced) finishAndRemoveTask();
                        else hideUpdateOverlay();
                    })
                    .setCancelable(!updateIsForced)
                    .show();
        });
    }

    private void requestInstall(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            waitingUnknownSourcesPermission = true;
            showUpdateOverlay(
                    "O Android precisa autorizar este app a instalar atualizações. Abrindo a permissão...",
                    100,
                    false);

            updateUiHandler.postDelayed(() -> {
                try {
                    Intent i = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    } catch (Exception ignored) {}
                }
            }, 700L);
            return;
        }

        installApk(uri);
    }

    private void installApk(Uri uri) {
        if (uri == null || installerLaunched) return;

        waitingUnknownSourcesPermission = false;
        installerLaunched = true;
        showUpdateOverlay("Abrindo o instalador do Android...", 100, false);

        updateUiHandler.postDelayed(() -> {
            try {
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, APK_MIME);
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(install);

                showUpdateOverlay(
                        "Atualização pronta. Confirme a instalação na tela do Android.",
                        100,
                        false);
            } catch (ActivityNotFoundException e) {
                installerLaunched = false;
                Toast.makeText(this,
                        "O instalador do Android não foi encontrado.",
                        Toast.LENGTH_LONG).show();
                showDownloadError();
            } catch (Exception e) {
                installerLaunched = false;
                Toast.makeText(this,
                        "Não foi possível abrir a atualização.",
                        Toast.LENGTH_LONG).show();
                showDownloadError();
            }
        }, 500L);
    }

    private void openPopupUrl(String url, WebView popup) {
        if (isInternal(url)) webView.loadUrl(url);
        else openExternal(url);
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
            if (!("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) return false;
            if (host == null) return false;
            return host.equalsIgnoreCase("turmadorango.com.br")
                    || host.equalsIgnoreCase("www.turmadorango.com.br");
        } catch (Exception e) {
            return false;
        }
    }

    private void openExternal(String url) {
        try {
            if (url.startsWith("intent://")) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && !fallback.isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                    } else {
                        Toast.makeText(this,
                                "Aplicativo necessário não encontrado.",
                                Toast.LENGTH_SHORT).show();
                    }
                }
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this,
                    "Não foi possível abrir este link.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_LOCATION && pendingGeoCallback != null) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }

            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;

            if (!granted) {
                Toast.makeText(this,
                        "Ative a localização para o rastreamento do motoboy.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (updateOverlay != null && updateOverlay.getVisibility() == View.VISIBLE) {
            if (updateIsForced || updateDownloadId >= 0 || waitingUnknownSourcesPermission) return;
        }

        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Não persiste a navegação do WebView. Uma nova abertura sempre passa
        // pelo bootstrap de sessão e evita restaurar tela branca/seletor antigo.
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        checkPageHealthOnResume();

        if (pendingInstallUri != null
                && waitingUnknownSourcesPermission
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls())) {
            Uri uri = pendingInstallUri;
            showUpdateOverlay("Permissão concedida. Preparando instalação...", 100, false);
            updateUiHandler.postDelayed(() -> installApk(uri), 450L);
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopDownloadProgressMonitor();
        updateUiHandler.removeCallbacksAndMessages(null);
        webUiHandler.removeCallbacksAndMessages(null);

        if (receiverRegistered) {
            try {
                unregisterReceiver(updateReceiver);
            } catch (Exception ignored) {}
            receiverRegistered = false;
        }

        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
