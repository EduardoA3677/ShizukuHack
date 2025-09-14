package rikka.shizuku.shell;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.Os;
import android.text.TextUtils;

import java.io.File;

import dalvik.system.DexClassLoader;

public class ShizukuShellLoader {

    private static String[] args;
    private static String callingPackage;
    private static Handler handler;

    private static void requestForBinder() throws RemoteException {
        Binder binder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == 1) {
                    IBinder binder = data.readStrongBinder();

                    String sourceDir = data.readString();
                    if (binder != null) {
                        handler.post(() -> onBinderReceived(binder, sourceDir));
                    } else {
                        System.err.println("Server is not running");
                        System.err.flush();
                        System.exit(1);
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };

        Bundle data = new Bundle();
        data.putBinder("binder", binder);

        Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                .setPackage("moe.shizuku.privileged.api")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("data", data);

        IBinder amBinder = ServiceManager.getService("activity");
        IActivityManager am;
        if (Build.VERSION.SDK_INT >= 26) {
            am = IActivityManager.Stub.asInterface(amBinder);
        } else {
            am = ActivityManagerNative.asInterface(amBinder);
        }

        am.broadcastIntent(null, intent, null, null, 0, null, null,
                null, -1, null, true, false, 0);
    }

    private static void onBinderReceived(IBinder binder, String sourceDir) {
        String trimmedAbi = Build.SUPPORTED_ABIS[0];
        int index = trimmedAbi.indexOf("-");

        if (index != -1) {
            trimmedAbi = trimmedAbi.substring(0, index);
        }

        String librarySearchPath = sourceDir + "!/lib/" + Build.SUPPORTED_ABIS[0];
        String systemLibrarySearchPath = System.getProperty("java.library.path");
        if (!TextUtils.isEmpty(systemLibrarySearchPath)) {
            librarySearchPath += File.pathSeparatorChar + systemLibrarySearchPath;
        }
        librarySearchPath += File.pathSeparatorChar + sourceDir.replace("/base.apk", "") + "/lib/" + trimmedAbi;

        try {
            DexClassLoader classLoader = new DexClassLoader(sourceDir, ".", librarySearchPath, ClassLoader.getSystemClassLoader());
            Class<?> cls = classLoader.loadClass("moe.shizuku.manager.shell.Shell");
            cls.getDeclaredMethod("main", String[].class, String.class, IBinder.class, Handler.class)
                    .invoke(null, args, callingPackage, binder, handler);
        } catch (ClassNotFoundException tr) {
            System.err.println("Class not found");
            System.err.println("Make sure you have Shizuku v12.0.0 or above installed");
            System.err.flush();
            System.exit(1);
        } catch (Throwable tr) {
            tr.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }

    /**
     * Robust shell context detection for Android 15+ compatibility.
     * Checks multiple indicators to determine if running in ADB shell context.
     */
    private static boolean isShellContext() {
        int uid = Os.getuid();
        
        // Traditional ADB shell UID check (works for Android 14 and below)
        if (uid == 2000) {
            return true;
        }
        
        // Android 15+ may use different UIDs for ADB shell sessions
        // Check for shell-related environment variables and context
        String user = System.getenv("USER");
        String shell = System.getenv("SHELL");
        String adbVendorKeys = System.getenv("ADB_VENDOR_KEYS");
        
        // Check if running as shell user or in shell environment
        if ("shell".equals(user) || 
            (shell != null && shell.contains("sh")) ||
            adbVendorKeys != null) {
            return true;
        }
        
        // Additional check for Android 15+: Check if UID is in the shell range
        // On some Android 15 devices, shell processes may have UID in different ranges
        if (Build.VERSION.SDK_INT >= 35) { // Android 15 (API 35)
            // Check for extended shell UID ranges or alternative indicators
            if (uid >= 2000 && uid <= 2999) { // Extended shell UID range
                return true;
            }
            
            // Check process name or parent process (if accessible)
            try {
                String process = System.getProperty("sun.java.command");
                if (process != null && (process.contains("shell") || process.contains("adb"))) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        
        return false;
    }

    public static void main(String[] args) {
        ShizukuShellLoader.args = args;

        String packageName;
        if (isShellContext()) {
            packageName = "com.android.shell";
            // Debug logging for Android 15+ shell detection
            if (Build.VERSION.SDK_INT >= 35) {
                System.err.println("Android 15+ detected: Using shell context with UID " + Os.getuid());
                System.err.flush();
            }
        } else {
            packageName = System.getenv("RISH_APPLICATION_ID");
            if (TextUtils.isEmpty(packageName) || "PKG".equals(packageName)) {
                abort("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)");
                System.exit(1);
            }
        }

        ShizukuShellLoader.callingPackage = packageName;

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        handler = new Handler(Looper.getMainLooper());

        try {
            requestForBinder();
        } catch (Throwable tr) {
            tr.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }

        handler.postDelayed(() -> abort(
                String.format(
                        "Request timeout. The connection between the current app (%1$s) and Shizuku app may be blocked by your system. " +
                                "Please disable all battery optimization features for both current app (%1$s) and Shizuku app. " +
                                "On Android 15+, ensure ADB shell permissions are properly granted.",
                        packageName)
        ), Build.VERSION.SDK_INT >= 35 ? 10000 : 5000); // Longer timeout for Android 15+

        Looper.loop();
        System.exit(0);
    }

    private static void abort(String message) {
        System.err.println(message);
        System.err.flush();
        System.exit(1);
    }
}
