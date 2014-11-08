package com.test.battery.batteryusage.kk;

import com.test.battery.batteryusage.IBatteryUsage.BatUsage;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BatUsageService retrieves power usage summary for API >= KitKat.
 */
public class BatUsageService extends Service {

    // private static final String TAG = "BatUsageService";
    // private static final int MIN_POWER_THRESHOLD = 5;
    private static final int MIN_POWER_THRESHOLD = 0;

    private ConcurrentHashMap<Integer, BatUsage>
            mUsageMap = new ConcurrentHashMap<Integer, BatUsage>();

    private BatUsageListener mListener;

    // private final List<BatUsage> mBatUsageList = new ArrayList<BatUsage>();
    private ArrayList<BatUsage> mBatUsageList;

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BatteryStatsHelper.MSG_UPDATE_NAME_ICON:
                    BatterySipper bs = (BatterySipper) msg.obj;
                    int k = bs.uidObj.getUid();
                    if (mUsageMap.containsKey(k)) {
                        BatUsage usage = mUsageMap.get(k);
                        usage.name = bs.name;
                        usage.packageName = bs.defaultPackageName;
                    }
                    break;
                case BatteryStatsHelper.MSG_REPORT_FULLY_DRAWN:
                    mBatUsageList = new ArrayList<BatUsage>(mUsageMap.values());
                    Collections.sort(mBatUsageList);
                    if (mListener != null) {
                        mListener.onKitkatBatUsageReady(mBatUsageList);
                    }
                    break;
            }
            super.handleMessage(msg);
        }
    };

    private final IBinder mBinder = new BatUsageBinder();

    private BatteryStatsHelper mStatsHelper;

    public BatUsageService() {
    }

    public void setBatUsageListener(BatUsageListener listener) {
        mListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mStatsHelper = new BatteryStatsHelper(this, mHandler);
        mStatsHelper.create();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatsHelper.destroy();
    }

//    @Override
//    public int onStartCommand(Intent intent, int flags, int startId) {
//        return super.onStartCommand(intent, flags, startId);
//    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO:
        // refreshStats();
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    //
//
//    @Override
//    public boolean onUnbind(Intent intent) {
////        stopSelf();
//        Log.v("BatUsageService", "onUnbind");
//        return super.onUnbind(intent);
//    }

    /**
     * Refresh the power usage statistics.
     */
    public void refreshStats() {
        mBatUsageList = null;
        mUsageMap = new ConcurrentHashMap<Integer, BatUsage>();
        mStatsHelper.refreshStats(false);
        final List<BatterySipper> usageList = mStatsHelper.getUsageList();
        Random r = new Random();
        for (BatterySipper sipper : usageList) {
            if (sipper.getSortValue() < MIN_POWER_THRESHOLD) {
                continue;
            }
            final double percentOfTotal =
                    ((sipper.getSortValue() / mStatsHelper.getTotalPower()) * 100);
            if (percentOfTotal < 1) {
                continue;
            }
            sipper.percent = percentOfTotal;
            String pkgName = ((sipper.defaultPackageName == null) ? ""
                    : sipper.defaultPackageName);
            int k = sipper.getUid(); // all system app's Uids are 0;
            if (mUsageMap.containsKey(k)) {
                k = r.nextInt(1000);
            }
            mUsageMap.put(k,
                    new BatUsage(pkgName, sipper.name, Math.round(percentOfTotal * 100.0) / 100.0,
                            sipper.drainType.name()));
        }
    }

    public ArrayList<BatUsage> getBatUsageList() {
        return mBatUsageList;
    }

    public interface BatUsageListener {

        public void onKitkatBatUsageReady(ArrayList<BatUsage> batUsages);
    }

    public class BatUsageBinder extends Binder {

        public BatUsageService getService() {
            return BatUsageService.this;
        }
    }

}
