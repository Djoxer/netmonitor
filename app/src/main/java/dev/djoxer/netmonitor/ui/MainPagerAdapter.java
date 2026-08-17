package dev.djoxer.netmonitor.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return new LogFragment();
            case 2: return new SettingsFragment();
            case 3: return new AboutFragment();
            case 0:
            default: return new MonitorFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 4;
    }
}
