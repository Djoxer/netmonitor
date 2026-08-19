package dev.djoxer.netmonitor.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

public final class ThemePrefs {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREFS = "netmonitor_prefs";
    private static final String KEY_THEME = "theme_mode";

    private ThemePrefs() {}

    public static int getMode(Context context) {
        return prefs(context).getInt(KEY_THEME, MODE_DARK);
    }

    public static void setMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply();
        apply(mode);
    }

    public static void applyStored(Context context) {
        apply(getMode(context));
    }

    public static void apply(int mode) {
        int night;
        switch (mode) {
            case MODE_LIGHT:
                night = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case MODE_DARK:
                night = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            case MODE_SYSTEM:
            default:
                night = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(night);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
