package dev.djoxer.netmonitor;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.Bundle;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import dev.djoxer.netmonitor.ui.MainPagerAdapter;

public class MainActivity extends FragmentActivity {

    private static final String[] TAB_TITLES = {"Monitor", "Log", "Settings", "About"};

    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_FORWARD = 1;
    public static final int STATUS_BLOCK = 2;

    private ImageView headerStatusIcon;
    private ObjectAnimator blinkAnimator;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        headerStatusIcon = findViewById(R.id.headerStatusIcon);
        setVpnStatus(STATUS_STOPPED);

        ViewPager2 pager = findViewById(R.id.viewPager);
        TabLayout tabs = findViewById(R.id.tabLayout);

        pager.setAdapter(new MainPagerAdapter(this));
        new TabLayoutMediator(tabs, pager, (tab, position) ->
                tab.setText(TAB_TITLES[position])).attach();
    }

    /**
     * Called from MonitorFragment when VPN starts/stops.
     */
    public void setVpnStatus(int status) {
        if (headerStatusIcon == null) return;

        stopBlink();

        if (status == STATUS_FORWARD || status == STATUS_BLOCK) {
            headerStatusIcon.setImageResource(android.R.drawable.ic_media_play);
            int color = (status == STATUS_BLOCK) ? Color.parseColor("#FF5722") : Color.parseColor("#4CAF50");
            headerStatusIcon.setColorFilter(color);
            headerStatusIcon.setAlpha(1f);
            startBlink();
        } else {
            headerStatusIcon.setImageResource(android.R.drawable.ic_media_pause);
            headerStatusIcon.setColorFilter(Color.parseColor("#F44336"));
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
        if (headerStatusIcon != null) {
            headerStatusIcon.setAlpha(1f);
        }
    }

    @Override
    protected void onDestroy() {
        stopBlink();
        super.onDestroy();
    }
}