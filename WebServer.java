package com.tpn.adbautoenable;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebServer extends NanoHTTPD {
    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";

    private final Context context;
    private final AdbHelper adbHelper;
    private Boolean permissionCached = null;

    private SharedPreferences getDevicePrefs() {
        Context safeContext = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            safeContext = context.createDeviceProtectedStorageContext();
            safeContext.moveSharedPreferencesFrom(context.getApplicationContext(), PREFS_NAME);
        }
        return safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private File getDeviceFilesDir() {
        Context safeContext = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            safeContext = context.createDeviceProtectedStorageContext();
        }
        return safeContext.getFilesDir();
    }

    public WebServer(Context context, int port) {
        super(port);
        this.context = context;
        this.adbHelper = new AdbHelper(context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        if (method == Method.OPTIONS) {
            Response response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
            addCORSHeaders(response);
            return response;
        }

        String uri = session.getUri();
        Response response;

        if (uri.equals("/api/pair") && method == Method.POST) {
            response = handlePairing(session);
        } else if (uri.equals("/api/status")) {
            response = handleStatus();
        } else if (uri.equals("/api/test")) {
            response = handleTest();
        } else if (uri.equals("/api/switch")) {
            response = handleSwitch();
        } else if (uri.equals("/api/logs")) {
            response = handleLogs();
        } else if (uri.equals("/api/reset") && method == Method.POST) {
            response = handleReset();
        } else {
            response = newFixedLengthResponse(getHTML());
        }

        addCORSHeaders(response);
        return response;
    }

    private void addCORSHeaders(Response response) {
        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        }
    }

    private Response handlePairing(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, List<String>> params = session.getParameters();

            List<String> portList = params.get("port");
            List<String> codeList = params.get("code");

            String portStr = (portList != null && !portList.isEmpty()) ? portList.get(0) : null;
            String code = (codeList != null && !codeList.isEmpty()) ? codeList.get(0) : null;

            Log.i(TAG, "Web API: Received pairing request - port: " + portStr + ", code: " + code);

            if (portStr == null || code == null || portStr.isEmpty() || code.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"error\":\"Port and code required\"}");
            }

            int port = Integer.parseInt(portStr);
            Log.i(TAG, "Web API: Pairing on port " + port + " with code " + code);

            boolean success = adbHelper.pair("127.0.0.1", port, code);

            if (success) {
                SharedPreferences prefs = getDevicePrefs();
                prefs.edit().putBoolean("is_paired", true).apply();
                Log.i(TAG, "Web API: Pairing successful");

                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        Log.i(TAG, "Attempting to self-grant WRITE_SECURE_SETTINGS permission");

                        int adbPort = discoverAdbPort();
                        if (adbPort == -1) {
                            Log.w(TAG, "Could not discover ADB port for self-grant, skipping");
                            return;
                        }

                        Log.i(TAG, "Found ADB on port " + adbPort + ", attempting self-grant");
                        boolean granted = adbHelper.selfGrantPermission("127.0.0.1", adbPort,
                                "com.tpn.adbautoenable", "android.permission.WRITE_SECURE_SETTINGS");

                        if (granted) {
                            Log.i(TAG, "Successfully self-granted WRITE_SECURE_SETTINGS permission!");
                        } else {
                            Log.w(TAG, "Failed to self-grant permission, user will need to grant manually");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during self-grant attempt", e);
                    }
                }).start();

                return newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"success\":true,\"message\":\"Pairing successful! Attempting to self-grant permissions...\"}");
            } else {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"Pairing failed. Make sure wireless debugging is enabled and code is correct.\"}");
            }

        } catch (NumberFormatException e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    "{\"error\":\"Invalid port number\"}");
        } catch (Exception e) {
            Log.e(TAG, "Web API: Pairing error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleStatus() {
        Log.d(TAG, "handleStatus() called - creating socket for port check");
        SharedPreferences prefs = getDevicePrefs();
        String lastStatus = prefs.getString("last_status", "Not run yet");
        int lastPort = prefs.getInt("last_port", -1);
        boolean isPaired = prefs.getBoolean("is_paired", false);

        Log.d(TAG, "handleStatus() - calling checkPort5555()");
        boolean adb5555Available = checkPort5555();

        // Cache permission check - only do it once
        if (permissionCached == null) {
            Log.d(TAG, "handleStatus() - checking WRITE_SECURE_SETTINGS permission (cached)");
            try {
                Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
                permissionCached = true;
                Log.d(TAG, "handleStatus() - permission check SUCCESS");
            } catch (SecurityException e) {
                permissionCached = false;
                Log.d(TAG, "handleStatus() - permission check FAILED");
            }
        }

        android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean isIgnoringBattery = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.getPackageName());
        }

        String json = String.format(Locale.US,
                "{\"lastStatus\":\"%s\",\"lastPort\":%d,\"isPaired\":%b,\"hasPermission\":%b,\"adb5555Available\":%b,\"isIgnoringBattery\":%b}",
                lastStatus, lastPort, isPaired, permissionCached, adb5555Available, isIgnoringBattery
        );
        Log.d(TAG, "handleStatus() completed");
        return newFixedLengthResponse(Response.Status.OK, "application/json", json);
    }


    private boolean checkPermission() {
        try {
            // Just READ, don't write!
            Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }


    // Cache the permission check result
    private Boolean cachedPermissionCheck = null;

    private boolean checkWriteSettingsPermission() {
        if (cachedPermissionCheck != null) {
            return cachedPermissionCheck;
        }

        try {
            // Just CHECK, don't actually write
            int current = Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0);
            cachedPermissionCheck = true;  // If we can read, we likely have permission
            return true;
        } catch (Exception e) {
            cachedPermissionCheck = false;
            return false;
        }
    }


    private Response handleLogs() {
        Log.d(TAG, "handleLogs() called - spawning logcat process");
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-s", "ADBAutoEnable:*"});

            StringBuilder logs = new StringBuilder();
            // Use try-with-resources for BOTH InputStreamReader and BufferedReader
            try (InputStreamReader isr = new InputStreamReader(process.getInputStream());
                 BufferedReader reader = new BufferedReader(isr)) {

                String line;
                while ((line = reader.readLine()) != null) {
                    logs.append(line).append("\n");
                }
            } // Readers automatically closed here

            // Wait for process to complete
            process.waitFor();

            String logsText = logs.toString();
            if (logsText.isEmpty()) {
                logsText = "No logs found for ADBAutoEnable";
            }

            logsText = logsText.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            Log.d(TAG, "handleLogs() completed - returning " + logsText.length() + " characters");
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"logs\":\"" + logsText + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Failed to read logs", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"Failed to read logs: " + e.getMessage() + "\"}");
        } finally {
            if (process != null) {
                process.destroy();
                Log.d(TAG, "handleLogs() - process destroyed");
            }
        }
    }



    private Response handleReset() {
        try {
            Log.i(TAG, "Web API: Resetting pairing status");

            SharedPreferences prefs = getDevicePrefs();
            prefs.edit()
                    .putBoolean("is_paired", false)
                    .apply();

            File keyDir = new File(getDeviceFilesDir(), "adb_key");
            File pubKeyFile = new File(getDeviceFilesDir(), "adb_key.pub");
            File certFile = new File(getDeviceFilesDir(), "adb_cert");

            boolean deleted1 = keyDir.delete();
            boolean deleted2 = pubKeyFile.delete();
            boolean deleted3 = certFile.delete();

            Log.i(TAG, "Deleted adb_key: " + deleted1);
            Log.i(TAG, "Deleted adb_key.pub: " + deleted2);
            Log.i(TAG, "Deleted adb_cert: " + deleted3);

            Log.i(TAG, "Pairing reset successful");
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"success\":true,\"message\":\"Pairing reset successful. Please pair again.\"}");

        } catch (Exception e) {
            Log.e(TAG, "Web API: Reset error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private boolean checkPort5555() {
        try {
            Socket socket = new Socket("127.0.0.1", 5555);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Response handleTest() {
        new Thread(() -> {
            BootReceiver receiver = new BootReceiver();
            receiver.onReceive(context, new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));
        }).start();

        return newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"success\":true,\"message\":\"Boot test started. Check logs below for progress.\"}");
    }

    private Response handleSwitch() {
        new Thread(() -> {
            try {
                Log.i(TAG, "Web API: Discovering ADB port...");
                int port = discoverAdbPort();

                if (port == -1) {
                    Log.e(TAG, "Web API: Could not find ADB port");
                    return;
                }

                Log.i(TAG, "Web API: Found ADB on port " + port + ", switching to 5555...");
                boolean success = adbHelper.switchToPort5555("127.0.0.1", port);  // Use localhost for port 5555

                if (success) {
                    Log.i(TAG, "Web API: Successfully switched to port 5555");
                } else {
                    Log.e(TAG, "Web API: Failed to switch to port 5555");
                }

            } catch (Exception e) {
                Log.e(TAG, "Web API: Switch error", e);
            }

        }).start();

        return newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"success\":true,\"message\":\"Port switch started. Check logs below for status.\"}");
    }


    private int discoverAdbPort() {
        final int[] discoveredPort = {-1};
        final CountDownLatch latch = new CountDownLatch(1);
        String deviceIP = getDeviceIP();

        Log.i(TAG, "Looking for mDNS service on device IP: " + deviceIP);

        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available");
            return -1;
        }

        final NsdManager.DiscoveryListener[] discoveryListenerHolder = new NsdManager.DiscoveryListener[1];

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "mDNS discovery started for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service found: " + serviceInfo.getServiceName());
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        Log.e(TAG, "Resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        if (serviceInfo.getHost() != null) {
                            InetAddress hostAddress = serviceInfo.getHost();
                            String host = hostAddress.getHostAddress();
                            if (host == null) {
                                Log.w(TAG, "Host address is null");
                                return;
                            }

                            int port = serviceInfo.getPort();
                            Log.i(TAG, "Host: " + host + ", Port: " + port);
                            if (host.startsWith("127.") || host.equals("::1") ||
                                    host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                                if (host.equals(deviceIP)) {
                                    Log.i(TAG, "Found matching device with IP: " + deviceIP + ", Port: " + port);
                                    discoveredPort[0] = port;
                                    // DON'T countdown - let timeout handle it to get the latest port
                                } else {
                                    Log.w(TAG, "Skipping device with IP " + host + " (looking for " + deviceIP + ")");
                                }
                            }
                        }
                    }

                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service lost: " + serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: error " + errorCode);
                latch.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: error " + errorCode);
            }
        };

        discoveryListenerHolder[0] = discoveryListener;

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            boolean found = latch.await(10, TimeUnit.SECONDS);
            if (!found) {
                Log.e(TAG, "mDNS discovery timed out after 10 seconds");
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener);
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping discovery after timeout", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "mDNS discovery error", e);
        }

        return discoveredPort[0];
    }

    private String getDeviceIP() {
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return "127.0.0.1";
            }

            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            byte[] ipBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ipAddress)
                    .array();

            InetAddress inetAddress = InetAddress.getByAddress(ipBytes);
            String result = inetAddress.getHostAddress();
            return (result != null) ? result : "127.0.0.1";
        } catch (Exception e) {
            Log.e(TAG, "Failed to get device IP", e);
            return "127.0.0.1";
        }
    }

    private String getHTML() {
        String deviceIP = getDeviceIP();
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>ADB Auto-Enable Configuration</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; max-width: 800px; margin: 20px auto; padding: 20px; background: #f5f5f5; }\n" +
                "        .card { background: white; border-radius: 8px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
                "        h1 { color: #333; margin-top: 0; }\n" +
                "        h2 { color: #666; font-size: 18px; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }\n" +
                "        button { background: #4CAF50; color: white; border: none; padding: 12px 24px; font-size: 16px; border-radius: 4px; cursor: pointer; margin: 5px; }\n" +
                "        button:hover { background: #45a049; }\n" +
                "        button.secondary { background: #2196F3; }\n" +
                "        button.secondary:hover { background: #0b7dda; }\n" +
                "        button.danger { background: #f44336; }\n" +
                "        button.danger:hover { background: #da190b; }\n" +
                "        input { padding: 10px; font-size: 14px; border: 1px solid #ddd; border-radius: 4px; width: 200px; margin: 5px; }\n" +
                "        .status { padding: 8px; border-radius: 4px; margin: 5px 0; }\n" +
                "        .status.good { background: #d4edda; color: #155724; }\n" +
                "        .status.bad { background: #f8d7da; color: #721c24; }\n" +
                "        code { background: #f4f4f4; padding: 2px 6px; border-radius: 3px; font-family: monospace; display: block; margin: 10px 0; white-space: pre-wrap; word-break: break-all; }\n" +
                "        .instruction { background: #e3f2fd; padding: 15px; border-radius: 4px; margin: 10px 0; }\n" +
                "        .success { background: #d4edda; color: #155724; padding: 10px; border-radius: 4px; margin: 10px 0; display: none; }\n" +
                "        .error { background: #f8d7da; color: #721c24; padding: 10px; border-radius: 4px; margin: 10px 0; display: none; }\n" +
                "        .info { background: #d1ecf1; color: #0c5460; padding: 10px; border-radius: 4px; margin: 10px 0; display: none; }\n" +
                "        .status-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid #eee; }\n" +
                "        .status-row:last-child { border-bottom: none; }\n" +
                "        .status-label { font-weight: bold; color: #666; min-width: 150px; }\n" +
                "        .status-value { flex: 1; text-align: right; }\n" +
                "        #logs-container { background: #1e1e1e; color: #d4d4d4; font-family: 'Courier New', monospace; font-size: 12px; padding: 15px; border-radius: 4px; max-height: 400px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; user-select: text; }\n" +
                "        .logs-controls { margin-bottom: 10px; }\n" +
                "        .paused { background: #ff9800; color: white; padding: 5px 10px; border-radius: 3px; font-size: 12px; margin-left: 10px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div id=\"active-device-banner\" style=\"background: #2196F3; color: white; padding: 12px 20px; border-radius: 8px; margin-bottom: 20px; font-weight: bold; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 2px 4px rgba(0,0,0,0.1);\">\n" +
                "        <span>Currently Controlling: <span id=\"active-device-display\">This Device (" + deviceIP + ")</span></span>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\">\n" +
                "        <h2>📱 Device Manager</h2>\n" +
                "        <div style=\"display: flex; gap: 10px; margin-bottom: 15px; flex-wrap: wrap; align-items: center;\">\n" +
                "            <input type=\"text\" id=\"new-device-ip\" placeholder=\"e.g. 192.168.16.100\" style=\"flex: 1; min-width: 150px; margin: 0;\" />\n" +
                "            <button onclick=\"addDevice()\" style=\"margin: 0;\">➕ Add Device</button>\n" +
                "        </div>\n" +
                "        <div style=\"overflow-x: auto;\">\n" +
                "            <table style=\"width: 100%; border-collapse: collapse; margin-top: 10px; text-align: left;\">\n" +
                "                <thead>\n" +
                "                    <tr style=\"border-bottom: 2px solid #ddd;\">\n" +
                "                        <th style=\"padding: 8px;\">Device IP</th>\n" +
                "                        <th style=\"padding: 8px;\">Status</th>\n" +
                "                        <th style=\"padding: 8px;\">5555 Port</th>\n" +
                "                        <th style=\"padding: 8px; text-align: right;\">Actions</th>\n" +
                "                    </tr>\n" +
                "                </thead>\n" +
                "                <tbody id=\"device-list-body\">\n" +
                "                </tbody>\n" +
                "            </table>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <h1>🔧 ADB Auto-Enable Configuration</h1>\n" +
                "    \n" +
                "    <div class=\"card\">\n" +
                "        <h2>📊 System Status</h2>\n" +
                "        <div id=\"status-display\">\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Permission:</div>\n" +
                "                <div class=\"status-value\" id=\"permission-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Pairing Status:</div>\n" +
                "                <div class=\"status-value\" id=\"pairing-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">ADB Port 5555:</div>\n" +
                "                <div class=\"status-value\" id=\"port-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Battery Optimization:</div>\n" +
                "                <div class=\"status-value\" id=\"battery-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Device IP:</div>\n" +
                "                <div class=\"status-value\">" + deviceIP + "</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Last Boot Status:</div>\n" +
                "                <div class=\"status-value\" id=\"last-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Last Port:</div>\n" +
                "                <div class=\"status-value\" id=\"last-port\">Loading...</div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        <button onclick=\"refreshStatus()\">🔄 Refresh Status</button>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"battery-optimization-card\" style=\"display:none\">\n" +
                "        <h2>⚡ Autostart & Battery Optimization (Important)</h2>\n" +
                "        <div class=\"instruction\" style=\"background: #fff3cd; color: #856404;\">\n" +
                "            <strong>Warning:</strong> Battery Optimization is currently <strong>Enabled</strong> for this app.<br>\n" +
                "            Android or custom phone skins (MIUI/Xiaomi, Realme, OPPO, Vivo, OnePlus) will kill the background web server and prevent the app from starting automatically on reboot.<br><br>\n" +
                "            To fix this, please complete these steps on your phone:\n" +
                "            <ol>\n" +
                "                <li>Go to <strong>Settings → Apps → ADB Auto-Enable</strong></li>\n" +
                "                <li>Tap <strong>Battery</strong> and change it to <strong>Unrestricted / Don't optimize</strong></li>\n" +
                "                <li>Enable <strong>Auto-start / Autostart</strong> permission in your settings (if available on your device)</li>\n" +
                "            </ol>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"pairing-card\">\n" +
                "        <h2>🔐 Initial Pairing (One-Time Setup)</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            <strong>Step 1:</strong> On your Android device, go to:<br>\n" +
                "            <strong>Settings → Developer Options → Wireless Debugging</strong><br>\n" +
                "            Tap <strong>\"Pair device with pairing code\"</strong><br><br>\n" +
                "            <strong>Step 2:</strong> Copy the pairing code and port shown and enter them below:<br>\n" +
                "        </div>\n" +
                "        <div>\n" +
                "            <input type=\"text\" id=\"pair-code\" placeholder=\"Pairing Code\" />\n" +
                "            <input type=\"number\" id=\"pair-port\" placeholder=\"Pairing Port\" />\n" +
                "            <button onclick=\"pairDevice()\">🔗 Pair Device</button>\n" +
                "        </div>\n" +
                "        <div id=\"pair-success\" class=\"success\"></div>\n" +
                "        <div id=\"pair-error\" class=\"error\"></div>\n" +
                "        <p><em>After pairing, the app will attempt to automatically grant itself permissions. Check the status above to verify.</em></p>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"paired-card\" style=\"display:none\">\n" +
                "        <h2>✅ Device Paired</h2>\n" +
                "        <p>Your device is successfully paired and ready to use!</p>\n" +
                "        <button onclick=\"resetPairing()\" class=\"danger\">🔄 Reset Pairing</button>\n" +
                "        <div id=\"reset-success\" class=\"success\"></div>\n" +
                "        <div id=\"reset-error\" class=\"error\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"switch-card\">\n" +
                "        <h2>🔄 Switch to Port 5555</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            After pairing and enabling wireless debugging, switch ADB to port 5555:\n" +
                "        </div>\n" +
                "        <button onclick=\"switchPort()\">🔀 Switch to Port 5555 Now</button>\n" +
                "        <div id=\"switch-info\" class=\"info\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\">\n" +
                "        <h2>🧪 Testing</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            Test the full boot configuration sequence:\n" +
                "        </div>\n" +
                "        <button onclick=\"runTest()\">▶️ Run Test Now</button>\n" +
                "        <div id=\"test-info\" class=\"info\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\">\n" +
                "        <h2>📋 Live Logs</h2>\n" +
                "        <div class=\"logs-controls\">\n" +
                "            <button onclick=\"copyLogs()\" class=\"secondary\">📋 Copy to Clipboard</button>\n" +
                "            <span id=\"paused-indicator\" class=\"paused\" style=\"display:none\">Auto-refresh paused</span>\n" +
                "        </div>\n" +
                "        <div id=\"logs-container\">Loading logs...</div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <script>\n" +
                "        let autoRefreshPaused = false;\n" +
                "        let logsRefreshInterval;\n" +
                "        let activeDeviceIp = \"\";\n" +
                "        let devices = [];\n" +
                "        let deviceStatuses = {};\n" +
                "        const localDeviceIp = \"" + deviceIP + "\";\n" +
                "        \n" +
                "        function getApiUrl(path) {\n" +
                "            if (!activeDeviceIp || activeDeviceIp === localDeviceIp) {\n" +
                "                return path;\n" +
                "            }\n" +
                "            return 'http://' + activeDeviceIp + ':9093' + path;\n" +
                "        }\n" +
                "        \n" +
                "        function loadDevices() {\n" +
                "            const stored = localStorage.getItem('adb_devices');\n" +
                "            if (stored) {\n" +
                "                try {\n" +
                "                    devices = JSON.parse(stored);\n" +
                "                } catch(e) {\n" +
                "                    devices = [];\n" +
                "                }\n" +
                "            }\n" +
                "            if (devices.indexOf(localDeviceIp) === -1) {\n" +
                "                devices.unshift(localDeviceIp);\n" +
                "                saveDevices();\n" +
                "            }\n" +
                "            if (!activeDeviceIp) {\n" +
                "                activeDeviceIp = localDeviceIp;\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function saveDevices() {\n" +
                "            localStorage.setItem('adb_devices', JSON.stringify(devices));\n" +
                "        }\n" +
                "        \n" +
                "        function addDevice() {\n" +
                "            const ipInput = document.getElementById('new-device-ip');\n" +
                "            const ip = ipInput.value.trim();\n" +
                "            if (!ip) return;\n" +
                "            const ipPattern = /^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$/;\n" +
                "            if (!ipPattern.test(ip)) {\n" +
                "                alert('Please enter a valid IP address');\n" +
                "                return;\n" +
                "            }\n" +
                "            if (devices.indexOf(ip) === -1) {\n" +
                "                devices.push(ip);\n" +
                "                saveDevices();\n" +
                "                ipInput.value = '';\n" +
                "                renderDeviceList();\n" +
                "                pollAllDevices();\n" +
                "            } else {\n" +
                "                alert('Device already added');\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function removeDevice(ip) {\n" +
                "            if (ip === localDeviceIp) {\n" +
                "                alert('Cannot remove the local device hosting this page.');\n" +
                "                return;\n" +
                "            }\n" +
                "            const index = devices.indexOf(ip);\n" +
                "            if (index !== -1) {\n" +
                "                devices.splice(index, 1);\n" +
                "                saveDevices();\n" +
                "                if (activeDeviceIp === ip) {\n" +
                "                    selectDevice(localDeviceIp);\n" +
                "                } else {\n" +
                "                    renderDeviceList();\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function selectDevice(ip) {\n" +
                "            activeDeviceIp = ip;\n" +
                "            document.getElementById('active-device-display').textContent = (ip === localDeviceIp) ? 'This Device (' + ip + ')' : ip;\n" +
                "            document.getElementById('logs-container').textContent = 'Loading logs for ' + ip + '...';\n" +
                "            renderDeviceList();\n" +
                "            refreshStatus();\n" +
                "            refreshLogs();\n" +
                "        }\n" +
                "        \n" +
                "        function renderDeviceList() {\n" +
                "            const tbody = document.getElementById('device-list-body');\n" +
                "            tbody.innerHTML = '';\n" +
                "            \n" +
                "            devices.forEach(ip => {\n" +
                "                const isLocal = (ip === localDeviceIp);\n" +
                "                const isActive = (ip === activeDeviceIp);\n" +
                "                const status = deviceStatuses[ip] || { loading: true };\n" +
                "                \n" +
                "                let statusText = '<span style=\"color: #999;\">Loading...</span>';\n" +
                "                let portText = '<span style=\"color: #999;\">-</span>';\n" +
                "                \n" +
                "                if (!status.loading) {\n" +
                "                    if (status.error) {\n" +
                "                        statusText = '<span class=\"status bad\" style=\"padding: 2px 6px; font-size:12px;\">Offline</span>';\n" +
                "                    } else {\n" +
                "                        const pairedStr = status.isPaired ? '✓ Paired' : '✗ Unpaired';\n" +
                "                        const permStr = status.hasPermission ? '✓ Perm' : '✗ Perm';\n" +
                "                        statusText = `<span class=\"status ${status.isPaired ? 'good' : 'bad'}\" style=\"padding: 2px 6px; font-size:12px;\">${pairedStr}</span> ` +\n" +
                "                                    `<span class=\"status ${status.hasPermission ? 'good' : 'bad'}\" style=\"padding: 2px 6px; font-size:12px;\">${permStr}</span>`;\n" +
                "                        \n" +
                "                        portText = status.adb5555Available ? \n" +
                "                            '<span class=\"status good\" style=\"padding: 2px 6px; font-size:12px;\">✓ 5555</span>' : \n" +
                "                            '<span class=\"status bad\" style=\"padding: 2px 6px; font-size:12px;\">✗ Closed</span>';\n" +
                "                    }\n" +
                "                }\n" +
                "                \n" +
                "                const tr = document.createElement('tr');\n" +
                "                tr.style.borderBottom = '1px solid #eee';\n" +
                "                if (isActive) {\n" +
                "                    tr.style.backgroundColor = '#e3f2fd';\n" +
                "                }\n" +
                "                \n" +
                "                tr.innerHTML = `\n" +
                "                    <td style=\"padding: 8px; font-weight: ${isActive ? 'bold' : 'normal'};\">\n" +
                "                        ${ip} ${isLocal ? ' <span style=\"font-size: 11px; color: #666;\">(Local)</span>' : ''}\n" +
                "                    </td>\n" +
                "                    <td style=\"padding: 8px;\">${statusText}</td>\n" +
                "                    <td style=\"padding: 8px;\">${portText}</td>\n" +
                "                    <td style=\"padding: 8px; text-align: right;\">\n" +
                "                        <button onclick=\"selectDevice('${ip}')\" class=\"secondary\" style=\"padding: 4px 8px; font-size: 12px; margin: 0 2px; ${isActive ? 'background: #4CAF50;' : ''}\">\n" +
                "                            ${isActive ? 'Active' : 'Control'}\n" +
                "                        </button>\n" +
                "                        ${!isLocal ? `<button onclick=\"removeDevice('${ip}')\" class=\"danger\" style=\"padding: 4px 8px; font-size: 12px; margin: 0 2px;\">Remove</button>` : ''}\n" +
                "                    </td>\n" +
                "                `;\n" +
                "                tbody.appendChild(tr);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function pollAllDevices() {\n" +
                "            devices.forEach(ip => {\n" +
                "                const url = (ip === localDeviceIp) ? '/api/status' : 'http://' + ip + ':9093/api/status';\n" +
                "                const controller = new AbortController();\n" +
                "                const timeoutId = setTimeout(() => controller.abort(), 3000);\n" +
                "                \n" +
                "                fetch(url, { signal: controller.signal })\n" +
                "                    .then(r => r.json())\n" +
                "                    .then(data => {\n" +
                "                        clearTimeout(timeoutId);\n" +
                "                        deviceStatuses[ip] = {\n" +
                "                            loading: false,\n" +
                "                            error: false,\n" +
                "                            isPaired: data.isPaired,\n" +
                "                            hasPermission: data.hasPermission,\n" +
                "                            adb5555Available: data.adb5555Available\n" +
                "                        };\n" +
                "                        renderDeviceList();\n" +
                "                        if (ip === activeDeviceIp) {\n" +
                "                            updateMainStatusCard(data);\n" +
                "                        }\n" +
                "                    })\n" +
                "                    .catch(err => {\n" +
                "                        clearTimeout(timeoutId);\n" +
                "                        deviceStatuses[ip] = {\n" +
                "                            loading: false,\n" +
                "                            error: true\n" +
                "                        };\n" +
                "                        renderDeviceList();\n" +
                "                        if (ip === activeDeviceIp) {\n" +
                "                            document.getElementById('permission-status').innerHTML = '<span class=\"status bad\">✗ Offline</span>';\n" +
                "                            document.getElementById('pairing-status').innerHTML = '<span class=\"status bad\">✗ Offline</span>';\n" +
                "                            document.getElementById('port-status').innerHTML = '<span class=\"status bad\">✗ Offline</span>';\n" +
                "                            document.getElementById('battery-status').innerHTML = '<span class=\"status bad\">✗ Offline</span>';\n" +
                "                            document.getElementById('last-status').textContent = 'Device offline / unreachable';\n" +
                "                            document.getElementById('last-port').textContent = '-';\n" +
                "                        }\n" +
                "                    });\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function updateMainStatusCard(data) {\n" +
                "            document.getElementById('permission-status').innerHTML = data.hasPermission ? \n" +
                "                '<span class=\"status good\">✓ Granted</span>' : \n" +
                "                '<span class=\"status bad\">✗ Not granted</span>';\n" +
                "            document.getElementById('pairing-status').innerHTML = data.isPaired ? \n" +
                "                '<span class=\"status good\">✓ Paired</span>' : \n" +
                "                '<span class=\"status bad\">✗ Not paired</span>';\n" +
                "            document.getElementById('port-status').innerHTML = data.adb5555Available ? \n" +
                "                '<span class=\"status good\">✓ Available</span>' : \n" +
                "                '<span class=\"status bad\">✗ Not available</span>';\n" +
                "            \n" +
                "            if (data.isIgnoringBattery !== undefined) {\n" +
                "                document.getElementById('battery-status').innerHTML = data.isIgnoringBattery ? \n" +
                "                    '<span class=\"status good\">✓ Ignored (OK)</span>' : \n" +
                "                    '<span class=\"status bad\">✗ Optimized (Will fail on boot)</span>';\n" +
                "                document.getElementById('battery-optimization-card').style.display = data.isIgnoringBattery ? 'none' : 'block';\n" +
                "            } else {\n" +
                "                document.getElementById('battery-status').innerHTML = 'Unknown';\n" +
                "                document.getElementById('battery-optimization-card').style.display = 'none';\n" +
                "            }\n" +
                "            \n" +
                "            document.getElementById('last-status').textContent = data.lastStatus;\n" +
                "            document.getElementById('last-port').textContent = data.lastPort;\n" +
                "            \n" +
                "            if (data.isPaired) {\n" +
                "                document.getElementById('pairing-card').style.display = 'none';\n" +
                "                document.getElementById('paired-card').style.display = 'block';\n" +
                "            } else {\n" +
                "                document.getElementById('pairing-card').style.display = 'block';\n" +
                "                document.getElementById('paired-card').style.display = 'none';\n" +
                "            }\n" +
                "            \n" +
                "            if (data.adb5555Available) {\n" +
                "                document.getElementById('switch-card').style.display = 'none';\n" +
                "            } else {\n" +
                "                document.getElementById('switch-card').style.display = 'block';\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function refreshStatus() {\n" +
                "            pollAllDevices();\n" +
                "        }\n" +
                "        \n" +
                "        function refreshLogs() {\n" +
                "            fetch(getApiUrl('/api/logs'))\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    const container = document.getElementById('logs-container');\n" +
                "                    const wasScrolledToBottom = container.scrollHeight - container.clientHeight <= container.scrollTop + 1;\n" +
                "                    container.textContent = data.logs || 'No logs available';\n" +
                "                    if (wasScrolledToBottom) {\n" +
                "                        container.scrollTop = container.scrollHeight;\n" +
                "                    }\n" +
                "                })\n" +
                "                .catch(e => {\n" +
                "                    document.getElementById('logs-container').textContent = 'Error loading logs: ' + e.message;\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        function copyLogs() {\n" +
                "            const logs = document.getElementById('logs-container').textContent;\n" +
                "            if (navigator.clipboard && navigator.clipboard.writeText) {\n" +
                "                navigator.clipboard.writeText(logs).then(() => {\n" +
                "                    const btn = event.target;\n" +
                "                    const originalText = btn.textContent;\n" +
                "                    btn.textContent = '✓ Copied!';\n" +
                "                    setTimeout(() => { btn.textContent = originalText; }, 2000);\n" +
                "                }).catch(e => {\n" +
                "                    console.log('Clipboard API failed, using fallback method');\n" +
                "                    copyLogsViaTextarea(logs, event.target);\n" +
                "                });\n" +
                "            } else {\n" +
                "                copyLogsViaTextarea(logs, event.target);\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function copyLogsViaTextarea(text, btn) {\n" +
                "            const textarea = document.createElement('textarea');\n" +
                "            textarea.value = text;\n" +
                "            document.body.appendChild(textarea);\n" +
                "            textarea.select();\n" +
                "            document.execCommand('copy');\n" +
                "            document.body.removeChild(textarea);\n" +
                "            const originalText = btn.textContent;\n" +
                "            btn.textContent = '✓ Copied!';\n" +
                "            setTimeout(() => { btn.textContent = originalText; }, 2000);\n" +
                "        }\n" +
                "        \n" +
                "        function resetPairing() {\n" +
                "            const successDiv = document.getElementById('reset-success');\n" +
                "            const errorDiv = document.getElementById('reset-error');\n" +
                "            \n" +
                "            if (confirm('Are you sure you want to reset pairing for ' + activeDeviceIp + '?')) {\n" +
                "                fetch(getApiUrl('/api/reset'), {\n" +
                "                    method: 'POST'\n" +
                "                })\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    if (data.success) {\n" +
                "                        successDiv.textContent = data.message;\n" +
                "                        successDiv.style.display = 'block';\n" +
                "                        errorDiv.style.display = 'none';\n" +
                "                        setTimeout(() => {\n" +
                "                            successDiv.style.display = 'none';\n" +
                "                            refreshStatus();\n" +
                "                        }, 3000);\n" +
                "                    } else {\n" +
                "                        errorDiv.textContent = 'Reset failed: ' + (data.error || 'Unknown error');\n" +
                "                        errorDiv.style.display = 'block';\n" +
                "                        successDiv.style.display = 'none';\n" +
                "                    }\n" +
                "                })\n" +
                "                .catch(e => {\n" +
                "                    errorDiv.textContent = 'Error: ' + e.message;\n" +
                "                    errorDiv.style.display = 'block';\n" +
                "                    successDiv.style.display = 'none';\n" +
                "                });\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function pairDevice() {\n" +
                "            const port = document.getElementById('pair-port').value;\n" +
                "            const code = document.getElementById('pair-code').value;\n" +
                "            const successDiv = document.getElementById('pair-success');\n" +
                "            const errorDiv = document.getElementById('pair-error');\n" +
                "            \n" +
                "            successDiv.style.display = 'none';\n" +
                "            errorDiv.style.display = 'none';\n" +
                "            \n" +
                "            fetch(getApiUrl('/api/pair'), {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'port=' + port + '&code=' + code\n" +
                "            })\n" +
                "            .then(r => r.json())\n" +
                "            .then(data => {\n" +
                "                if (data.success) {\n" +
                "                    successDiv.textContent = data.message;\n" +
                "                    successDiv.style.display = 'block';\n" +
                "                    setTimeout(refreshStatus, 2000);\n" +
                "                } else {\n" +
                "                    errorDiv.textContent = data.error || 'Pairing failed';\n" +
                "                    errorDiv.style.display = 'block';\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(e => {\n" +
                "                errorDiv.textContent = 'Error: ' + e.message;\n" +
                "                errorDiv.style.display = 'block';\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function switchPort() {\n" +
                "            const infoDiv = document.getElementById('switch-info');\n" +
                "            \n" +
                "            fetch(getApiUrl('/api/switch'))\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    infoDiv.textContent = data.message;\n" +
                "                    infoDiv.style.display = 'block';\n" +
                "                    setTimeout(() => {\n" +
                "                        infoDiv.style.display = 'none';\n" +
                "                        refreshStatus();\n" +
                "                        refreshLogs();\n" +
                "                    }, 5000);\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        function runTest() {\n" +
                "            const infoDiv = document.getElementById('test-info');\n" +
                "            \n" +
                "            fetch(getApiUrl('/api/test'))\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    infoDiv.textContent = data.message;\n" +
                "                    infoDiv.style.display = 'block';\n" +
                "                    setTimeout(() => {\n" +
                "                        infoDiv.style.display = 'none';\n" +
                "                        refreshLogs();\n" +
                "                    }, 3000);\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        document.addEventListener('DOMContentLoaded', function() {\n" +
                "            loadDevices();\n" +
                "            renderDeviceList();\n" +
                "            refreshStatus();\n" +
                "            refreshLogs();\n" +
                "            \n" +
                "            // Auto-refresh loops\n" +
                "            setInterval(refreshStatus, 5000);\n" +
                "            \n" +
                "            const logsContainer = document.getElementById('logs-container');\n" +
                "            const pausedIndicator = document.getElementById('paused-indicator');\n" +
                "            \n" +
                "            logsContainer.addEventListener('mousedown', function() {\n" +
                "                autoRefreshPaused = true;\n" +
                "                pausedIndicator.style.display = 'inline';\n" +
                "                clearInterval(logsRefreshInterval);\n" +
                "            });\n" +
                "            \n" +
                "            document.addEventListener('mouseup', function() {\n" +
                "                setTimeout(() => {\n" +
                "                    if (window.getSelection().toString().length === 0) {\n" +
                "                        autoRefreshPaused = false;\n" +
                "                        pausedIndicator.style.display = 'none';\n" +
                "                        startLogsAutoRefresh();\n" +
                "                    }\n" +
                "                }, 100);\n" +
                "            });\n" +
                "            \n" +
                "            startLogsAutoRefresh();\n" +
                "        });\n" +
                "        \n" +
                "        function startLogsAutoRefresh() {\n" +
                "            if (!autoRefreshPaused) {\n" +
                "                logsRefreshInterval = setInterval(() => {\n" +
                "                    if (!autoRefreshPaused) {\n" +
                "                        refreshLogs();\n" +
                "                    }\n" +
                "                }, 3000);\n" +
                "            }\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}