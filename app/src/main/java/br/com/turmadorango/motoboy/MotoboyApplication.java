package br.com.turmadorango.motoboy;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

public class MotoboyApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private Activity resumedActivity;
    private boolean pendingRefresh = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver realtimeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!RealtimeService.ACTION_CHANGED.equals(intent.getAction())) return;
            pendingRefresh = true;
            refreshVisibleWebView();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        IntentFilter f = new IntentFilter(RealtimeService.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(realtimeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(realtimeReceiver, f);
        DeliveryCallManager.start(this);
        BackgroundUpdateManager.start(this);
    }

    private void refreshVisibleWebView() {
        Activity a = resumedActivity;
        if (a == null || !pendingRefresh) return;
        WebView web = findWebView(a.getWindow().getDecorView());
        if (web == null) {
            retryLater();
            return;
        }
        String url = web.getUrl();
        if (url == null || !url.contains("/includes/motoboy/tela_motoboy.php")) {
            retryLater();
            return;
        }

        String js = "(function(){return !!document.querySelector('.modal.show, textarea:focus, input:focus, select:focus');})()";
        web.evaluateJavascript(js, value -> {
            boolean busy = "true".equalsIgnoreCase(String.valueOf(value).replace("\"", ""));
            if (!busy && pendingRefresh && resumedActivity != null) {
                pendingRefresh = false;
                web.reload();
            } else if (pendingRefresh) {
                retryLater();
            }
        });
    }

    private void retryLater() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::refreshVisibleWebView, 3500L);
    }

    private WebView findWebView(View v) {
        if (v instanceof WebView) return (WebView)v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) {
                WebView w=findWebView(g.getChildAt(i));
                if(w!=null) return w;
            }
        }
        return null;
    }

    @Override public void onActivityResumed(Activity activity) {
        resumedActivity = activity;
        BackgroundUpdateManager.onActivityResumed(activity);
        if (activity instanceof MainActivity) {
            CommunicationGuard.install(activity);
        }
        refreshVisibleWebView();
    }

    @Override public void onActivityPaused(Activity activity) {
        BackgroundUpdateManager.onActivityPaused(activity);
        if (resumedActivity == activity) resumedActivity = null;
    }

    @Override public void onActivityDestroyed(Activity activity) {
        if (resumedActivity == activity) resumedActivity = null;
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (activity instanceof MainActivity) {
            handler.post(() -> CommunicationGuard.install(activity));
        }
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
