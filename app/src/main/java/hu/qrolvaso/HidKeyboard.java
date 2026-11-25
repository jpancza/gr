package hu.qrolvaso;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A telefont szabványos Bluetooth HID billentyűzetként jeleníti meg – pont úgy, ahogy egy
 * kézi vonalkód-/QR-olvasó működik "billentyűzet módban". A notebookra nem kell semmit telepíteni.
 * A leíró megegyezik a Prezenter appéval, így a két app ugyanazt a párosítást használhatja.
 */
@SuppressLint("MissingPermission")
public class HidKeyboard {

    public interface Listener {
        void onStatus(String text, boolean connected);
    }

    private static final byte REPORT_ID = 1;
    private static final int MOD_LEFT_ALT = 0x04;
    private static final int KEY_NUMLOCK = 0x53;
    private static final int LED_NUMLOCK = 0x01;
    private static final int LED_CAPSLOCK = 0x02;
    private static final long KEY_DELAY_MS = 10;

    private static final byte[] DESCRIPTOR = {
            (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x06, (byte) 0xA1, (byte) 0x01,
            (byte) 0x85, REPORT_ID,
            (byte) 0x05, (byte) 0x07, (byte) 0x19, (byte) 0xE0, (byte) 0x29, (byte) 0xE7,
            (byte) 0x15, (byte) 0x00, (byte) 0x25, (byte) 0x01, (byte) 0x75, (byte) 0x01,
            (byte) 0x95, (byte) 0x08, (byte) 0x81, (byte) 0x02,
            (byte) 0x95, (byte) 0x01, (byte) 0x75, (byte) 0x08, (byte) 0x81, (byte) 0x01,
            (byte) 0x95, (byte) 0x05, (byte) 0x75, (byte) 0x01, (byte) 0x05, (byte) 0x08,
            (byte) 0x19, (byte) 0x01, (byte) 0x29, (byte) 0x05, (byte) 0x91, (byte) 0x02,
            (byte) 0x95, (byte) 0x01, (byte) 0x75, (byte) 0x03, (byte) 0x91, (byte) 0x01,
            (byte) 0x95, (byte) 0x06, (byte) 0x75, (byte) 0x08, (byte) 0x15, (byte) 0x00,
            (byte) 0x25, (byte) 0x65, (byte) 0x05, (byte) 0x07, (byte) 0x19, (byte) 0x00,
            (byte) 0x29, (byte) 0x65, (byte) 0x81, (byte) 0x00,
            (byte) 0xC0
    };

    private final Context ctx;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private volatile boolean registered;
    /** A notebook LED-állapota (Num/Caps Lock); -1 = még nem tudjuk. */
    private volatile int leds = -1;

    public HidKeyboard(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
        this.prefs = ctx.getSharedPreferences("hid", Context.MODE_PRIVATE);
    }

    public BluetoothAdapter getAdapter() { return adapter; }
    public boolean isConnected() { return host != null; }
    public boolean isRegistered() { return registered; }

    private void status(final String text, final boolean connected) {
        main.post(() -> listener.onStatus(text, connected));
    }

    private void parseLeds(byte[] data) {
        if (data != null && data.length > 0) leds = data[data.length - 1] & 0xFF;
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean reg) {
            registered = reg;
            if (!reg) {
                host = null;
                status("A billentyűzet-szolgáltatás leállt", false);
                return;
            }
            BluetoothDevice target = pluggedDevice != null ? pluggedDevice : savedHost();
            if (target != null) {
                status("Kapcsolódás: " + name(target) + "…", false);
                hid.connect(target);
            } else {
                status("Kész. Párosítsd a notebookkal (lásd: Súgó).", false);
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                prefs.edit().putString("host", device.getAddress()).apply();
                status("Csatlakozva: " + name(device), true);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Kapcsolódás: " + name(device) + "…", false);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (host == null || device.equals(host)) {
                    host = null;
                    leds = -1;
                    status("Nincs kapcsolat – koppints a Csatlakozás gombra", false);
                }
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            BluetoothHidDevice h = hid;
            if (h == null) return;
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                h.replyReport(device, type, REPORT_ID, new byte[8]);
            } else {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) parseLeds(data);
            BluetoothHidDevice h = hid;
            if (h != null) h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
        }

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
            parseLeds(data);
        }
    };

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "QR olvaso", "Telefonos QR-olvaso", "hu.qrolvaso",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR);
            BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX);
            if (!hid.registerApp(sdp, null, qos, executor, callback)) {
                status("Nem sikerült a billentyűzet-mód. Zárd be a Prezenter appot, és indítsd újra a Bluetooth-t.", false);
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null;
                host = null;
                registered = false;
                status("Bluetooth szolgáltatás leállt", false);
            }
        }
    };

    public boolean start() {
        if (adapter == null) return false;
        status("Indítás…", false);
        return adapter.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE);
    }

    public void stop() {
        BluetoothHidDevice h = hid;
        if (h != null) {
            try {
                if (host != null) h.disconnect(host);
                h.unregisterApp();
            } catch (Exception ignored) {
            }
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, h);
        }
        hid = null;
        host = null;
        registered = false;
        executor.shutdown();
    }

    public boolean connect(BluetoothDevice device) {
        BluetoothHidDevice h = hid;
        if (h == null || !registered) return false;
        if (host != null && !host.equals(device)) h.disconnect(host);
        return h.connect(device);
    }

    public Set<BluetoothDevice> bondedDevices() {
        return adapter.getBondedDevices();
    }

    // ---------- Gépelés ----------

    /** Begépeli a szöveget (+ utótag: Enter / Tab), a megadott Windows-kiosztás szerint. */
    public boolean type(String text, int layout, int suffixKey) {
        final BluetoothHidDevice h = hid;
        final BluetoothDevice d = host;
        if (h == null || d == null) return false;
        final List<int[]> strokes = KeyMap.strokes(text, layout);
        if (suffixKey != 0) strokes.add(new int[]{0, suffixKey});
        final int lay = layout;
        executor.execute(() -> {
            for (int[] s : strokes) {
                if (s[0] == KeyMap.ALT_CODE) {
                    altCode(h, d, s[1]);
                } else {
                    int mod = s[0];
                    int l = leds;
                    if (l >= 0 && (l & LED_CAPSLOCK) != 0 && KeyMap.capsAffected(s[1], lay)) {
                        mod ^= KeyMap.SHIFT;
                    }
                    tap(h, d, mod, s[1]);
                }
            }
            send(h, d, 0, 0);
        });
        return true;
    }

    private void tap(BluetoothHidDevice h, BluetoothDevice d, int mod, int key) {
        send(h, d, mod, key);
        send(h, d, 0, 0);
    }

    /** Windows Alt+0NNN beírás a numerikus billentyűzettel (kiosztástól független). */
    private void altCode(BluetoothHidDevice h, BluetoothDevice d, int code) {
        int l = leds;
        boolean toggleNum = l >= 0 && (l & LED_NUMLOCK) == 0;
        if (toggleNum) tap(h, d, 0, KEY_NUMLOCK);
        send(h, d, MOD_LEFT_ALT, 0);
        String digits = "0" + code;
        for (int i = 0; i < digits.length(); i++) {
            int n = digits.charAt(i) - '0';
            int key = n == 0 ? 0x62 : 0x59 + (n - 1);
            send(h, d, MOD_LEFT_ALT, key);
            send(h, d, MOD_LEFT_ALT, 0);
        }
        send(h, d, 0, 0);
        if (toggleNum) tap(h, d, 0, KEY_NUMLOCK);
    }

    private void send(BluetoothHidDevice h, BluetoothDevice d, int mod, int key) {
        byte[] r = new byte[8];
        r[0] = (byte) mod;
        r[2] = (byte) key;
        boolean ok = h.sendReport(d, REPORT_ID, r);
        sleep(KEY_DELAY_MS);
        if (!ok) {
            sleep(30);
            h.sendReport(d, REPORT_ID, r);
            sleep(KEY_DELAY_MS);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private BluetoothDevice savedHost() {
        String addr = prefs.getString("host", null);
        if (addr == null || !BluetoothAdapter.checkBluetoothAddress(addr)) return null;
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (d.getAddress().equals(addr)) return d;
        }
        return null;
    }

    public static String name(BluetoothDevice d) {
        String n = null;
        try {
            n = d.getName();
        } catch (SecurityException ignored) {
        }
        return n != null ? n : d.getAddress();
    }
}
