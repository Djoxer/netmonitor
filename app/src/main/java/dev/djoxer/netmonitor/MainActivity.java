package dev.djoxer.netmonitor;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.ui.MainPagerAdapter;
import dev.djoxer.netmonitor.util.ThemePrefs;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MainActivity extends AppCompatActivity {

    private static final int[] TAB_ICONS = {
            R.drawable.ic_tab_monitor,
            R.drawable.ic_tab_log,
            R.drawable.ic_tab_about,
            R.drawable.ic_tab_settings
    };

    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_FORWARD = 1;
    public static final int STATUS_BLOCK = 2;

    private ImageView headerStatusIcon;
    private ImageButton btnThemeToggle;
    private ObjectAnimator blinkAnimator;

    private TextView sessionTimerText;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    private long ignoreStoppedUntilMs = 0L;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            updateSessionTimer();
            timerHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemePrefs.applyStored(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupEdgeToEdge();

        headerStatusIcon = findViewById(R.id.headerStatusIcon);
        sessionTimerText = findViewById(R.id.sessionTimerText);
        btnThemeToggle = findViewById(R.id.btnThemeToggle);

        setVpnStatus(STATUS_STOPPED);
        updateThemeToggleIcon();

        if (btnThemeToggle != null) {
            btnThemeToggle.setOnClickListener(v -> toggleTheme());
        }

        ViewPager2 pager = findViewById(R.id.viewPager);
        TabLayout tabs = findViewById(R.id.tabLayout);
        pager.setAdapter(new MainPagerAdapter(this));
        new TabLayoutMediator(tabs, pager, (tab, position) -> {
            tab.setText((CharSequence) null);
            tab.setIcon(TAB_ICONS[position]);
        }).attach();
    }

    private void toggleTheme() {
        int mode = ThemePrefs.getMode(this);
        boolean currentlyLight = isEffectivelyLight(mode);
        // setMode already applies night mode and typically recreates the Activity
        ThemePrefs.setMode(this,
                currentlyLight ? ThemePrefs.MODE_DARK : ThemePrefs.MODE_LIGHT);
        // do not call recreate() here
    }

    private boolean isEffectivelyLight(int mode) {
        if (mode == ThemePrefs.MODE_LIGHT) return true;
        if (mode == ThemePrefs.MODE_DARK) return false;
        int night = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    private void updateThemeToggleIcon() {
        if (btnThemeToggle == null) return;
        int mode = ThemePrefs.getMode(this);
        boolean light = isEffectivelyLight(mode);
        // Light UI → show moon (switch to dark); Dark UI → show sun
        btnThemeToggle.setImageResource(light ? R.drawable.ic_theme_moon : R.drawable.ic_theme_sun);
        btnThemeToggle.setColorFilter(
                ContextCompat.getColor(this, R.color.md_theme_on_surface));
    }

    private void setupEdgeToEdge() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        boolean lightTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(lightTheme);
            insetsController.setAppearanceLightNavigationBars(lightTheme);
        }

        View root = findViewById(R.id.mainRoot);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                androidx.core.graphics.Insets bars =
                        insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }
    }

    public void markVpnStartPending() {
        ignoreStoppedUntilMs = System.currentTimeMillis() + 4000L;
        timerHandler.postDelayed(this::syncVpnStatusFromService, 400L);
        timerHandler.postDelayed(this::syncVpnStatusFromService, 1200L);
        timerHandler.postDelayed(this::syncVpnStatusFromService, 2500L);
    }

    public void setVpnStatus(int status) {
        if (headerStatusIcon == null) return;
        stopBlink();

        if (status == STATUS_FORWARD || status == STATUS_BLOCK) {
            headerStatusIcon.setImageResource(android.R.drawable.ic_media_play);
            int color = ContextCompat.getColor(this,
                    status == STATUS_BLOCK ? R.color.status_block : R.color.status_running);
            headerStatusIcon.setColorFilter(color);
            headerStatusIcon.setAlpha(1f);
            startBlink();
        } else {
            headerStatusIcon.setImageResource(android.R.drawable.ic_media_pause);
            headerStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_stopped));
            headerStatusIcon.setAlpha(1f);
        }
    }

    private void startBlink() {
        blinkAnimator = ObjectAnimator.ofFloat(headerStatusIcon, "alpha", 1f, 0.25f);
        blinkAnimator.setDuration(700);
        blinkAnimator.setRepeatMode(ValueAnimator.REVERSE);
        blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnimator.setInterpolator(new LinearInterpolator());
        blinkAnimator.start();
    }

    private void stopBlink() {
        if (blinkAnimator != null) {
            blinkAnimator.cancel();
            blinkAnimator = null;
        }
        if (headerStatusIcon != null) headerStatusIcon.setAlpha(1f);
    }

    public void syncVpnStatusFromService() {
        if (NetVpnService.isServiceRunning()) {
            if (NetVpnService.isBlockMode()) {
                setVpnStatus(STATUS_BLOCK);
            } else {
                setVpnStatus(STATUS_FORWARD);
            }
            ignoreStoppedUntilMs = 0L;
        } else if (System.currentTimeMillis() < ignoreStoppedUntilMs) {
            // keep pending start icon
        } else {
            setVpnStatus(STATUS_STOPPED);
        }
    }

    private void updateSessionTimer() {
        if (sessionTimerText == null) return;
        long elapsed = NetVpnService.getSessionElapsedMs();
        if (elapsed <= 0L || !NetVpnService.isServiceRunning()) {
            sessionTimerText.setVisibility(View.INVISIBLE);
            sessionTimerText.setText("00:00:00");
            return;
        }
        sessionTimerText.setVisibility(View.VISIBLE);
        long sec = elapsed / 1000L;
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        sessionTimerText.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s));
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncVpnStatusFromService();
        updateThemeToggleIcon();
        timerHandler.removeCallbacks(timerTick);
        timerHandler.post(timerTick);
    }

    @Override
    protected void onPause() {
        timerHandler.removeCallbacks(timerTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopBlink();
        super.onDestroy();
    }
}