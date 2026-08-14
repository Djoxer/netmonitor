package dev.djoxer.netmonitor.root;

import android.util.Log;

public class RootManager {

    private static final String TAG = "RootManager";
    private static boolean libsuAvailable = false;

    static {
        try {
            // Only try to load the class – this will throw if the library is missing
            Class.forName("com.topjohnwu.superuser.Shell");
            libsuAvailable = true;
            Log.d(TAG, "libsu classes found");
        } catch (Throwable t) {
            libsuAvailable = false;
            Log.e(TAG, "libsu NOT found", t);
        }
    }

    public static boolean isLibsuAvailable() {
        return libsuAvailable;
    }

    public static void init() {
        if (!libsuAvailable) return;

        try {
            com.topjohnwu.superuser.Shell.enableVerboseLogging = true;
            com.topjohnwu.superuser.Shell.setDefaultBuilder(
                    com.topjohnwu.superuser.Shell.Builder.create()
                            .setTimeout(10)
            );
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            libsuAvailable = false;
        }
    }

    public static boolean isRootAvailable() {
        if (!libsuAvailable) return false;
        try {
            Boolean granted = com.topjohnwu.superuser.Shell.isAppGrantedRoot();
            return granted != null && granted;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String runCommand(String command) {
        if (!libsuAvailable) return "libsu not available";
        try {
            com.topjohnwu.superuser.Shell.Result result =
                    com.topjohnwu.superuser.Shell.cmd(command).exec();
            if (result.isSuccess()) {
                return String.join("\n", result.getOut());
            } else {
                return "Error: " + String.join("\n", result.getErr());
            }
        } catch (Throwable t) {
            return "Exception: " + t.getMessage();
        }
    }
}
