package de.codevoid.aNavMode.remote;

/**
 * Events emitted by RemoteControlManager.
 *
 * All keys emit KeyDown / KeyUp for raw press/release.
 * On release, ShortPress or LongPress is additionally emitted:
 *   - LongPress only for CONFIRM and BACK when held >= 500ms.
 *   - ShortPress for everything else.
 *
 * JoyInput carries normalised (dx, dy) in [-1, 1]; (0,0) = neutral.
 */
public abstract class RemoteEvent {

    private RemoteEvent() {}

    public static final class KeyDown extends RemoteEvent {
        public final RemoteKey key;
        public KeyDown(RemoteKey key) { this.key = key; }
    }

    public static final class KeyUp extends RemoteEvent {
        public final RemoteKey key;
        public KeyUp(RemoteKey key) { this.key = key; }
    }

    public static final class ShortPress extends RemoteEvent {
        public final RemoteKey key;
        public ShortPress(RemoteKey key) { this.key = key; }
    }

    public static final class LongPress extends RemoteEvent {
        public final RemoteKey key;
        public LongPress(RemoteKey key) { this.key = key; }
    }

    public static final class JoyInput extends RemoteEvent {
        public final float dx, dy;
        public JoyInput(float dx, float dy) { this.dx = dx; this.dy = dy; }
    }
}
