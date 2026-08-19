package dev.djoxer.netmonitor.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.data.LogExporter;
import dev.djoxer.netmonitor.data.RuleRepository;

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
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void uiDone(String msg) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            status.setText(msg);
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }
}
