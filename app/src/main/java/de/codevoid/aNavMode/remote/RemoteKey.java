package de.codevoid.aNavMode.remote;

import java.util.HashMap;
import java.util.Map;

/**
 * Keys emitted by DMD remote controllers via the
 * com.thorkracing.wireddevices.keypress broadcast.
 */
public enum RemoteKey {
    UP(19),        // Map pan up
    DOWN(20),      // Map pan down
    LEFT(21),      // Map pan left
    RIGHT(22),     // Map pan right
    CONFIRM(66),   // Round button 1 — add waypoint at crosshair
    BACK(111),     // Round button 2 — remove last waypoint
    ZOOM_IN(136),  // Switch in — zoom in
    ZOOM_OUT(137); // Switch out — zoom out

    public final int keyCode;

    RemoteKey(int keyCode) {
        this.keyCode = keyCode;
    }

    private static final Map<Integer, RemoteKey> BY_KEY_CODE = new HashMap<>();
    static {
        for (RemoteKey k : values()) BY_KEY_CODE.put(k.keyCode, k);
    }

    public static RemoteKey fromKeyCode(int code) {
        return BY_KEY_CODE.get(code);
    }
}
