package com.test.battery.batteryusage;

import com.test.battery.batteryusage.jb.BatteryUsageRunnable;
import com.test.battery.batteryusage.kk.BatUsageService;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.util.Pair;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Created by prt2121 on 11/7/14.
 */
public class BatteryUsageImpl
        implements IBatteryUsage, BatUsageService.BatUsageListener,
        BatteryUsageRunnable.JellyBeanBatteryUsageListener {

    public static final String BATTERY_USAGE_DEVINFO_FEATURE = "batteryusage";

    private Context mContext;

    private BatUsageService mBatUsageService;

    private boolean mBatUsageBound = false;

    private final ServiceConnection mBatUsageConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            BatUsageService.BatUsageBinder binder = (BatUsageService.BatUsageBinder) service;
            mBatUsageService = binder.getService();
            mBatUsageBound = true;
            retrieveBatUsageKitKat();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBatUsageBound = false;
        }
    };

    private List<Pair<String, String>> mUsage = new ArrayList<Pair<String, String>>();

    private BatteryUsageListener mBatteryUsageListener;

    private BatteryUsageRunnable mJellyBeanBatUsage;

    public BatteryUsageImpl(Context context) {
        mContext = context;
    }

    @Override
    public void onJellyBeanBatteryUsageReady(ArrayList<BatUsage> usages) {
        mBatteryUsageListener.onUsageReady(usages);
    }

    public String getTimeSinceLastBoot() {
        long dur = android.os.SystemClock.elapsedRealtime();
        return millisecondsToDaysHoursMinutes(dur);
    }

    public String getLastPowerOnTime() {
        long dur = android.os.SystemClock.elapsedRealtime();
        long ms = System.currentTimeMillis() - dur;
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(
                new Date(ms));
    }

    public String millisecondsToDaysHoursMinutes(long ms) {
        long days = TimeUnit.MILLISECONDS.toDays(ms);
        long hours = TimeUnit.MILLISECONDS.toHours(ms) - TimeUnit.DAYS.toHours(days);
        long mins = TimeUnit.MILLISECONDS.toMinutes(ms) - TimeUnit.DAYS.toMinutes(days)
                - TimeUnit.HOURS.toMinutes(hours);
        if (days == 0 && hours == 0) {
            return String.format(Locale.getDefault(), "%d mins", mins);
        } else if (days == 0) {
            return String.format(Locale.getDefault(), "%d hours, %d mins", hours, mins);
        } else {
            return String.format(Locale.getDefault(), "%d days %d hours, %d mins", days, hours,
                    mins);
        }
    }

    @Override
    public void getBatteryUsage(BatteryUsageListener batteryUsageListener) {
        mBatteryUsageListener = batteryUsageListener;
        if (BatUsageUtil.isPermissionGranted(mContext)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Intent intent = new Intent(mContext, BatUsageService.class);
                mContext.bindService(intent, mBatUsageConnection, Context.BIND_AUTO_CREATE);
            } else {
                mJellyBeanBatUsage = new BatteryUsageRunnable(mContext);
                mJellyBeanBatUsage.setListener(this);
                new Thread(mJellyBeanBatUsage).start();
            }
        } else {
            throw new SecurityException("Permission is not Granted.");
        }
    }

    private void retrieveBatUsageKitKat() {
        mBatUsageService.setBatUsageListener(BatteryUsageImpl.this);
        mBatUsageService.refreshStats();
    }

    @Override
    public void onKitkatBatUsageReady(ArrayList<BatUsage> batUsages) {
        mBatteryUsageListener.onUsageReady(batUsages);
        if (mBatUsageBound) {
            mContext.unbindService(mBatUsageConnection);
        }
    }
}
