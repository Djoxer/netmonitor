package dev.djoxer.netmonitor.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.data.LogExporter;
import dev.djoxer.netmonitor.data.RuleRepository;
import dev.djoxer.netmonitor.util.ThemePrefs;

public class SettingsFragment extends Fragment {

    private RuleRepository ruleRepository;
    private LogExporter logExporter;
    private TextView status;

    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ruleRepository = new RuleRepository(requireContext());
        logExporter = new LogExporter(requireContext());
        status = view.findViewById(R.id.settingsStatus);

        Button btnClearLog = view.findViewById(R.id.btnClearLog);
        Button btnDeleteOld = view.findViewById(R.id.btnDeleteOldLogs);
        Button btnExportCsv = view.findViewById(R.id.btnExportCsv);
        Button btnExportJson = view.findViewById(R.id.btnExportJson);
        Button btnClearBlocks = view.findViewById(R.id.btnClearPermanentBlocks);
        Button btnClearSchedules = view.findViewById(R.id.btnClearSchedules);
        Button btnReload = view.findViewById(R.id.btnReloadRules);

        RadioGroup themeGroup = view.findViewById(R.id.themeGroup);
        RadioButton themeSystem = view.findViewById(R.id.themeSystem);
        RadioButton themeLight = view.findViewById(R.id.themeLight);
        RadioButton themeDark = view.findViewById(R.id.themeDark);

        int mode = ThemePrefs.getMode(requireContext());
        if (mode == ThemePrefs.MODE_LIGHT) themeLight.setChecked(true);
        else if (mode == ThemePrefs.MODE_SYSTEM) themeSystem.setChecked(true);
        else themeDark.setChecked(true);

        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode = ThemePrefs.MODE_DARK;
            if (checkedId == R.id.themeLight) newMode = ThemePrefs.MODE_LIGHT;
            else if (checkedId == R.id.themeSystem) newMode = ThemePrefs.MODE_SYSTEM;
            
            if (newMode != ThemePrefs.getMode(requireContext())) {
                ThemePrefs.setMode(requireContext(), newMode);
                requireActivity().recreate();
            }
        });

        btnClearLog.setOnClickListener(v -> confirm(
                "Clear event log?",
                "All log entries will be deleted.",
                () -> ruleRepository.clearLogAsync(() -> uiDone("Event log cleared."))));

        btnDeleteOld.setOnClickListener(v ->
                ruleRepository.deleteOldLogsAsync(SEVEN_DAYS_MS, () ->
                        uiDone("Logs older than 7 days deleted.")));

        btnExportCsv.setOnClickListener(v -> export(LogExporter.Format.CSV));
        btnExportJson.setOnClickListener(v -> export(LogExporter.Format.JSON));

        btnClearBlocks.setOnClickListener(v -> confirm(
                "Clear permanent blocks?",
                "All permanently blocked apps will be allowed again. Schedules stay.",
                () -> ruleRepository.clearPermanentBlocksAsync(() ->
                        uiDone("Permanent blocks cleared."))));

        btnClearSchedules.setOnClickListener(v -> confirm(
                "Clear all schedules?",
                "All time-based block windows will be removed.",
                () -> ruleRepository.clearAllSchedulesAsync(() ->
                        uiDone("All schedules cleared."))));

        btnReload.setOnClickListener(v ->
                ruleRepository.loadIntoMemoryAsync(() ->
                        uiDone("Rules reloaded into memory.")));
    }

    private void export(LogExporter.Format format) {
        status.setText("Exporting…");
        logExporter.exportAsync(format, new LogExporter.Callback() {
            @Override
            public void onSuccess(Intent shareIntent) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    try {
                        startActivity(shareIntent);
                        status.setText(format == LogExporter.Format.CSV
                                ? "CSV export ready."
                                : "JSON export ready.");
                    } catch (Exception e) {
                        status.setText("Share failed: " + e.getMessage());
                        Toast.makeText(requireContext(), "Share failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    status.setText("Export error: " + message);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirm(String title, String message, Runnable action) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> action.run())
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            final int gapPx = (int) (12 * getResources().getDisplayMetrics().density);

            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

            if (positive != null) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) positive.getLayoutParams();
                lp.setMarginStart(gapPx);
                positive.setLayoutParams(lp);
            }
            if (negative != null) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) negative.getLayoutParams();
                lp.setMarginEnd(gapPx);
                negative.setLayoutParams(lp);
            }
        });

        dialog.show();
    }

    private void uiDone(String msg) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            status.setText(msg);
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }
}
