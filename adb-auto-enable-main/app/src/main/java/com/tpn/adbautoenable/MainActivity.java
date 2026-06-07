package com.tpn.adbautoenable;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.view.View;
import android.net.wifi.WifiManager;
import android.content.Context;
import android.text.format.Formatter;
import android.util.Log;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;

public class MainActivity extends Activity {
    private static final String TAG = "ADBAutoEnable";
    private static final int WEB_PORT = 9093;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Start the foreground service to keep web server alive
        Intent serviceIntent = new Intent(this, AdbConfigService.class);
        serviceIntent.putExtra("boot_config", false); // Not boot config, just start service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Create UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        TextView titleText = new TextView(this);
        titleText.setText("ADB Auto-Enable");
        titleText.setTextSize(26);
        titleText.setPaintFlags(titleText.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);

        TextView statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setText("\nWeb interface running on:\n");

        TextView urlText = new TextView(this);
        urlText.setTextSize(20);
        urlText.setTextColor(0xFF2196F3);
        urlText.setText("http://" + getLocalIpAddress() + ":" + WEB_PORT);

        TextView instructionText = new TextView(this);
        instructionText.setText("\nOpen this URL in your browser to configure the app.\n\nThe web server runs in the background even when you close this app.");
        instructionText.setTextSize(14);

        layout.addView(titleText);
        layout.addView(statusText);
        layout.addView(urlText);
        layout.addView(instructionText);

        // Battery Optimization status and action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean isIgnoringBattery = pm.isIgnoringBatteryOptimizations(getPackageName());

            if (!isIgnoringBattery) {
                TextView batteryWarning = new TextView(this);
                batteryWarning.setText("\n⚠️ Battery optimization is active. This can stop the background service and autostart on boot.\n");
                batteryWarning.setTextColor(0xFFFF9800); // Orange color
                batteryWarning.setTextSize(14);

                Button ignoreButton = new Button(this);
                ignoreButton.setText("Disable Battery Optimization");
                ignoreButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            Intent intent = new Intent();
                            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to request ignore battery optimizations", e);
                            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(intent);
                        }
                    }
                });

                layout.addView(batteryWarning);
                layout.addView(ignoreButton);
            } else {
                TextView batteryStatus = new TextView(this);
                batteryStatus.setText("\n✅ Battery optimization disabled (Whitelisted).");
                batteryStatus.setTextColor(0xFF4CAF50); // Green color
                batteryStatus.setTextSize(14);
                layout.addView(batteryStatus);
            }
        }

        setContentView(layout);
    }

    private String getLocalIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        return Formatter.formatIpAddress(ipAddress);
    }
}
