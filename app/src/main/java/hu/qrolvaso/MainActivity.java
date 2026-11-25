package hu.qrolvaso;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import zxingcpp.BarcodeReader;

@SuppressLint("MissingPermission")
public class MainActivity extends ComponentActivity implements HidKeyboard.Listener {

    private static final int REQ_PERMS = 1;
    private static final int REQ_ENABLE_BT = 2;

    private static final int BG = Color.parseColor("#121418");
    private static final int CARD = Color.parseColor("#22262E");
    private static final int ACCENT = Color.parseColor("#2F80ED");
    private static final int TEXT = Color.parseColor("#F2F4F7");
    private static final int MUTED = Color.parseColor("#9AA3AF");
    private static final int OK = Color.parseColor("#27AE60");
    private static final int WARN = Color.parseColor("#E2A03F");

    // Beállítások
    private static final int MODE_CONTINUOUS = 0;
    private static final int MODE_TRIGGER = 1;
    private static final int[] SUFFIX_KEYS = {KeyMap.KEY_ENTER, KeyMap.KEY_TAB, 0};
    private SharedPreferences prefs;
    private int layout, suffix, mode;

    private HidKeyboard keyboard;
    private TextView statusText, lastText, historyText, hintText;
    private View statusDot;
    private Button triggerButton, torchButton;
    private PreviewView previewView;
    private AimOverlay overlay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private BarcodeReader reader;
    private Camera camera;
    private boolean torchOn;
    private ToneGenerator tone;

    // Olvasási állapot
    private volatile boolean paused;
    private volatile boolean triggerHeld;
    private volatile boolean triggerConsumed;
    private String lastSent;
    private long lastSeen;
    private final List<String> history = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        layout = prefs.getInt("layout", KeyMap.LAYOUT_HU);
        suffix = prefs.getInt("suffix", 0);
        mode = prefs.getInt("mode", MODE_CONTINUOUS);

