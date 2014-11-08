package com.test.battery;

import com.test.battery.batteryusage.BatteryUsageImpl;
import com.test.battery.batteryusage.IBatteryUsage;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.util.ArrayList;


public class MainActivity extends Activity implements IBatteryUsage.BatteryUsageListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        IBatteryUsage batteryUsage = new BatteryUsageImpl(this);
        batteryUsage.getBatteryUsage(this);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onUsageReady(ArrayList<IBatteryUsage.BatUsage> batUsages) {
        IBatteryUsage.BatUsage[] batUsageArray = new IBatteryUsage.BatUsage[batUsages.size()];
        batUsageArray = batUsages.toArray(batUsageArray);
        String desc = "";
        for (IBatteryUsage.BatUsage usage : batUsageArray) {
            desc = desc.concat(
                    String.format("%-10s %2.2f%%", usage.getName(), usage.getPercent()) + "\n");
        }
        desc = desc.trim();
        Log.d(MainActivity.class.getSimpleName(), "!!!onUsageReady " + desc);
    }
}
