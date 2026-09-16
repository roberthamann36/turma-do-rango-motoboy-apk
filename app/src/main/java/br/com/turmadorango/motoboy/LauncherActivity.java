package br.com.turmadorango.motoboy;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class LauncherActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 2201;
    private static final long SPLASH_MIN_MS = 1850L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean opened = false;
    private boolean launchReady = false;

    private FrameLayout logoWrap;
    private TextView titleText;
    private TextView subtitleText;
    private TextView statusText;
    private ProgressBar loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFF050505);
        getWindow().setNavigationBarColor(0xFF050505);

        createSplashLayout();
        startRealtimeService();
        playEntranceAnimation();

        handler.postDelayed(() -> {
            launchReady = true;
            continueLaunch();
        }, SPLASH_MIN_MS);
    }

    private void createSplashLayout() {
        FrameLayout root = new FrameLayout(this);

        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF050505, 0xFF111111, 0xFF050505});
        root.setBackground(background);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(dp(28), dp(24), dp(28), dp(24));

        logoWrap = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setShape(GradientDrawable.OVAL);
        logoBg.setColor(0xFF111111);
        logoBg.setStroke(dp(3), 0xFFFFC400);
        logoWrap.setBackground(logoBg);
        logoWrap.setElevation(dp(12));
        logoWrap.setAlpha(0f);
        logoWrap.setScaleX(0.72f);
        logoWrap.setScaleY(0.72f);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int logoSize = dp(138);
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(logoSize, logoSize);
        logoLp.gravity = Gravity.CENTER;
        logoWrap.addView(logo, logoLp);

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(dp(188), dp(188));
        center.addView(logoWrap, wrapLp);

        TextView chip = new TextView(this);
        chip.setText("  MOTOBOY  ");
        chip.setTextSize(12);
        chip.setTextColor(0xFF101010);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setLetterSpacing(0.12f);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0xFFFFC400);
        chipBg.setCornerRadius(dp(40));
        chip.setBackground(chipBg);
        chip.setPadding(dp(13), dp(7), dp(13), dp(7));
        chip.setAlpha(0f);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = dp(22);
        center.addView(chip, chipLp);

        titleText = new TextView(this);
        titleText.setText("TURMA DO RANGO");
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(29);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setGravity(Gravity.CENTER);
        titleText.setLetterSpacing(0.04f);
        titleText.setAlpha(0f);
        titleText.setTranslationY(dp(14));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(17);
        center.addView(titleText, titleLp);

        subtitleText = new TextView(this);
        subtitleText.setText("Aplicativo oficial de entregas");
        subtitleText.setTextColor(0xFFB9B9B9);
        subtitleText.setTextSize(15);
        subtitleText.setGravity(Gravity.CENTER);
        subtitleText.setAlpha(0f);
        subtitleText.setTranslationY(dp(10));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(5);
        center.addView(subtitleText, subLp);

        loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && loading.getIndeterminateDrawable() != null) {
            loading.getIndeterminateDrawable().setTint(0xFFFFC400);
        }
        loading.setAlpha(0f);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        loadLp.topMargin = dp(30);
        center.addView(loading, loadLp);

        statusText = new TextView(this);
        statusText.setText("Conectando com a operação...");
        statusText.setTextColor(0xFFD5D5D5);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setAlpha(0f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(10);
        center.addView(statusText, statusLp);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        centerLp.gravity = Gravity.CENTER;
        root.addView(center, centerLp);

        TextView footer = new TextView(this);
        footer.setText("Entregas • Status • Notificações em tempo real");
        footer.setTextColor(0xFF777777);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setAlpha(0f);
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        footerLp.gravity = Gravity.BOTTOM;
        footerLp.leftMargin = dp(20);
        footerLp.rightMargin = dp(20);
        footerLp.bottomMargin = dp(28);
        root.addView(footer, footerLp);

        setContentView(root);

        root.setTag(new View[]{chip, footer});
    }

    private void playEntranceAnimation() {
        View[] tagged = (View[]) ((View) logoWrap.getParent().getParent()).getTag();
        View chip = tagged != null && tagged.length > 0 ? tagged[0] : null;
        View footer = tagged != null && tagged.length > 1 ? tagged[1] : null;

        AnimatorSet logoIn = new AnimatorSet();
        logoIn.playTogether(
                ObjectAnimator.ofFloat(logoWrap, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(logoWrap, View.SCALE_X, 0.72f, 1.06f),
                ObjectAnimator.ofFloat(logoWrap, View.SCALE_Y, 0.72f, 1.06f));
        logoIn.setDuration(620);
        logoIn.setInterpolator(new DecelerateInterpolator());

        AnimatorSet logoSettle = new AnimatorSet();
        logoSettle.playTogether(
                ObjectAnimator.ofFloat(logoWrap, View.SCALE_X, 1.06f, 1f),
                ObjectAnimator.ofFloat(logoWrap, View.SCALE_Y, 1.06f, 1f));
        logoSettle.setDuration(240);

        AnimatorSet texts = new AnimatorSet();
        texts.playTogether(
                ObjectAnimator.ofFloat(titleText, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(titleText, View.TRANSLATION_Y, dp(14), 0f),
                ObjectAnimator.ofFloat(subtitleText, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(subtitleText, View.TRANSLATION_Y, dp(10), 0f),
                ObjectAnimator.ofFloat(loading, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(statusText, View.ALPHA, 0f, 1f));
        texts.setDuration(430);
        texts.setInterpolator(new DecelerateInterpolator());

        AnimatorSet sequence = new AnimatorSet();
        sequence.playSequentially(logoIn, logoSettle, texts);
        sequence.start();

        if (chip != null) {
            chip.animate().alpha(1f).setStartDelay(720).setDuration(350).start();
        }
        if (footer != null) {
            footer.animate().alpha(1f).setStartDelay(1050).setDuration(420).start();
        }
    }

    private void startRealtimeService() {
        getSharedPreferences("tdr_realtime", MODE_PRIVATE)
                .edit().putBoolean("service_enabled", true).apply();

        Intent service = new Intent(this, RealtimeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
        } catch (Exception ignored) {
        }
    }

    private void continueLaunch() {
        if (!launchReady || opened) return;

        statusText.setText("Preparando notificações...");

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }

        openMain();
    }

    private void openMain() {
        if (opened) return;
        opened = true;
        statusText.setText("Tudo pronto. Abrindo seu painel...");

        handler.postDelayed(() -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 260L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) openMain();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