        BarcodeReader.Options opts = new BarcodeReader.Options();
        opts.setFormats(EnumSet.of(BarcodeReader.Format.MICRO_QR_CODE, BarcodeReader.Format.QR_CODE));
        opts.setTryHarder(true);
        opts.setTryInvert(true);
        reader = new BarcodeReader(opts);

        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
        } catch (RuntimeException ignored) {
        }

        setContentView(buildUi());
        applyModeUi();
        keyboard = new HidKeyboard(this, this);
        requestNeededPermissions();
    }

    // ---------- Engedélyek, indítás ----------

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
            wanted.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            startEverything();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS) startEverything();
    }

    private void startEverything() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Kamera-engedély nélkül nem tudok olvasni", Toast.LENGTH_LONG).show();
        }
        if (keyboard.getAdapter() == null) {
            onStatus("Ezen a telefonon nincs Bluetooth.", false);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Add meg a \"Közeli eszközök\" engedélyt a Beállításokban.", false);
            return;
        }
        if (!keyboard.getAdapter().isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
        } else {
            startHid();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (keyboard.getAdapter().isEnabled()) startHid();
            else onStatus("Kapcsold be a Bluetooth-t.", false);
        }
    }

    private void startHid() {
        if (!keyboard.start()) {
            onStatus("Ez a telefon nem támogatja a Bluetooth-billentyűzet módot.", false);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (keyboard != null) keyboard.stop();
        analysisExecutor.shutdown();
        if (tone != null) tone.release();
        super.onDestroy();
    }

    // ---------- Kamera ----------

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindCamera(future.get());
            } catch (Exception e) {
                Toast.makeText(this, "A kamera nem indult el: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, getMainExecutor());
    }

    @SuppressWarnings("deprecation")
    private void bindCamera(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1080, 1920))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, image -> {
            try {
                if (!scanningActive()) return;
                int w = image.getWidth(), h = image.getHeight();
                int s = (int) (Math.min(w, h) * 0.7f);
                image.setCropRect(new Rect((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2));
                List<BarcodeReader.Result> results = reader.read(image);
                for (BarcodeReader.Result r : results) {
                    final String text = r.getText();
                    if (text != null && !text.isEmpty()) {
                        final boolean micro = r.getFormat() == BarcodeReader.Format.MICRO_QR_CODE;
                        handler.post(() -> onCodeFound(text, micro));
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                image.close();
            }
        });

        provider.unbindAll();
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
        camera.getCameraControl().setLinearZoom(prefs.getInt("zoom", 0) / 100f);
    }

    private boolean scanningActive() {
        if (mode == MODE_TRIGGER) return triggerHeld && !triggerConsumed;
        return !paused;
    }

    // ---------- Találat ----------

    private void onCodeFound(String text, boolean micro) {
        if (!scanningActive()) return;
        long now = SystemClock.elapsedRealtime();
        if (mode == MODE_CONTINUOUS) {
            // Ugyanazt a kódot csak akkor küldjük újra, ha közben 1,5 mp-re eltűnt a képről
            boolean same = text.equals(lastSent) && now - lastSeen < 1500;
            lastSeen = now;
            if (same) return;
        } else {
            triggerConsumed = true;
            lastSeen = now;
        }
        lastSent = text;

        boolean sent = keyboard.type(text, layout, SUFFIX_KEYS[suffix]);
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        overlay.flash(sent ? OK : WARN);

        lastText.setText(text);
        history.add(0, (micro ? "[Micro QR] " : "[QR] ") + text);
        while (history.size() > 6) history.remove(history.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < history.size(); i++) sb.append(history.get(i)).append('\n');
        historyText.setText(sb.toString().trim());

        if (!sent) Toast.makeText(this, "Nincs kapcsolat a notebookkal – nem lett elküldve", Toast.LENGTH_SHORT).show();
    }

    private void resend(View v) {
        if (lastSent == null) {
            Toast.makeText(this, "Még nincs beolvasott kód", Toast.LENGTH_SHORT).show();
            return;
        }
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (!keyboard.type(lastSent, layout, SUFFIX_KEYS[suffix])) {
            Toast.makeText(this, "Nincs kapcsolat a notebookkal", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Ravasz (gomb / hangerőgomb) ----------

    private void triggerDown() {
        if (mode != MODE_TRIGGER) return;
        triggerHeld = true;
        triggerConsumed = false;
        overlay.setActive(true);
    }

    private void triggerUp() {
        if (mode != MODE_TRIGGER) return;
        triggerHeld = false;
        overlay.setActive(false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mode == MODE_TRIGGER
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.getRepeatCount() == 0) triggerDown();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mode == MODE_TRIGGER
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            triggerUp();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void applyModeUi() {
        if (mode == MODE_TRIGGER) {
            triggerButton.setText("Nyomd és tartsd: OLVASÁS");
            hintText.setText("Ravasz mód: tartsd nyomva a gombot vagy a hangerőgombot, egy kód = egy küldés");
            overlay.setActive(false);
        } else {
            triggerButton.setText(paused ? "▶  Olvasás folytatása" : "❚❚  Szünet");
            hintText.setText("Folyamatos mód: a kódot a keretbe tartva magától beküldi");
            overlay.setActive(!paused);
        }
    }

    // ---------- Beállítások ----------

    private RadioGroup radios(LinearLayout parent, String title, String[] labels, int selected) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(4));
        parent.addView(t);
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setId(View.generateViewId());
            g.addView(b);
            if (i == selected) b.setChecked(true);
        }
        parent.addView(g);
        return g;
    }

    private static int checkedIndex(RadioGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            if (((RadioButton) g.getChildAt(i)).isChecked()) return i;
        }
        return 0;
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        final RadioGroup gLayout = radios(box, "A notebook billentyűzetkiosztása",
                new String[]{"Magyar", "Angol (US)", "Alt-kód (bármilyen kiosztás, lassabb)"}, layout);
        final RadioGroup gSuffix = radios(box, "A kód után",
                new String[]{"Enter", "Tab", "Semmi"}, suffix);
        final RadioGroup gMode = radios(box, "Olvasási mód",
                new String[]{"Folyamatos", "Ravasz (gombnyomásra)"}, mode);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Beállítások")
                .setView(sv)
                .setPositiveButton("Mentés", (d, w) -> {
                    layout = checkedIndex(gLayout);
                    suffix = checkedIndex(gSuffix);
                    mode = checkedIndex(gMode);
                    prefs.edit().putInt("layout", layout).putInt("suffix", suffix).putInt("mode", mode).apply();
                    triggerHeld = false;
                    paused = false;
                    applyModeUi();
                })
                .setNegativeButton("Mégse", null)
                .show();
    }

    // ---------- Párosítás ----------

    private void makeDiscoverable() {
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
        startActivity(i);
        Toast.makeText(this,
                "Most a notebookon: Beállítások → Bluetooth → Eszköz hozzáadása → válaszd ezt a telefont",
                Toast.LENGTH_LONG).show();
    }

    private void chooseHost() {
        if (!keyboard.isRegistered()) {
            Toast.makeText(this, "Még indul, próbáld újra pár másodperc múlva", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>(keyboard.bondedDevices());
        if (devices.isEmpty()) {
            makeDiscoverable();
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) names[i] = HidKeyboard.name(devices.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Melyik géphez csatlakozzon?")
                .setItems(names, (d, which) -> {
                    if (!keyboard.connect(devices.get(which))) {
                        Toast.makeText(this, "Nem sikerült kapcsolódni", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Új párosítás", (d, w) -> makeDiscoverable())
                .show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Súgó")
                .setMessage("PÁROSÍTÁS\n"
                        + "1. \"Csatlakozás\" → \"Új párosítás\", engedd a láthatóságot.\n"
                        + "2. Notebookon: Beállítások → Bluetooth és eszközök → Eszköz hozzáadása → Bluetooth → "
                        + "válaszd a telefont.\n"
                        + "Ha a Prezenter appal már párosítottad, nem kell újra – csak a Prezentert zárd be, "
                        + "mert egyszerre csak az egyik lehet billentyűzet.\n\n"
                        + "OLVASÁS\n"
                        + "Kattints a notebookon oda, ahová a kódnak kerülnie kell (pl. Excel cella), "
                        + "majd tartsd a kódot a keretbe. Apró Micro QR-hez húzd jobbra a Nagyítás csúszkát, "
                        + "és koppints a képre az élességért. Sípszó = elküldve.\n\n"
                        + "Ha furcsa karakterek jelennek meg (pl. y és z felcserélve), állítsd be a notebook "
                        + "kiosztását a Beállításokban.")
                .setPositiveButton("Rendben", null)
                .show();
    }

    @Override
    public void onStatus(String text, boolean connected) {
        statusText.setText(text);
        ((GradientDrawable) statusDot.getBackground()).setColor(connected ? OK : WARN);
    }

    // ---------- Felület ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String label, int color, float sp, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMargins(i == 0 ? 0 : dp(5), 0, i == views.length - 1 ? 0 : dp(5), 0);
            r.addView(views[i], lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        r.setLayoutParams(lp);
        return r;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        // Állapotsáv
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(WARN);
        statusDot.setBackground(dot);
        status.addView(statusDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        statusText = new TextView(this);
        statusText.setTextColor(MUTED);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setPadding(dp(10), 0, 0, 0);
        statusText.setText("Indítás…");
        status.addView(statusText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView help = new TextView(this);
        help.setText("Súgó");
        help.setTextColor(ACCENT);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        help.setPadding(dp(12), dp(8), 0, dp(8));
        help.setOnClickListener(v -> showHelp());
        status.addView(help);
        root.addView(status);

        // Kamera + célkereszt
        FrameLayout camBox = new FrameLayout(this);
        previewView = new PreviewView(this);
        camBox.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        overlay = new AimOverlay(this);
        camBox.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(dp(16));
        clip.setColor(Color.BLACK);
        camBox.setBackground(clip);
        camBox.setClipToOutline(true);
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(-1, 0, 1);
        camLp.setMargins(0, dp(6), 0, 0);
        root.addView(camBox, camLp);
        previewView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint p = previewView.getMeteringPointFactory().createPoint(e.getX(), e.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(p).build());
                v.performClick();
            }
            return true;
        });

        // Nagyítás
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomRow.setPadding(0, dp(6), 0, 0);
        TextView zl = new TextView(this);
        zl.setText("Nagyítás");
        zl.setTextColor(MUTED);
        zoomRow.addView(zl);
        SeekBar zoom = new SeekBar(this);
        zoom.setMax(100);
        zoom.setProgress(prefs.getInt("zoom", 0));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (camera != null) camera.getCameraControl().setLinearZoom(p / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("zoom", s.getProgress()).apply();
            }
        });
        zoomRow.addView(zoom, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(zoomRow);

        // Utolsó kód
        lastText = new TextView(this);
        lastText.setText("Még nincs beolvasott kód");
        lastText.setTextColor(TEXT);
        lastText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        lastText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lastText.setTextIsSelectable(true);
        lastText.setMaxLines(3);
        lastText.setPadding(0, dp(6), 0, 0);
        root.addView(lastText);
        historyText = new TextView(this);
        historyText.setTextColor(MUTED);
        historyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        historyText.setTypeface(Typeface.MONOSPACE);
        historyText.setMaxLines(4);
        root.addView(historyText);

        // Gombok
        torchButton = button("Lámpa", CARD, 14, v -> {
            if (camera == null) return;
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            ((GradientDrawable) v.getBackground()).setColor(torchOn ? WARN : CARD);
        });
        root.addView(row(
                button("↻ Újraküldés", CARD, 14, this::resend),
                torchButton,
                button("Beállítások", CARD, 14, v -> showSettings())));
        root.addView(row(
                button("Csatlakozás", Color.parseColor("#1B3A5C"), 14, v -> chooseHost()),
                button("Új párosítás", Color.parseColor("#1B3A5C"), 14, v -> makeDiscoverable())));

        triggerButton = button("", ACCENT, 20, null);
        triggerButton.setOnTouchListener((v, e) -> {
            if (mode == MODE_TRIGGER) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    triggerDown();
                } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    triggerUp();
                    v.performClick();
                }
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                paused = !paused;
                applyModeUi();
                v.performClick();
            }
            return false;
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(84));
        tlp.setMargins(0, dp(10), 0, 0);
        root.addView(triggerButton, tlp);

        hintText = new TextView(this);
        hintText.setTextColor(MUTED);
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hintText.setGravity(Gravity.CENTER);
        hintText.setPadding(0, dp(6), 0, 0);
        root.addView(hintText);
        return root;
    }

    /** Célkereszt: elsötétített szélek, középen a keret (zöld villanás = elküldve). */
    private static class AimOverlay extends View {
        private final Paint dim = new Paint();
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private boolean active = true;
        private int flashColor = 0;
        private final float density;

        AimOverlay(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            dim.setColor(Color.argb(120, 0, 0, 0));
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(4 * density);
            frame.setStrokeCap(Paint.Cap.ROUND);
        }

        void setActive(boolean a) {
            active = a;
            invalidate();
        }

        void flash(int color) {
            flashColor = color;
            invalidate();
            postDelayed(() -> {
                flashColor = 0;
                invalidate();
            }, 450);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h) * 0.62f;
            box.set((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRoundRect(box, 14 * density, 14 * density, Path.Direction.CW);
            c.drawPath(path, dim);
            int color = flashColor != 0 ? flashColor : (active ? Color.WHITE : Color.argb(110, 255, 255, 255));
            frame.setColor(color);
            float l = s * 0.18f;
            // sarkok
            c.drawLine(box.left, box.top, box.left + l, box.top, frame);
            c.drawLine(box.left, box.top, box.left, box.top + l, frame);
            c.drawLine(box.right, box.top, box.right - l, box.top, frame);
            c.drawLine(box.right, box.top, box.right, box.top + l, frame);
            c.drawLine(box.left, box.bottom, box.left + l, box.bottom, frame);
            c.drawLine(box.left, box.bottom, box.left, box.bottom - l, frame);
            c.drawLine(box.right, box.bottom, box.right - l, box.bottom, frame);
            c.drawLine(box.right, box.bottom, box.right, box.bottom - l, frame);
            if (flashColor != 0) {
                frame.setAlpha(60);
                Paint fill = new Paint();
                fill.setColor(flashColor);
                fill.setAlpha(60);
                c.drawRoundRect(box, 14 * density, 14 * density, fill);
            }
        }
    }
}
