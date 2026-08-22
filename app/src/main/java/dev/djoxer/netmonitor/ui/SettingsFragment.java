package dev.djoxer.netmonitor.ui;

import android.content.Context;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
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
    private TextView blockModeLabel;
    private Switch switchBlockMode;
    private Spinner profileSpinner;
    private ImageButton btnAddProfile;

    private final List<ProfileEntity> profileList = new ArrayList<>();
    private long selectedProfileId = -1L;
    private boolean suppressSpinnerCallback = false;
    private boolean suppressModeSwitch = false;

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
        blockModeLabel = view.findViewById(R.id.blockModeLabel);
        switchBlockMode = view.findViewById(R.id.switchBlockMode);
        profileSpinner = view.findViewById(R.id.profileSpinner);
        btnAddProfile = view.findViewById(R.id.btnAddProfile);

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

        final boolean[] themeReady = {false};
        themeGroup.setOnCheckedChangeListener(null);
        int mode = ThemePrefs.getMode(requireContext());
        if (mode == ThemePrefs.MODE_LIGHT) themeLight.setChecked(true);
        else if (mode == ThemePrefs.MODE_SYSTEM) themeSystem.setChecked(true);
        else themeDark.setChecked(true);

        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (!themeReady[0] || !isAdded()) return;
            int newMode = ThemePrefs.MODE_DARK;
            if (checkedId == R.id.themeLight) newMode = ThemePrefs.MODE_LIGHT;
            else if (checkedId == R.id.themeSystem) newMode = ThemePrefs.MODE_SYSTEM;
            ThemePrefs.setMode(requireContext(), newMode);
        });
        themeGroup.post(() -> themeReady[0] = true);

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

        if (btnAddProfile != null) {
            btnAddProfile.setOnClickListener(v -> showAddProfileDialog());
        }

        if (switchBlockMode != null) {
            switchBlockMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressModeSwitch || !isAdded()) return;
                if (selectedProfileId < 0) return;
                int newMode = isChecked
                        ? ProfileEntity.MODE_WHITELIST
                        : ProfileEntity.MODE_BLACKLIST;
                ProfileManager.getInstance().setProfileModeAsync(
                        requireContext(), selectedProfileId, newMode, () -> {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                updateBlockModeUi();
                                reloadProfilesUi();
                                uiDone(isChecked
                                        ? "Active profile: Whitelist"
                                        : "Active profile: Blacklist");
                            });
                        });
            });
        }

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
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

    private void updateBlockModeUi() {
        if (!isAdded()) return;
        boolean whitelist = BlockManager.getInstance().isWhitelistMode();
        if (blockModeLabel != null) {
            blockModeLabel.setText(whitelist ? "engine: whitelist" : "engine: blacklist");
        }
        if (switchBlockMode != null) {
            suppressModeSwitch = true;
            switchBlockMode.setChecked(whitelist);
            suppressModeSwitch = false;
        }
    }

    private void reloadProfilesUi() {
        if (!isAdded() || profileSpinner == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        ProfileManager.getInstance().listProfilesAsync(ctx, (profiles, activeId) -> {
            if (!isAdded()) return;
            View v = getView();
            if (v == null) return;
            v.post(() -> {
                if (!isAdded() || getContext() == null || profileSpinner == null) return;
                bindProfiles(profiles, activeId);
            });
        });
    }

    private void bindProfiles(List<ProfileEntity> profiles, long activeId) {
        if (!isAdded() || profileSpinner == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        profileList.clear();
        if (profiles != null) profileList.addAll(profiles);
        selectedProfileId = activeId;

        int selectedIndex = 0;
        for (int i = 0; i < profileList.size(); i++) {
            if (profileList.get(i).id == activeId) {
                selectedIndex = i;
                break;
            }
        }

        ProfileSpinnerAdapter adapter = new ProfileSpinnerAdapter(ctx, profileList, activeId);
        suppressSpinnerCallback = true;
        profileSpinner.setAdapter(adapter);
        if (!profileList.isEmpty()) {
            profileSpinner.setSelection(selectedIndex, false);
        }
        suppressSpinnerCallback = false;

        updateBlockModeUi();
    }

    private boolean isDefaultProfile(ProfileEntity p) {
        return p != null && p.name != null && p.name.equalsIgnoreCase("Default");
    }

    private void onDeleteProfile(ProfileEntity p) {
        if (p == null || isDefaultProfile(p)) return;
        confirm(
                "Delete profile?",
                "Delete \"" + p.name + "\" and its rules?",
                () -> ProfileManager.getInstance().deleteProfileAsync(
                        requireContext(), p.id, () -> {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                reloadProfilesUi();
                                toastVpnRestartIfNeeded();
                                uiDone("Profile deleted.");
                            });
                        }));
    }

    private void onResetDefault(ProfileEntity p) {
        if (p == null) return;
        confirm(
                "Reset Default profile?",
                "All rules on Default will be cleared.",
                () -> ProfileManager.getInstance().resetProfileAsync(
                        requireContext(), p.id, () -> {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                reloadProfilesUi();
                                toastVpnRestartIfNeeded();
                                uiDone("Default profile reset.");
                            });
                        }));
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

        dialog.setOnShowListener(d -> {
            final int gapPx = (int) (16 * getResources().getDisplayMetrics().density);
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            int outline = ContextCompat.getColor(requireContext(), R.color.md_theme_outline);
            int onSurface = ContextCompat.getColor(requireContext(), R.color.md_theme_on_surface);

            if (positive != null) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) positive.getLayoutParams();
                lp.setMarginStart(gapPx);
                positive.setLayoutParams(lp);
                positive.setTextColor(onSurface);
                if (positive.getBackground() != null) {
                    positive.getBackground().setTint(outline);
                }
            }
            if (negative != null) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) negative.getLayoutParams();
                lp.setMarginEnd(gapPx);
                negative.setLayoutParams(lp);
                negative.setTextColor(onSurface);
                if (negative.getBackground() != null) {
                    negative.getBackground().setTint(outline);
                }
            }
        });
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
        if (status != null) status.setText("Exporting…");
        logExporter.exportAsync(format, new LogExporter.Callback() {
            @Override
            public void onSuccess(Intent shareIntent) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    try {
                        startActivity(shareIntent);
                        if (status != null) {
                            status.setText(format == LogExporter.Format.CSV
                                    ? "CSV export ready."
                                    : "JSON export ready.");
                        }
                    } catch (Exception e) {
                        if (status != null) {
                            status.setText("Share failed: " + e.getMessage());
                        }
                        Toast.makeText(requireContext(), "Share failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (status != null) status.setText("Export error: " + message);
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
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            if (status != null) status.setText(msg);
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------------------------
    // Spinner adapter: active = green check, Default = reset, else trash
    // ------------------------------------------------------------------

    private final class ProfileSpinnerAdapter extends ArrayAdapter<ProfileEntity> {

        private final long activeId;
        private final LayoutInflater inflater;

        ProfileSpinnerAdapter(Context context, List<ProfileEntity> data, long activeId) {
            super(context, 0, data);
            this.activeId = activeId;
            this.inflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_profile_spinner, parent, false);
            }
            TextView tv = convertView.findViewById(R.id.profileSpinnerText);
            ImageView check = convertView.findViewById(R.id.profileSpinnerCheck);
            ProfileEntity p = getItem(position);
            if (tv != null && p != null) {
                String modeLabel = p.mode == ProfileEntity.MODE_WHITELIST
                        ? "whitelist" : "blacklist";
                tv.setText(p.name + " (" + modeLabel + ")");
                boolean active = p.id == activeId;
                tv.setTextColor(ContextCompat.getColor(getContext(),
                        active ? R.color.profile_active : R.color.md_theme_on_surface));
                if (check != null) {
                    check.setVisibility(active ? View.VISIBLE : View.GONE);
                }
            }
            return convertView;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView,
                                    @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_profile_dropdown, parent, false);
            }
            TextView name = convertView.findViewById(R.id.profileName);
            ImageView action = convertView.findViewById(R.id.profileAction);
            ProfileEntity p = getItem(position);
            if (p == null) return convertView;

            String modeLabel = p.mode == ProfileEntity.MODE_WHITELIST
                    ? "whitelist" : "blacklist";
            name.setText(p.name + " (" + modeLabel + ")");

            boolean active = p.id == activeId;
            boolean isDefault = isDefaultProfile(p);

            // Default always offers reset (even when active)
            if (isDefault) {
                name.setTextColor(ContextCompat.getColor(getContext(),
                        active ? R.color.profile_active : R.color.md_theme_on_surface));
                action.setImageResource(R.drawable.ic_profile_reset);
                action.setClickable(true);
                action.setOnClickListener(v -> onResetDefault(p));
            } else if (active) {
                name.setTextColor(ContextCompat.getColor(getContext(), R.color.profile_active));
                action.setImageResource(R.drawable.ic_profile_check);
                action.setOnClickListener(null);
                action.setClickable(false);
            } else {
                name.setTextColor(ContextCompat.getColor(getContext(), R.color.md_theme_on_surface));
                action.setImageResource(R.drawable.ic_profile_trash);
                action.setClickable(true);
                action.setOnClickListener(v -> onDeleteProfile(p));
            }

            return convertView;
        }
    }
}
