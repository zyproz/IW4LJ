package dev.zyproz.legacy4;

import android.app.AlertDialog;
import android.app.NativeActivity;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.hardware.input.InputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Runs the game (libcod4e_android.so) full screen, with the screen kept on.
 *
 * A NativeActivity hands its input to native code, where the window library turns a controller's buttons into
 * unknown keys and drops its sticks. So this activity keeps the input and sends touches and controllers to the game
 * over JNI (the same approach as Legacy Warfare). Wired and Bluetooth controllers arrive the same way.
 */
public class GameActivity extends NativeActivity implements InputManager.InputDeviceListener {
    static {
        // NativeActivity opens the library outside the VM's list; the native methods need it here too.
        System.loadLibrary("cod4e_android");
    }

    private WifiManager.MulticastLock broadcastLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout overlay;
    private TouchPad pad;
    private ModMenu mods;
    private WindowManager.LayoutParams overlayParams;
    private boolean lastMenu;
    private boolean lastTextField;
    private AlertDialog textDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Input goes through the views, to the dispatch methods below.
        getWindow().takeInputQueue(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(attributes);
        getSystemService(InputManager.class).registerInputDeviceListener(this, null);
        // Wi-Fi drops broadcast packets unless asked: LAN games are found by broadcast.
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            broadcastLock = wifi.createMulticastLock("legacy4-lan");
            broadcastLock.setReferenceCounted(false);
            broadcastLock.acquire();
        } catch (RuntimeException ignored) {
        }
        hideBars();
        applyControls();
        // The touch controls live in a window of their own over the game: the game's window is all its surface.
        getWindow().getDecorView().post(this::addOverlay);
    }

    private void addOverlay() {
        if (overlay != null || getWindow().getDecorView().getWindowToken() == null) {
            if (overlay == null) {
                handler.postDelayed(this::addOverlay, 200);
            }
            return;
        }
        overlay = new FrameLayout(this) {
            /** The Legacy menu with a controller: B, Start or the Xbox/PS button close it, A selects, LB/RB change tab. */
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (mods != null && mods.getVisibility() == View.VISIBLE) {
                    int c = e.getKeyCode();
                    boolean up = e.getAction() == KeyEvent.ACTION_UP;
                    switch (c) {
                        case KeyEvent.KEYCODE_BUTTON_MODE:
                        case KeyEvent.KEYCODE_BUTTON_B:
                        case KeyEvent.KEYCODE_BUTTON_START:
                        case KeyEvent.KEYCODE_BACK:
                            if (up) {
                                closeMods();
                            }
                            return true;
                        case KeyEvent.KEYCODE_BUTTON_A:
                            return super.dispatchKeyEvent(new KeyEvent(e.getAction(), KeyEvent.KEYCODE_DPAD_CENTER));
                        case KeyEvent.KEYCODE_BUTTON_L1:
                        case KeyEvent.KEYCODE_BUTTON_R1:
                            if (up) {
                                mods.nextTab(c == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
                            }
                            return true;
                        default:
                            break;
                    }
                }
                return super.dispatchKeyEvent(e);
            }
        };
        pad = new TouchPad(this, this::openMods);
        overlay.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mods = new ModMenu(this, new ModMenu.Host() {
            @Override
            public void closeMods() {
                GameActivity.this.closeMods();
            }

            @Override
            public void controlsChanged() {
                applyControls();
            }

            @Override
            public void editLayout() {
                GameActivity.this.closeMods();
                pad.startEdit();
            }
        });
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.9f);
        overlay.addView(mods, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        mods.setVisibility(View.GONE);
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        overlayParams.token = getWindow().getDecorView().getWindowToken();
        overlayParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try {
            getWindowManager().addView(overlay, overlayParams);
        } catch (RuntimeException e) {
            overlay = null;
            return;
        }
        applyControls();
        handler.post(this::watchMenu);
    }

    /** Follows whether one of the game's menus is up: the buttons step aside and a finger becomes the mouse. */
    private void watchMenu() {
        if (pad == null) {
            return;
        }
        boolean menu = nativeMenuOpen();
        if (menu != lastMenu) {
            lastMenu = menu;
            pad.releaseAll();
            pad.gameMenu = menu;
            pad.invalidate();
        }
        boolean field = nativeTextField();
        if (field && !lastTextField && textDialog == null) {
            askText();
        }
        lastTextField = field;
        handler.postDelayed(this::watchMenu, 120);
    }

    /**
     * One of the game's text fields (a new profile's name, a password...) is being typed in: the phone's keyboard
     * opens in a small box, and what is typed replaces the field's text, followed by Enter.
     */
    private void askText() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        textDialog = new AlertDialog.Builder(this)
                .setTitle("Legacy 4")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> sendText(input.getText().toString()))
                .setNegativeButton("Cancel", (d, w) -> nativeTextKey(-4))
                .setOnCancelListener(d -> nativeTextKey(-4))
                .create();
        textDialog.setOnDismissListener(d -> {
            textDialog = null;
            hideBars();
        });
        input.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_NULL) {
                sendText(input.getText().toString());
                if (textDialog != null) {
                    textDialog.dismiss();
                }
                return true;
            }
            return false;
        });
        if (textDialog.getWindow() != null) {
            textDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        textDialog.show();
        input.requestFocus();
    }

    /** The field's old text goes (End, then Backspace), the typed text comes in, Enter ends the typing. */
    private static void sendText(String text) {
        nativeTextKey(-1);
        for (int i = 0; i < 64; i++) {
            nativeTextKey(-2);
        }
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            nativeTextKey(c);
            i += Character.charCount(c);
        }
        nativeTextKey(-3);
    }

    private void openMods() {
        if (mods == null) {
            return;
        }
        pad.releaseAll();
        pad.setVisibility(View.GONE);
        mods.setVisibility(View.VISIBLE);
        // Typing an address or a name needs the keyboard: the overlay takes the focus while the menu is open.
        overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        getWindowManager().updateViewLayout(overlay, overlayParams);
        // A controller moves through the menu from its first button.
        mods.post(mods::focusFirst);
    }

    private void closeMods() {
        if (mods == null) {
            return;
        }
        mods.setVisibility(View.GONE);
        pad.setVisibility(View.VISIBLE);
        overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        getWindowManager().updateViewLayout(overlay, overlayParams);
        hideBars();
    }

    private void applyControls() {
        SharedPreferences prefs = getSharedPreferences("legacy4", MODE_PRIVATE);
        nativeLookSpeed(prefs.getFloat("look_speed", 0.15f));
        if (pad != null) {
            pad.opacity = prefs.getFloat("opacity", 0.55f);
            pad.scale = prefs.getFloat("size", 1f);
            pad.aimToggle = prefs.getBoolean("aim_toggle", true);
            pad.loadLayout();
            pad.invalidate();
        }
        // The controller's turn speed: the game's own gamepad rates, in degrees per second at full stick.
        int level = prefs.getInt("stick_sens", 8);
        int yaw = ModMenu.stickYaw(level);
        float ads = prefs.getInt("ads_sens", 6) / 10f;
        command("seta in_gamepad_yawrate " + yaw + "; seta in_gamepad_pitchrate " + (yaw * 7 / 10)
                + "; seta l4_ads_sens " + ads);
    }

    /** A controller is in use: the touch buttons hide until the screen is touched again. */
    private void controllerUsed() {
        if (pad != null && !pad.padActive) {
            pad.releaseAll();
            pad.padActive = true;
            pad.invalidate();
        }
    }

    // ---- Calls from the touch controls and the mod menu ----

    static void pressButton(int key, boolean down) {
        nativePadButton(-1, key, down);
    }

    static void touchMouse(int id, int phase, float x, float y) {
        nativeTouch(id, phase, x, y);
    }

    static void stick(float x, float y) {
        nativeStick(x, y);
    }

    static void look(float dx, float dy) {
        nativeLook(dx, dy);
    }

    /** 0 free, 1 VIP, 2 admin (Legacy licence key, same keys as Legacy Warfare). */
    static int tier() {
        return nativeTier();
    }

    /** Checks a licence key: the new tier, or -1 if refused. */
    static int submitKey(String key) {
        for (int i = 0; i < key.length(); ) {
            int c = key.codePointAt(i);
            nativeKeyChar(c);
            i += Character.charCount(c);
        }
        return nativeKeySubmit();
    }

    /** A console line for the game, as if typed at its console. */
    static void command(String line) {
        for (int i = 0; i < line.length(); ) {
            int c = line.codePointAt(i);
            nativeCommandChar(c);
            i += Character.charCount(c);
        }
        nativeCommandEnd();
    }

    @Override
    protected void onPause() {
        if (pad != null) {
            pad.releaseAll();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (overlay != null) {
            try {
                getWindowManager().removeView(overlay);
            } catch (RuntimeException ignored) {
            }
            overlay = null;
        }
        if (broadcastLock != null && broadcastLock.isHeld()) {
            broadcastLock.release();
        }
        super.onDestroy();
        // The game itself lives for the whole process (one engine per process): closed for good, the process ends
        // with it, so the next start is a fresh one. Leaving the app with the home button only pauses (no destroy).
        if (isFinishing()) {
            System.exit(0);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideBars();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---- Touch -------------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        View content = findViewById(android.R.id.content);
        if (content == null || content.getWidth() == 0 || content.getHeight() == 0) {
            return true;
        }
        float width = content.getWidth();
        float height = content.getHeight();
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
                nativeTouch(event.getPointerId(index), down ? 0 : 2,
                        event.getX(index) / width, event.getY(index) / height);
                break;
            }
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_CANCEL:
                for (int index = 0; index < event.getPointerCount(); index++) {
                    nativeTouch(event.getPointerId(index), action == MotionEvent.ACTION_MOVE ? 1 : 3,
                            event.getX(index) / width, event.getY(index) / height);
                }
                break;
            default:
                break;
        }
        return true;
    }

    // ---- Controller --------------------------------------------------------------------------------------------

    private static boolean isPad(int source) {
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    /** An axis centred on zero, with the device's own dead band removed. */
    private static float axis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(axis, event.getSource());
        if (range != null && Math.abs(value) <= range.getFlat()) {
            return 0f;
        }
        return value;
    }

    /** A trigger axis as 0..1, wherever its range starts. */
    private static float unit(MotionEvent event, int axis) {
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(axis);
        float value = event.getAxisValue(axis);
        if (range == null || range.getRange() <= 0f) {
            return Math.max(0f, value);
        }
        float pulled = (value - range.getMin()) / range.getRange();
        return pulled < 0.06f ? 0f : Math.min(1f, pulled);
    }

    /** Where a pad's right stick and triggers are: Android does not say, there are two common layouts. */
    private static final class PadLayout {
        int rightX = MotionEvent.AXIS_Z;
        int rightY = MotionEvent.AXIS_RZ;
        boolean triggersOnZ;
    }

    private static final class PadState {
        float lx, ly, rx, ry, lt, rt, hatX, hatY;
        boolean l2Key, r2Key;
    }

    private final SparseArray<PadLayout> layouts = new SparseArray<>();
    private final SparseArray<PadState> states = new SparseArray<>();

    private static boolean has(InputDevice device, int axis) {
        return device.getMotionRange(axis) != null;
    }

    private PadLayout layoutOf(InputDevice device) {
        int id = device == null ? -1 : device.getId();
        PadLayout layout = layouts.get(id);
        if (layout != null) {
            return layout;
        }
        layout = new PadLayout();
        if (device != null) {
            boolean ownTriggers = has(device, MotionEvent.AXIS_LTRIGGER) || has(device, MotionEvent.AXIS_RTRIGGER)
                    || has(device, MotionEvent.AXIS_BRAKE) || has(device, MotionEvent.AXIS_GAS);
            boolean zPair = has(device, MotionEvent.AXIS_Z) && has(device, MotionEvent.AXIS_RZ);
            boolean rPair = has(device, MotionEvent.AXIS_RX) && has(device, MotionEvent.AXIS_RY);
            if (rPair && !(zPair && ownTriggers)) {
                layout.rightX = MotionEvent.AXIS_RX;
                layout.rightY = MotionEvent.AXIS_RY;
                layout.triggersOnZ = zPair && !ownTriggers;
            }
        }
        layouts.put(id, layout);
        return layout;
    }

    private PadState stateOf(int device) {
        PadState state = states.get(device);
        if (state == null) {
            state = new PadState();
            states.put(device, state);
        }
        return state;
    }

    private void sendPad(int device, PadState s) {
        nativePadAxes(device, s.lx, s.ly, s.rx, s.ry,
                Math.max(s.lt, s.l2Key ? 1f : 0f), Math.max(s.rt, s.r2Key ? 1f : 0f), s.hatX, s.hatY);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (isPad(event.getSource()) && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            PadLayout layout = layoutOf(event.getDevice());
            PadState state = stateOf(event.getDeviceId());
            state.lx = axis(event, MotionEvent.AXIS_X);
            state.ly = axis(event, MotionEvent.AXIS_Y);
            state.rx = axis(event, layout.rightX);
            state.ry = axis(event, layout.rightY);
            if (layout.triggersOnZ) {
                state.lt = unit(event, MotionEvent.AXIS_Z);
                state.rt = unit(event, MotionEvent.AXIS_RZ);
            } else {
                state.lt = Math.max(0f, Math.max(axis(event, MotionEvent.AXIS_LTRIGGER),
                        axis(event, MotionEvent.AXIS_BRAKE)));
                state.rt = Math.max(0f, Math.max(axis(event, MotionEvent.AXIS_RTRIGGER),
                        axis(event, MotionEvent.AXIS_GAS)));
            }
            state.hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            state.hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            controllerUsed();
            sendPad(event.getDeviceId(), state);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean padKey = KeyEvent.isGamepadButton(code)
                || (isPad(event.getSource()) && code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT);
        if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
            // The Xbox / PlayStation button opens the Legacy menu, as in Legacy Warfare and Legacy Ops.
            if (down && event.getRepeatCount() == 0) {
                openMods();
            }
            return true;
        }
        if (padKey) {
            controllerUsed();
            if (event.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) {
                    // A trigger: merged with its axis, never sent as a button of its own.
                    PadState state = stateOf(event.getDeviceId());
                    if (code == KeyEvent.KEYCODE_BUTTON_L2) {
                        state.l2Key = down;
                    } else {
                        state.r2Key = down;
                    }
                    sendPad(event.getDeviceId(), state);
                } else {
                    nativePadButton(event.getDeviceId(), code, down);
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            // Some pads send B as Back too; the button is enough.
            if (down && event.getRepeatCount() == 0 && !isPad(event.getSource())) {
                nativeBack();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        layouts.remove(deviceId);
        states.remove(deviceId);
        nativePadRemoved(deviceId);
    }

    private static native void nativeTouch(int id, int phase, float x, float y);

    private static native void nativePadButton(int device, int keycode, boolean down);

    private static native void nativePadAxes(int device, float lx, float ly, float rx, float ry, float lt, float rt,
            float hatX, float hatY);

    private static native void nativePadRemoved(int device);

    private static native void nativeBack();

    private static native void nativeStick(float x, float y);

    private static native void nativeLook(float dx, float dy);

    private static native void nativeLookSpeed(float degreesPerPixel);

    private static native boolean nativeMenuOpen();

    private static native boolean nativeTextField();

    private static native void nativeTextKey(int code);

    private static native void nativeCommandChar(int c);

    private static native void nativeCommandEnd();

    private static native int nativeTier();

    private static native void nativeKeyChar(int c);

    private static native int nativeKeySubmit();
}
