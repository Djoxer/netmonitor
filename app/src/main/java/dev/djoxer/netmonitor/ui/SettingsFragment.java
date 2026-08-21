package dev.djoxer.netmonitor.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.block.ProfileManager;
import dev.djoxer.netmonitor.data.LogExporter;
import dev.djoxer.netmonitor.data.RuleRepository;
import dev.djoxer.netmonitor.data.entity.ProfileEntity;
import dev.djoxer.netmonitor.util.ThemePrefs;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class SettingsFragment extends Fragment {

    private RuleRepository ruleRepository;
    private LogExporter logExporter;
    private TextView status;
    private TextView activeProfileLabel;
    private Spinner profileSpinner;

    private final List<ProfileEntity> profileList = new ArrayList<>();
    private long selectedProfileId = -1L;
    private boolean suppressSpinnerCallback = false;

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
        activeProfileLabel = view.findViewById(R.id.activeProfileLabel);
        profileSpinner = view.findViewById(R.id.profileSpinner);

        Button btnClearLog = view.findViewById(R.id.btnClearLog);
        Button btnDeleteOld = view.findViewById(R.id.btnDeleteOldLogs);
        Button btnExportCsv = view.findViewById(R.id.btnExportCsv);
        Button btnExportJson = view.findViewById(R.id.btnExportJson);
        Button btnClearBlocks = view.findViewById(R.id.btnClearPermanentBlocks);
        Button btnClearSchedules = view.findViewById(R.id.btnClearSchedules);
        Button btnReload = view.findViewById(R.id.btnReloadRules);
        Button btnAddProfile = view.findViewById(R.id.btnAddProfile);
        Button btnDeleteProfile = view.findViewById(R.id.btnDeleteProfile);
        Button btnModeBlacklist = view.findViewById(R.id.btnProfileBlacklist);
        Button btnModeWhitelist = view.findViewById(R.id.btnProfileWhitelist);

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
                "All direction blocks on the active profile will be cleared. Schedules stay.",
                () -> ruleRepository.clearPermanentBlocksAsync(() ->
                        uiDone("Permanent blocks cleared."))));

        btnClearSchedules.setOnClickListener(v -> confirm(
                "Clear all schedules?",
                "All time-based block windows will be removed.",
                () -> ruleRepository.clearAllSchedulesAsync(() ->
                        uiDone("All schedules cleared."))));

        btnReload.setOnClickListener(v ->
                ruleRepository.loadIntoMemoryAsync(() -> {
                    reloadProfilesUi();
                    uiDone("Rules reloaded into memory.");
                }));

        btnAddProfile.setOnClickListener(v -> showAddProfileDialog());
        btnDeleteProfile.setOnClickListener(v -> confirm(
                "Delete active profile?",
                "Rules for this profile will be removed.",
                () -> {
                    long id = selectedProfileId;
                    if (id < 0) return;
                    ProfileManager.getInstance().deleteProfileAsync(requireContext(), id, () -> {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            reloadProfilesUi();
                            toastVpnRestartIfNeeded();
                            uiDone("Profile deleted.");
                        });
                    });
                }));

        btnModeBlacklist.setOnClickListener(v -> setActiveMode(ProfileEntity.MODE_BLACKLIST));
        btnModeWhitelist.setOnClickListener(v -> setActiveMode(ProfileEntity.MODE_WHITELIST));

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSpinnerCallback) return;
                if (position < 0 || position >= profileList.size()) return;
                ProfileEntity p = profileList.get(position);
                if (p.id == selectedProfileId) return;
                ProfileManager.getInstance().activateProfileAsync(requireContext(), p.id, () -> {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        selectedProfileId = p.id;
                        reloadProfilesUi();
                        toastVpnRestartIfNeeded();
                        uiDone("Profile activated. Restart VPN if bypass list changed.");
                    });
                });
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        ProfileManager.getInstance().ensureDefaultAndLoadAsync(requireContext(), () -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(this::reloadProfilesUi);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadProfilesUi();
    }

    private void reloadProfilesUi() {
        if (profileSpinner == null || getContext() == null) return;
        ProfileManager.getInstance().listProfilesAsync(requireContext(), (profiles, activeId) -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> bindProfiles(profiles, activeId));
        });
    }

    private void bindProfiles(List<ProfileEntity> profiles, long activeId) {
        profileList.clear();
        if (profiles != null) profileList.addAll(profiles);
        selectedProfileId = activeId;

        List<String> labels = new ArrayList<>();
        int selectedIndex = 0;
        String activeName = "-";
        for (int i = 0; i < profileList.size(); i++) {
            ProfileEntity p = profileList.get(i);
            String modeLabel = p.mode == ProfileEntity.MODE_WHITELIST ? "whitelist" : "blacklist";
            labels.add(p.name + " (" + modeLabel + ")");
            if (p.id == activeId) {
                selectedIndex = i;
                activeName = p.name + " · " + modeLabel;
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        suppressSpinnerCallback = true;
        profileSpinner.setAdapter(adapter);
        if (!labels.isEmpty()) {
            profileSpinner.setSelection(selectedIndex, false);
        }
        suppressSpinnerCallback = false;

        if (activeProfileLabel != null) {
            activeProfileLabel.setText("Active: " + activeName
                    + (BlockManager.getInstance().isWhitelistMode()
                    ? " (engine: whitelist)" : " (engine: blacklist)"));
        }
    }

    private void setActiveMode(int mode) {
        if (selectedProfileId < 0) return;
        ProfileManager.getInstance().setProfileModeAsync(
                requireContext(), selectedProfileId, mode, () -> {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        reloadProfilesUi();
                        uiDone(mode == ProfileEntity.MODE_WHITELIST
                                ? "Active profile: Whitelist"
                                : "Active profile: Blacklist");
                    });
                });
    }

    private void showAddProfileDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Profile name");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        String[] modes = {"Blacklist", "Whitelist"};
        final int[] chosen = {ProfileEntity.MODE_BLACKLIST};

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("New profile")
                .setView(input)
                .setSingleChoiceItems(modes, 0, (d, which) ->
                        chosen[0] = which == 1
                                ? ProfileEntity.MODE_WHITELIST
                                : ProfileEntity.MODE_BLACKLIST)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText() != null
                            ? input.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        Toast.makeText(requireContext(), "Name required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ProfileManager.getInstance().createProfileAsync(
                            requireContext(), name, chosen[0], () -> {
                                if (getActivity() == null) return;
                                getActivity().runOnUiThread(() -> {
                                    reloadProfilesUi();
                                    uiDone("Profile created: " + name);
                                });
                            });
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
    }

    private void toastVpnRestartIfNeeded() {
        if (NetVpnService.isServiceRunning()) {
            Toast.makeText(requireContext(),
                    "Restart VPN to apply bypass list for this profile",
                    Toast.LENGTH_LONG).show();
        }
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
