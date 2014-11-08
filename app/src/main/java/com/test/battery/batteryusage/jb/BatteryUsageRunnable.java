package com.test.battery.batteryusage.jb;

import com.test.battery.R;
import com.test.battery.batteryusage.jb.BatterySipper.DrainType;
import com.test.battery.batteryusage.IBatteryUsage.BatUsage;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;

import android.content.Context;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.os.BatteryStats;
import android.os.BatteryStats.Uid;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.telephony.SignalStrength;
import android.util.Log;
import android.util.SparseArray;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class BatteryUsageRunnable implements Runnable {

    private static final int MIN_POWER_THRESHOLD = 5;
    private static final int MAX_ITEMS_TO_LIST = 100;
    private static final boolean DEBUG = true;
    private static final String TAG = "BatteryUsageRunnable";
    private final ArrayList<BatUsage> mBatUsageList = new ArrayList<BatUsage>();
    private final List<BatterySipper> mUsageList = new ArrayList<BatterySipper>();
    private final List<BatterySipper> mWifiSippers = new ArrayList<BatterySipper>();
    private final List<BatterySipper> mBluetoothSippers = new ArrayList<BatterySipper>();
    IBatteryStats mBatteryInfo = null;
    BatteryStatsImpl mStats = null;
    private Context mContext;
    private int mLimit;
    private long mStatsPeriod = 0;
    private double mMaxPower = 1;
    private double mTotalPower;
    private double mWifiPower;
    private double mBluetoothPower;
    private PowerProfile mPowerProfile;
    // How much the apps together have left WIFI running.
    private long mAppWifiRunning;
    private ArrayList<BatterySipper> mRequestQueue = new ArrayList<BatterySipper>();
    private int mStatsType = BatteryStats.STATS_SINCE_CHARGED;
    private JellyBeanBatteryUsageListener mListener;

    public BatteryUsageRunnable(Context mContext) {
        super();
        this.mContext = mContext;
    }

    public static boolean isWifiOnly(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        return (cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false);
    }

    public void setListener(JellyBeanBatteryUsageListener listener) {
        mListener = listener;
    }

    @Override
    public void run() {
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService("batteryinfo"));
        mPowerProfile = new PowerProfile(mContext);
        refresh();
    }

    private void refresh() {
        byte[] data = null;
        try {
            data = mBatteryInfo.getStatistics();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(data, 0, data.length);
        parcel.setDataPosition(0);
        mStats = BatteryStatsImpl.CREATOR
                .createFromParcel(parcel);
        mStats.distributeWorkLocked(BatteryStats.STATS_SINCE_CHARGED);
        getBatteryInfo();
    }

    private void getBatteryInfo() {
        mMaxPower = 0;
        mTotalPower = 0;
        mWifiPower = 0;
        mBluetoothPower = 0;
        mAppWifiRunning = 0;

        // mAppListGroup.removeAll();
        mUsageList.clear();
        mWifiSippers.clear();
        mBluetoothSippers.clear();

        if (mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL) < 10) {
            return;
        }

        processAppUsage();
        processMiscUsage();

        Collections.sort(mUsageList);

        // out
        for (BatterySipper sipper : mUsageList) {
            sipper.getNameIcon();
            final double percentOfTotal = ((sipper.getSortValue() / mTotalPower) * 100);
            sipper.percent = percentOfTotal;
            String name = (sipper.defaultPackageName == null) ? "" : sipper.defaultPackageName;
            mBatUsageList.add(new BatUsage(name, name, Math.round(percentOfTotal * 100.0) / 100.0, sipper.drainType.name()));
        }
        mListener.onJellyBeanBatteryUsageReady(mBatUsageList);
    }

    public List<BatUsage> getBatUsageList() {
//        (new Handler()).postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                BatteryUsageRunnable.this.run();
//            }
//        }, 2000);
        return mBatUsageList;
    }

    private void processAppUsage() {
        SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                Context.SENSOR_SERVICE);
        final int which = mStatsType;
        final int speedSteps = mPowerProfile.getNumSpeedSteps();
        final double[] powerCpuNormal = new double[speedSteps];
        final long[] cpuSpeedStepTimes = new long[speedSteps];
        for (int p = 0; p < speedSteps; p++) {
            powerCpuNormal[p] = mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE, p);
        }
        final double averageCostPerByte = getAverageDataCost();
        long uSecTime = mStats.computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, which);
        long appWakelockTime = 0;
        BatterySipper osApp = null;
        mStatsPeriod = uSecTime;
        SparseArray<? extends Uid> uidStats = mStats.getUidStats();

        final int NU = uidStats.size();
        for (int iu = 0; iu < NU; iu++) {
            Uid u = uidStats.valueAt(iu);
            double power = 0;
            double highestDrain = 0;
            String packageWithHighestDrain = null;
            Map<String, ? extends Uid.Proc> processStats = u.getProcessStats();
            Log.i(TAG, "" + processStats.toString());
            long cpuTime = 0;
            long cpuFgTime = 0;
            long wakelockTime = 0;
            long gpsTime = 0;
            if (processStats.size() > 0) {
                // Process CPU time
                for (Map.Entry<String, ? extends Uid.Proc> ent : processStats
                        .entrySet()) {
                    if (DEBUG)
                        Log.i(TAG, "Process name = " + ent.getKey());
                    Uid.Proc ps = ent.getValue();
                    final long userTime = ps.getUserTime(which);
                    final long systemTime = ps.getSystemTime(which);
                    final long foregroundTime = ps.getForegroundTime(which);
                    cpuFgTime += foregroundTime * 10; // convert to millis
                    final long tmpCpuTime = (userTime + systemTime) * 10; // convert
                    // to
                    // millis
                    int totalTimeAtSpeeds = 0;
                    // Get the total first
                    for (int step = 0; step < speedSteps; step++) {
                        cpuSpeedStepTimes[step] = ps.getTimeAtCpuSpeedStep(step, which);
                        totalTimeAtSpeeds += cpuSpeedStepTimes[step];
                    }
                    if (totalTimeAtSpeeds == 0)
                        totalTimeAtSpeeds = 1;
                    // Then compute the ratio of time spent at each speed
                    double processPower = 0;
                    for (int step = 0; step < speedSteps; step++) {
                        double ratio = (double) cpuSpeedStepTimes[step] / totalTimeAtSpeeds;
                        processPower += ratio * tmpCpuTime * powerCpuNormal[step];
                    }
                    cpuTime += tmpCpuTime;
                    power += processPower;
                    if (packageWithHighestDrain == null
                            || packageWithHighestDrain.startsWith("*")) {
                        highestDrain = processPower;
                        packageWithHighestDrain = ent.getKey();
                    } else if (highestDrain < processPower
                            && !ent.getKey().startsWith("*")) {
                        highestDrain = processPower;
                        packageWithHighestDrain = ent.getKey();
                    }
                }
                if (DEBUG)
                    Log.i(TAG, "Max drain of " + highestDrain
                            + " by " + packageWithHighestDrain);
            }
            if (cpuFgTime > cpuTime) {
                if (DEBUG && cpuFgTime > cpuTime + 10000) {
                    Log.i(TAG, "WARNING! Cputime is more than 10 seconds behind Foreground time");
                }
                cpuTime = cpuFgTime; // Statistics may not have been gathered
                // yet.
            }
            power /= 1000;

            // Process wake lock usage
            Map<String, ? extends Uid.Wakelock> wakelockStats = u.getWakelockStats();
            for (Map.Entry<String, ? extends Uid.Wakelock> wakelockEntry : wakelockStats
                    .entrySet()) {
                Uid.Wakelock wakelock = wakelockEntry.getValue();
                // Only care about partial wake locks since full wake locks
                // are canceled when the user turns the screen off.
                BatteryStats.Timer timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
                if (timer != null) {
                    wakelockTime += timer.getTotalTimeLocked(uSecTime, which);
                }
            }
            wakelockTime /= 1000; // convert to millis
            appWakelockTime += wakelockTime;

            // Add cost of holding a wake lock
            power += (wakelockTime
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_AWAKE)) / 1000;

            // Add cost of data traffic
            // long tcpBytesReceived = u.getTcpBytesReceived(mStatsType);
            // Reflection.call(u.getClass() , u, "getTcpBytesReceived", null,
            // mStatsType);

            Method getTcpBytesReceived;
            long tcpBytesReceived = 0;
            try {
                getTcpBytesReceived = u.getClass().getMethod("getTcpBytesReceived", Integer.TYPE);
                tcpBytesReceived = (Long) getTcpBytesReceived.invoke(u, mStatsType);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }

            Method getTcpBytesSent;
            long tcpBytesSent = 0;
            try {
                getTcpBytesSent = u.getClass().getMethod("getTcpBytesSent", Integer.TYPE);
                tcpBytesSent = (Long) getTcpBytesSent.invoke(u, mStatsType);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }

            // long tcpBytesSent = u.getTcpBytesSent(mStatsType);
            // power += (tcpBytesReceived + tcpBytesSent) * averageCostPerByte;

            // Add cost of keeping WIFI running.
            long wifiRunningTimeMs = u.getWifiRunningTime(uSecTime, which) / 1000;
            mAppWifiRunning += wifiRunningTimeMs;
            power += (wifiRunningTimeMs
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / 1000;

            // Process Sensor usage
            Map<Integer, ? extends Uid.Sensor> sensorStats = u.getSensorStats();
            for (Map.Entry<Integer, ? extends Uid.Sensor> sensorEntry : sensorStats
                    .entrySet()) {
                Uid.Sensor sensor = sensorEntry.getValue();
                int sensorType = sensor.getHandle();
                BatteryStats.Timer timer = sensor.getSensorTime();
                long sensorTime = timer.getTotalTimeLocked(uSecTime, which) / 1000;
                double multiplier = 0;
                switch (sensorType) {
                    case Uid.Sensor.GPS:
                        multiplier = mPowerProfile.getAveragePower(PowerProfile.POWER_GPS_ON);
                        gpsTime = sensorTime;
                        break;
                    default:
                        android.hardware.Sensor sensorData =
                                sensorManager.getDefaultSensor(sensorType);
                        if (sensorData != null) {
                            multiplier = sensorData.getPower();
                            if (DEBUG) {
                                Log.i(TAG, "Got sensor " + sensorData.getName() + " with power = "
                                        + multiplier);
                            }
                        }
                }
                power += (multiplier * sensorTime) / 1000;
            }

            if (DEBUG)
                Log.i(TAG, "UID " + u.getUid() + ": power=" + power);

            // Add the app to the list if it is consuming power
            if (power != 0 || u.getUid() == 0) {
                BatterySipper app = new BatterySipper(mContext, mRequestQueue, null,
                        packageWithHighestDrain, DrainType.APP, 0, u,
                        new double[]{
                                power
                        }
                );
                app.cpuTime = cpuTime;
                app.gpsTime = gpsTime;
                app.wifiRunningTime = wifiRunningTimeMs;
                app.cpuFgTime = cpuFgTime;
                app.wakeLockTime = wakelockTime;
                app.tcpBytesReceived = tcpBytesReceived;
                app.tcpBytesSent = tcpBytesSent;
                if (u.getUid() == android.os.Process.WIFI_UID) {
                    mWifiSippers.add(app);
                } else if (u.getUid() == 2000) { // android.os.Process.BLUETOOTH_GID
                    // = 2000;
                    mBluetoothSippers.add(app);
                } else {
                    mUsageList.add(app);
                }
                if (u.getUid() == 0) {
                    osApp = app;
                }
            }
            if (u.getUid() == android.os.Process.WIFI_UID) {
                mWifiPower += power;
            } else if (u.getUid() == 2000) { // android.os.Process.BLUETOOTH_GID
                // is 2000
                mBluetoothPower += power;
            } else {
                if (power > mMaxPower)
                    mMaxPower = power;
                mTotalPower += power;
            }
            if (DEBUG)
                Log.i(TAG, "Added power = " + power);
        }

        // The device has probably been awake for longer than the screen on
        // time and application wake lock time would account for. Assign
        // this remainder to the OS, if possible.
        if (osApp != null) {
            long wakeTimeMillis = mStats.computeBatteryUptime(
                    SystemClock.uptimeMillis() * 1000, which) / 1000;
            wakeTimeMillis -= appWakelockTime - (mStats.getScreenOnTime(
                    SystemClock.elapsedRealtime(), which) / 1000);
            if (wakeTimeMillis > 0) {
                double power = (wakeTimeMillis
                        * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_AWAKE)) / 1000;
                osApp.wakeLockTime += wakeTimeMillis;
                osApp.value += power;
                osApp.values[0] += power;
                if (osApp.value > mMaxPower)
                    mMaxPower = osApp.value;
                mTotalPower += power;
            }
        }
    }

    private void processMiscUsage() {
        final int which = mStatsType;
        long uSecTime = SystemClock.elapsedRealtime() * 1000;
        final long uSecNow = mStats.computeBatteryRealtime(uSecTime, which);
        final long timeSinceUnplugged = uSecNow;
        if (DEBUG) {
            Log.i(TAG, "Uptime since last unplugged = " + (timeSinceUnplugged / 1000));
        }

        addPhoneUsage(uSecNow);
        addScreenUsage(uSecNow);
        addWiFiUsage(uSecNow);
        addBluetoothUsage(uSecNow);
        addIdleUsage(uSecNow); // Not including cellular idle power
        // Don't compute radio usage if it's a wifi-only device
        if (isWifiOnly(mContext)) {
            addRadioUsage(uSecNow);
        }
    }

    private void addPhoneUsage(long uSecNow) {
        long phoneOnTimeMs = mStats.getPhoneOnTime(uSecNow, mStatsType) / 1000;
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / 1000;
        addEntry("power_phone", DrainType.PHONE, phoneOnTimeMs,
                R.drawable.ic_launcher, phoneOnPower);
    }

    private void addScreenUsage(long uSecNow) {
        double power = 0;
        long screenOnTimeMs = mStats.getScreenOnTime(uSecNow, mStatsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            long brightnessTime = mStats.getScreenBrightnessTime(i, uSecNow, mStatsType) / 1000;
            power += screenBinPower * brightnessTime;
            if (DEBUG) {
                Log.i(TAG, "Screen bin power = " + (int) screenBinPower + ", time = "
                        + brightnessTime);
            }
        }
        power /= 1000; // To seconds
        addEntry("power_screen", DrainType.SCREEN, screenOnTimeMs,
                R.drawable.ic_launcher, power);
    }

    private void addRadioUsage(long uSecNow) {
        double power = 0;
        final int BINS = SignalStrength.NUM_SIGNAL_STRENGTH_BINS;
        long signalTimeMs = 0;
        for (int i = 0; i < BINS; i++) {
            long strengthTimeMs = mStats.getPhoneSignalStrengthTime(i, uSecNow, mStatsType) / 1000;
            power += strengthTimeMs / 1000
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ON, i);
            signalTimeMs += strengthTimeMs;
        }
        long scanningTimeMs = mStats.getPhoneSignalScanningTime(uSecNow, mStatsType) / 1000;
        power += scanningTimeMs / 1000 * mPowerProfile.getAveragePower(
                PowerProfile.POWER_RADIO_SCANNING);
        BatterySipper bs =
                addEntry("power_cell", DrainType.CELL,
                        signalTimeMs, R.drawable.ic_launcher, power);
        if (signalTimeMs != 0) {
            bs.noCoveragePercent = mStats.getPhoneSignalStrengthTime(0, uSecNow, mStatsType)
                    / 1000 * 100.0 / signalTimeMs;
        }
    }

    private void aggregateSippers(BatterySipper bs, List<BatterySipper> from, String tag) {
        for (int i = 0; i < from.size(); i++) {
            BatterySipper wbs = from.get(i);
            if (DEBUG)
                Log.i(TAG, tag + " adding sipper " + wbs + ": cpu=" + wbs.cpuTime);
            bs.cpuTime += wbs.cpuTime;
            bs.gpsTime += wbs.gpsTime;
            bs.wifiRunningTime += wbs.wifiRunningTime;
            bs.cpuFgTime += wbs.cpuFgTime;
            bs.wakeLockTime += wbs.wakeLockTime;
            bs.tcpBytesReceived += wbs.tcpBytesReceived;
            bs.tcpBytesSent += wbs.tcpBytesSent;
        }
    }

    private void addWiFiUsage(long uSecNow) {
        long onTimeMs = mStats.getWifiOnTime(uSecNow, mStatsType) / 1000;
        long runningTimeMs = mStats.getGlobalWifiRunningTime(uSecNow, mStatsType) / 1000;
        if (DEBUG)
            Log.i(TAG, "WIFI runningTime=" + runningTimeMs
                    + " app runningTime=" + mAppWifiRunning);
        runningTimeMs -= mAppWifiRunning;
        if (runningTimeMs < 0)
            runningTimeMs = 0;
        double wifiPower = (onTimeMs * 0 /* TODO */
                * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)
                + runningTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / 1000;
        if (DEBUG)
            Log.i(TAG, "WIFI power=" + wifiPower + " from procs=" + mWifiPower);
        BatterySipper bs = addEntry("power_wifi", DrainType.WIFI,
                runningTimeMs, R.drawable.ic_launcher, wifiPower + mWifiPower);
        aggregateSippers(bs, mWifiSippers, "WIFI");
    }

    private void addIdleUsage(long uSecNow) {
        long idleTimeMs = (uSecNow - mStats.getScreenOnTime(uSecNow, mStatsType)) / 1000;
        double idlePower = (idleTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE))
                / 1000;
        addEntry("power_idle", DrainType.IDLE, idleTimeMs,
                R.drawable.ic_launcher, idlePower);
    }

    private void addBluetoothUsage(long uSecNow) {
        long btOnTimeMs = mStats.getBluetoothOnTime(uSecNow, mStatsType) / 1000;
        double btPower = btOnTimeMs
                * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_ON)
                / 1000;
        int btPingCount = mStats.getBluetoothPingCount();
        btPower += (btPingCount
                * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_AT_CMD)) / 1000;
        BatterySipper bs = addEntry("power_bluetooth",
                DrainType.BLUETOOTH, btOnTimeMs, R.drawable.ic_launcher,
                btPower + mBluetoothPower);
        aggregateSippers(bs, mBluetoothSippers, "Bluetooth");
    }

    private double getAverageDataCost() {
        final long WIFI_BPS = 1000000; // TODO: Extract average bit rates from
        // system
        final long MOBILE_BPS = 200000; // TODO: Extract average bit rates from
        // system
        final double WIFI_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE)
                / 3600;
        final double MOBILE_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                / 3600;

        Method getMobileTcpBytesReceived;
        Method getMobileTcpBytesSent;
        long mobileData = 0;
        try {
            getMobileTcpBytesReceived = mStats.getClass().getMethod("getMobileTcpBytesReceived",
                    Integer.TYPE);
            getMobileTcpBytesSent = mStats.getClass().getMethod("getMobileTcpBytesSent",
                    Integer.TYPE);
            long r = (Long) getMobileTcpBytesReceived.invoke(mStats, mStatsType);
            long s = (Long) getMobileTcpBytesSent.invoke(mStats, mStatsType);
            mobileData = r + s;
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        Method getTotalTcpBytesReceived;
        Method getTotalTcpBytesSent;
        long wifiData = 0;
        try {
            getTotalTcpBytesReceived = mStats.getClass().getMethod("getTotalTcpBytesReceived",
                    Integer.TYPE);
            getTotalTcpBytesSent = mStats.getClass()
                    .getMethod("getTotalTcpBytesSent", Integer.TYPE);
            long r = (Long) getTotalTcpBytesReceived.invoke(mStats, mStatsType);
            long s = (Long) getTotalTcpBytesSent.invoke(mStats, mStatsType);
            wifiData = r + s - mobileData;
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        // final long mobileData = mStats.getMobileTcpBytesReceived(mStatsType)
        // +
        // mStats.getMobileTcpBytesSent(mStatsType);
        // final long wifiData = mStats.getTotalTcpBytesReceived(mStatsType) +
        // mStats.getTotalTcpBytesSent(mStatsType) - mobileData;
        final long radioDataUptimeMs = mStats.getRadioDataUptime() / 1000;
        final long mobileBps = radioDataUptimeMs != 0
                ? mobileData * 8 * 1000 / radioDataUptimeMs
                : MOBILE_BPS;

        double mobileCostPerByte = MOBILE_POWER / (mobileBps / 8);
        double wifiCostPerByte = WIFI_POWER / (WIFI_BPS / 8);
        if (wifiData + mobileData != 0) {
            return (mobileCostPerByte * mobileData + wifiCostPerByte * wifiData)
                    / (mobileData + wifiData);
        } else {
            return 0;
        }
    }

    private BatterySipper addEntry(String label, DrainType drainType, long time, int iconId,
                                   double power) {
        if (power > mMaxPower)
            mMaxPower = power;
        mTotalPower += power;
        BatterySipper bs = new BatterySipper(mContext, mRequestQueue, null,
                label, drainType, iconId, null, new double[]{
                power
        }
        );
        bs.usageTime = time;
        bs.iconId = iconId;
        mUsageList.add(bs);
        return bs;
    }

    public interface JellyBeanBatteryUsageListener {
        public void onJellyBeanBatteryUsageReady(ArrayList<BatUsage> usages);
    }

}
