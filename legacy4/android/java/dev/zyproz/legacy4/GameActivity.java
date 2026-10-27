package dev.zyproz.legacy4;

import android.app.NativeActivity;
import android.content.Context;
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
    }

    @Override
    protected void onDestroy() {
        if (broadcastLock != null && broadcastLock.isHeld()) {
            broadcastLock.release();
        }
        super.onDestroy();
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
        if (padKey) {
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
}
