package br.com.turmadorango.motoboy;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Evita que o motoboy veja páginas de erro do Android/Cloudflare.
 * Em falha de rede ou HTTP 5xx, cobre o WebView com uma tela da marca e
 * tenta reconstruir a comunicação automaticamente até o servidor voltar.
 */
public final class CommunicationGuard {
    private static final String START_URL = "https://turmadorango.com.br/includes/motoboy/";
    private static final WeakHashMap<WebView, CommunicationGuard> INSTALLED = new WeakHashMap<>();

    private final WeakReference<Activity> activityRef;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout overlay;
    private TextView detailText;
    private boolean failedCurrentLoad;
    private boolean recovering;
    private int attempts;
    private String lastInternalUrl = START_URL;
    private Runnable retryRunnable;

    private CommunicationGuard(Activity activity, WebView webView) {
        this.activityRef = new WeakReference<>(activity);
        this.webView = webView;
        createOverlay(activity);
        installClient();
    }

    public static synchronized void install(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        WebView web = findWebView(activity.getWindow().getDecorView());
        if (web == null || INSTALLED.containsKey(web)) return;
        CommunicationGuard guard = new CommunicationGuard(activity, web);
        INSTALLED.put(web, guard);
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void createOverlay(Activity activity) {
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0xFF050505);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(activity, 26), dp(activity, 28), dp(activity, 26), dp(activity, 28));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF111111);
        cardBg.setCornerRadius(dp(activity, 24));
        cardBg.setStroke(dp(activity, 2), 0xFFFFC400);
        card.setBackground(cardBg);

        TextView brand = new TextView(activity);
        brand.setText("TURMA DO RANGO");
        brand.setTextColor(0xFFFFC400);
        brand.setTextSize(24);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, matchWrap());

        TextView title = new TextView(activity);
        title.setText("Falta de comunicação...");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(activity, 22);
        card.addView(title, titleLp);

        TextView message = new TextView(activity);
        message.setText("aguarde enquanto corrijo tudo por aqui.");
        message.setTextColor(0xFFD0D0D0);
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgLp = matchWrap();
        msgLp.topMargin = dp(activity, 8);
        card.addView(message, msgLp);

        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && spinner.getIndeterminateDrawable() != null) {
            spinner.getIndeterminateDrawable().setTint(0xFFFFC400);
        }
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42));
        spinLp.topMargin = dp(activity, 24);
        card.addView(spinner, spinLp);

        detailText = new TextView(activity);
        detailText.setText("Reconectando automaticamente...");
        detailText.setTextColor(0xFF8E8E8E);
        detailText.setTextSize(13);
        detailText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailLp = matchWrap();
        detailLp.topMargin = dp(activity, 12);
        card.addView(detailText, detailLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(activity, 24);
        cardLp.rightMargin = dp(activity, 24);
        overlay.addView(card, cardLp);

        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            // Fica acima do WebView, mas abaixo do overlay de atualização já existente.
            int index = Math.min(1, parent.getChildCount());
            parent.addView(overlay, index, overlayLp);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void installClient() {
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
                failedCurrentLoad = false;
                if (isInternal(url)) lastInternalUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                if (!failedCurrentLoad) {
                    connectionRestored();
                    String versionLabel = "Versão " + BuildConfig.VERSION_NAME;
                    String js = "(function(){var e=document.getElementById('tdrAppVersion');if(e)e.textContent="
                            + JSONObject.quote(versionLabel) + ";})();";
                    try { view.evaluateJavascript(js, null); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) communicationFailed("rede");
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                communicationFailed("rede");
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request == null || !request.isForMainFrame() || response == null) return;
                int status = response.getStatusCode();
                if (status >= 500 || status == 408 || status == 429) {
                    communicationFailed("servidor");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                if (sslHandler != null) sslHandler.cancel();
                communicationFailed("segurança");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                communicationFailed("processo");
                scheduleRetry(true);
                return true;
            }
        });
    }

    private void communicationFailed(String reason) {
        failedCurrentLoad = true;
        recovering = true;
        attempts++;
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            try { webView.stopLoading(); } catch (Exception ignored) {}
            if (overlay != null) overlay.setVisibility(View.VISIBLE);
            if (detailText != null) {
                if (attempts <= 1) detailText.setText("Reconectando automaticamente...");
                else detailText.setText("Reconectando... tentativa " + attempts);
            }
            scheduleRetry(false);
        });
    }

    private void scheduleRetry(boolean renderCrashed) {
        if (retryRunnable != null) handler.removeCallbacks(retryRunnable);
        long delay;
        if (attempts <= 1) delay = 1800L;
        else if (attempts == 2) delay = 3000L;
        else if (attempts == 3) delay = 4500L;
        else delay = 6500L;

        retryRunnable = () -> {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;
            if (renderCrashed) {
                // Nunca traz o aplicativo para frente só porque o processo do
                // WebView caiu em segundo plano. Aguarda o motoboy voltar ao app.
                if (!activity.hasWindowFocus()) {
                    scheduleRetry(true);
                    return;
                }
                try {
                    Intent restart = new Intent(activity, MainActivity.class);
                    restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    activity.startActivity(restart);
                    activity.finish();
                } catch (Exception ignored) {}
                return;
            }
            try {
                String target = isInternal(lastInternalUrl) ? lastInternalUrl : START_URL;
                webView.loadUrl(target + (target.contains("?") ? "&" : "?") + "tdr_retry=" + System.currentTimeMillis());
            } catch (Exception e) {
                communicationFailed("reinicio");
            }
        };
        handler.postDelayed(retryRunnable, delay);
    }

    private void connectionRestored() {
        if (!recovering && (overlay == null || overlay.getVisibility() != View.VISIBLE)) return;
        recovering = false;
        failedCurrentLoad = false;
        attempts = 0;
        if (retryRunnable != null) handler.removeCallbacks(retryRunnable);
        retryRunnable = null;
        Activity activity = activityRef.get();
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(() -> {
                if (overlay != null) overlay.setVisibility(View.GONE);
            });
        }
    }

    private boolean handleUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        if (isInternal(url)) {
            lastInternalUrl = url;
            return false;
        }
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return true;

        // Links externos só podem abrir outro app quando esta Activity estiver
        // realmente na frente. Isso impede intents periódicos de roubar o foco
        // do WhatsApp, Maps ou qualquer outro aplicativo em segundo plano.
        if (!activity.hasWindowFocus()) return true;

        try {
            if (url.startsWith("intent://")) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                try {
                    activity.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && !fallback.isEmpty()) {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                    }
                }
            } else {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        } catch (Exception ignored) {}
        return true;
    }

    private boolean isInternal(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            return host != null && (host.equalsIgnoreCase("turmadorango.com.br")
                    || host.equalsIgnoreCase("www.turmadorango.com.br"));
        } catch (Exception e) {
            return false;
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
