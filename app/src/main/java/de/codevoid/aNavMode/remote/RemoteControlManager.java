package de.codevoid.aNavMode.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages the DMD remote control BroadcastReceiver.
 *
 * Call register() in Activity.onResume, unregister() in Activity.onPause.
 * Set a listener via setListener() to receive RemoteEvents on the main thread.
 *
 * Handles three broadcast extras (mutually exclusive per broadcast):
 *   key_press   — int keycode, physical button down
 *   key_release — int keycode, physical button up
 *   joy         — String, 360° head joystick (e.g. "U3R2", "Y0X0")
 *
 * Long-press detection applies to CONFIRM and BACK only (>= 500ms hold).
 *
 * Ported from aWayToGo/RemoteControlManager.kt.
 */
public class RemoteControlManager {

    public interface EventListener {
        void onEvent(RemoteEvent event);
    }

    private static final String TAG                    = "RemoteControl";
    private static final String ACTION                 = "com.thorkracing.wireddevices.keypress";
    private static final long   LONG_PRESS_THRESHOLD_MS = 500L;

    private static final Set<RemoteKey> LONG_PRESS_KEYS = new HashSet<>();
    static {
        LONG_PRESS_KEYS.add(RemoteKey.CONFIRM);
        LONG_PRESS_KEYS.add(RemoteKey.BACK);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EventListener listener;

    // Per-key pending long-press Runnables — cancelled on key-up if not yet fired.
    private final Map<RemoteKey, Runnable> pendingLongPress = new HashMap<>();
    // Keys for which the long-press timer has already fired this press cycle.
    private final Set<RemoteKey> longPressEmitted = new HashSet<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION.equals(intent.getAction())) return;

            if (intent.hasExtra("joy")) {
                String value = intent.getStringExtra("joy");
                if (value == null) value = "Y0X0";
                Log.d(TAG, "joy=" + value);
                onJoy(value);
            } else if (intent.hasExtra("key_press")) {
                int code = intent.getIntExtra("key_press", 0);
                Log.d(TAG, "key_press=" + code);
                onKeyPress(code);
            } else if (intent.hasExtra("key_release")) {
                int code = intent.getIntExtra("key_release", 0);
                Log.d(TAG, "key_release=" + code);
                onKeyRelease(code);
            } else {
                Log.w(TAG, "broadcast with no recognised extra");
            }
        }
    };

    public RemoteControlManager(Context context) {
        this.context = context;
    }

    public void setListener(EventListener listener) {
        this.listener = listener;
    }

    public void register() {
        IntentFilter filter = new IntentFilter(ACTION);
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        Log.d(TAG, "receiver registered for " + ACTION);
    }

    public void unregister() {
        for (Runnable r : pendingLongPress.values()) handler.removeCallbacks(r);
        pendingLongPress.clear();
        longPressEmitted.clear();
        try {
            context.unregisterReceiver(receiver);
            Log.d(TAG, "receiver unregistered");
        } catch (IllegalArgumentException e) {
            // Not registered — harmless.
        }
    }

    private void emit(RemoteEvent event) {
        if (listener != null) listener.onEvent(event);
    }

    private void onKeyPress(int keyCode) {
        RemoteKey key = RemoteKey.fromKeyCode(keyCode);
        if (key == null) return;

        emit(new RemoteEvent.KeyDown(key));

        if (LONG_PRESS_KEYS.contains(key)) {
            Runnable runnable = () -> {
                Log.d(TAG, "long_press fired for " + key);
                longPressEmitted.add(key);
                emit(new RemoteEvent.LongPress(key));
            };
            pendingLongPress.put(key, runnable);
            handler.postDelayed(runnable, LONG_PRESS_THRESHOLD_MS);
        }
    }

    private void onKeyRelease(int keyCode) {
        RemoteKey key = RemoteKey.fromKeyCode(keyCode);
        if (key == null) return;

        emit(new RemoteEvent.KeyUp(key));

        Runnable pending = pendingLongPress.remove(key);
        if (pending != null) handler.removeCallbacks(pending);

        if (!longPressEmitted.remove(key)) {
            emit(new RemoteEvent.ShortPress(key));
        }
    }

    private void onJoy(String value) {
        float[] axes = parseJoy(value);
        emit(new RemoteEvent.JoyInput(axes[0], axes[1]));
    }

    /**
     * Parse a DMD joystick string into normalised [dx, dy] in [-1, 1].
     *
     * Format: optional vertical component (U|D + digit 2–5) followed by
     * optional horizontal component (L|R + digit 2–5). Neutral strings
     * "Y0X0", "Y0", "X0" map to (0, 0). Digit maps to magnitude: digit/5f.
     *
     * Positive dy = joystick pushed up. Positive dx = joystick pushed right.
     */
    private float[] parseJoy(String value) {
        if ("Y0X0".equals(value) || "Y0".equals(value) || "X0".equals(value)) {
            return new float[]{0f, 0f};
        }
        float dy = magnitudeAfter(value, 'U') - magnitudeAfter(value, 'D');
        float dx = magnitudeAfter(value, 'R') - magnitudeAfter(value, 'L');
        return new float[]{dx, dy};
    }

    private float magnitudeAfter(String value, char prefix) {
        int idx = value.indexOf(prefix);
        if (idx < 0 || idx + 1 >= value.length()) return 0f;
        int digit = Character.digit(value.charAt(idx + 1), 10);
        return digit < 0 ? 0f : digit / 5f;
    }
}
