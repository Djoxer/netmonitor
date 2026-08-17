package dev.djoxer.netmonitor.ui;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import dev.djoxer.netmonitor.R;

public class AboutFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView about = view.findViewById(R.id.aboutText);
        String version = "?";
        try {
            PackageInfo p = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            version = p.versionName;
        } catch (Exception ignored) {}

        about.setText(
                "NetMonitor v" + version + "\n\n"
                        + "Non-root traffic monitor via local VpnService.\n"
                        + "• Live connections grouped by app\n"
                        + "• DNS / SNI hostnames\n"
                        + "• Global and per-app blocking\n"
                        + "• Async event log\n\n"
                        + "Package: dev.djoxer.netmonitor\n"
                        + "https://djoxer.dev"
        );
    }
}
