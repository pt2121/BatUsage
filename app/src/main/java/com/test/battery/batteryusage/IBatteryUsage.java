package com.test.battery.batteryusage;

import java.util.ArrayList;

/**
 * Created by prt2121 on 11/7/14.
 */
public interface IBatteryUsage {

    public String getTimeSinceLastBoot();

    public String getLastPowerOnTime();

    /**
     * A list of pairs of an app name and percentage
     */
    public void getBatteryUsage(BatteryUsageListener batteryUsageListener);

    public interface BatteryUsageListener {
        public void onUsageReady(ArrayList<BatUsage> batUsages);
    }

    public class BatteryUsage {
        public String appName = "";
        public String packageName = "";
        public float percentage;
        public String type = "";
    }

    public class BatUsage implements Comparable<BatUsage> {
        //change to default?
        public String packageName;
        public String name;
        public double percent;
        public String type;
        public BatUsage(String packageName, String name, double percent, String type) {
            super();
            this.packageName = packageName;
            this.name = name;
            this.percent = percent;
            this.type = type;
        }

        public String getSnapshot() {
            return "<battery-usage>" +
                    "<name>" + name + "</name>" +
                    "<pkg-name>" + packageName + "</pkg-name>" +
                    "<percent>" + percent + " %</percent>" +
                    "<type>" + type + "</type>" +
                    "</battery-usage>";
        }

        public String getPackageName() {
            return packageName;
        }

        public String getName() {
            return name;
        }

        public double getPercent() {
            return percent;
        }

        public String getType() {
            return type;
        }

        @Override
        public int compareTo(BatUsage other) {
            // Return the flipped value because we want the items in descending
            // order
            return Double.compare(other.percent, percent);
        }
    }
}