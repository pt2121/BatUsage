package com.test.battery.batteryusage;

import android.content.Context;
import android.content.pm.PackageManager;

public class BatUsageUtil {

    /**
     * Check BATTERY_STATS permission.
     *
     * @param ctx
     * @return true if BATTERY_STATS is granted.
     */
    public static boolean isPermissionGranted(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        int perm = pm.checkPermission(
                android.Manifest.permission.BATTERY_STATS,
                ctx.getPackageName());
        return (perm == PackageManager.PERMISSION_GRANTED);
    }
}
