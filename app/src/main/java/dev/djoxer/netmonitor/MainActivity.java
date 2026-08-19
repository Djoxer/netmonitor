package dev.djoxer.netmonitor;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.ui.MainPagerAdapter;
import dev.djoxer.netmonitor.util.ThemePrefs;

public class MainActivity extends AppCompatActivity {

    private static final String[] TAB_TITLES = {"Monitor", "Log", "Settings", "About"};

    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_FORWARD = 1;
    public static final int STATUS_BLOCK = 2;

    private ImageView headerStatusIcon;
    private ObjectAnimator blinkAnimator;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemePrefs.applyStored(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView title = findViewById(R.id.titleText);
        if (title != null) {
            title.setTextColor(ContextCompat.getColor(this, R.color.md_theme_on_surface));
        }

        headerStatusIcon = findViewById(R.id.headerStatusIcon);
        setVpnStatus(STATUS_STOPPED);

        ViewPager2 pager = findViewById(R.id.viewPager);
        TabLayout tabs = findViewById(R.id.tabLayout);
        pager.setAdapter(new MainPagerAdapter(this));
        new TabLayoutMediator(tabs, pager, (tab, position) ->
                tab.setText(TAB_TITLES[position])).attach();
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

    @Override
    protected void onDestroy() {
        stopBlink();
        super.onDestroy();
    }
}