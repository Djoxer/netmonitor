package dev.djoxer.netmonitor;

import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import dev.djoxer.netmonitor.ui.MainPagerAdapter;

public class MainActivity extends FragmentActivity {

    private static final String[] TAB_TITLES = {"Monitor", "Log", "Settings", "About"};

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String version = "?";
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = p.versionName != null ? p.versionName : "?";
        } catch (Exception ignored) {}

        android.widget.TextView title = findViewById(R.id.titleText);
        title.setText("NetMonitor");

        ViewPager2 pager = findViewById(R.id.viewPager);
        TabLayout tabs = findViewById(R.id.tabLayout);

        pager.setAdapter(new MainPagerAdapter(this));
        new TabLayoutMediator(tabs, pager, (tab, position) ->
                tab.setText(TAB_TITLES[position])).attach();
    }
}