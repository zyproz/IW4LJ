package dev.zyproz.legacy4;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * The on-screen controls, drawn over the game (same layout as Legacy Warfare).
 *
 * In a match: a floating move stick on the left, a look drag on the right, one big FIRE button under the right thumb
 * ringed by AIM, JUMP, CROUCH, RELOAD..., and a row of small buttons along the top. FIRE also turns the view while
 * held. Each button presses the controller button the game already binds (input/extra_binds.cfg).
 * In one of the game's menus: only MENU stays, and a finger is the mouse.
 */
public class TouchPad extends View {

    /** A drawn button. x, y: centre from the right/bottom (or top) edge, r: radius; all in % of screen height. */
    static final class Btn {
        final String label;
        final int key;
        final boolean bottom;
        /** Measured from the left edge instead of the right. */
        boolean left;
        final float x, y, r;
        final boolean accent;
        boolean down;

        Btn(String label, int key, boolean bottom, float x, float y, float r, boolean accent) {
            this.label = label;
            this.key = key;
            this.bottom = bottom;
            this.x = x;
            this.y = y;
            this.r = r;
            this.accent = accent;
        }
    }

    // Android key codes the game reads as controller buttons (see android.rs pad_key).
    static final int FIRE = 105, AIM = 104, JUMP = 96, CROUCH = 97, RELOAD = 99, SWAP = 100, NADE = 102,
            TAC = 103, SPRINT = 106, MELEE = 107, PAUSE = 108, SCORE = 109, USE = 19, PRONE = 20;
    /** Not a game button: opens the Legacy mod menu. */
    static final int MODS = -1;

    final Btn[] buttons = {
            new Btn("FIRE", FIRE, true, 34, 25, 11, true),
            leftSide(new Btn("FIRE", FIRE, false, 13, 30, 8, true)),
            new Btn("AIM", AIM, true, 34, 50, 5.8f, false),
            new Btn("JUMP", JUMP, true, 12, 38, 6.8f, false),
            new Btn("CROUCH", CROUCH, true, 12, 12, 6.2f, false),
            new Btn("RELOAD", RELOAD, true, 61, 18, 6.2f, false),
            new Btn("USE", USE, true, 79, 32, 5.2f, false),
            new Btn("PRONE", PRONE, true, 78, 8, 4.8f, false),
            new Btn("SPRINT", SPRINT, true, 61, 40, 4.8f, false),
            new Btn("MENU", MODS, false, 7, 7, 4.3f, false),
            new Btn("SWAP", SWAP, false, 18, 7, 4.3f, false),
            new Btn("MELEE", MELEE, false, 29, 7, 4.3f, false),
            new Btn("NADE", NADE, false, 40, 7, 4.3f, false),
            new Btn("TAC", TAC, false, 51, 7, 4.3f, false),
            new Btn("SCORE", SCORE, false, 62, 7, 4.3f, false),
            new Btn("PAUSE", PAUSE, false, 73, 7, 4.3f, false),
    };

    /** A second FIRE on the left, under the left thumb's reach: shoot while the right thumb aims. */
    private static Btn leftSide(Btn b) {
        b.left = true;
        return b;
    }

    interface Listener {
        void openMods();
    }

    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** One of the game's own menus is up: only MENU is drawn, touches are the mouse. */
    boolean gameMenu;
    /** A controller is in use: nothing is drawn until the screen is touched again. */
    boolean padActive;
    /** 0..1: how visible the buttons are. */
    float opacity = 0.55f;
    /** Size of the buttons, 1 = default. */
    float scale = 1f;
    /** AIM is a switch (tap to aim, tap again to stop) rather than held. */
    boolean aimToggle = true;
    private boolean aimOn;

    // What each finger is doing.
    private static final int STICK = -2, LOOK = -3, MOUSE = -4;
    private final SparseArray<Integer> role = new SparseArray<>();
    private final SparseArray<float[]> last = new SparseArray<>();
    private float stickX, stickY, knobX, knobY;
    private boolean stickOn;

    public TouchPad(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        ring.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setColor(Color.WHITE);
    }

    private float unit() {
        return getHeight() / 100f;
    }

    private float cx(Btn b) {
        float d = b.x * unit() * (b.bottom ? scale : 1f);
        return b.left ? d : getWidth() - d;
    }

    private float cy(Btn b) {
        return b.bottom ? getHeight() - b.y * unit() * scale : b.y * unit();
    }

    private float radius(Btn b) {
        return b.r * unit() * (b.bottom ? scale : 1f);
    }

    private boolean shown(Btn b) {
        return !padActive && (!gameMenu || b.key == MODS);
    }

    private int hit(float x, float y) {
        for (int i = 0; i < buttons.length; i++) {
            Btn b = buttons[i];
            if (!shown(b)) {
                continue;
            }
            float r = radius(b) * 1.15f;
            float dx = x - cx(b), dy = y - cy(b);
            if (dx * dx + dy * dy <= r * r) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (padActive) {
            return;
        }
        int alpha = (int) (255 * opacity);
        ring.setStrokeWidth(unit() * 0.35f);
        for (Btn b : buttons) {
            if (!shown(b)) {
                continue;
            }
            float r = radius(b);
            fill.setColor(Color.argb(b.down ? alpha : alpha / 2, 20, 20, 20));
            ring.setColor(b.accent ? Color.argb(alpha, 57, 255, 20) : Color.argb(alpha, 230, 230, 230));
            canvas.drawCircle(cx(b), cy(b), r, fill);
            canvas.drawCircle(cx(b), cy(b), r, ring);
            text.setTextSize(Math.min(r * 0.55f, r * 3.2f / Math.max(3, b.label.length())));
            text.setAlpha(Math.min(255, alpha + 80));
            canvas.drawText(b.label, cx(b), cy(b) + text.getTextSize() * 0.35f, text);
        }
        if (!gameMenu && stickOn) {
            float r = stickRadius();
            fill.setColor(Color.argb(alpha / 3, 255, 255, 255));
            ring.setColor(Color.argb(alpha, 230, 230, 230));
            canvas.drawCircle(stickX, stickY, r, fill);
            canvas.drawCircle(stickX, stickY, r, ring);
            fill.setColor(Color.argb(alpha, 230, 230, 230));
            canvas.drawCircle(knobX, knobY, r * 0.4f, fill);
        }
    }

    private float stickRadius() {
        return 11f * unit();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (padActive) {
            padActive = false;
            invalidate();
        }
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    move(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                up(e.getPointerId(i), e.getX(i), e.getY(i), false);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    up(e.getPointerId(i), e.getX(i), e.getY(i), true);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void down(int id, float x, float y) {
        int b = hit(x, y);
        if (b >= 0) {
            Btn btn = buttons[b];
            if (btn.key == MODS) {
                listener.openMods();
                role.put(id, MOUSE - 100); // swallowed
                return;
            }
            if (btn.key == AIM && aimToggle) {
                // A switch: the finger is free to turn the view, and the aim stays on without holding.
                aimOn = !aimOn;
                btn.down = aimOn;
                GameActivity.pressButton(AIM, aimOn);
            } else {
                btn.down = true;
                GameActivity.pressButton(btn.key, true);
            }
            role.put(id, b);
            last.put(id, new float[] {x, y});
            invalidate();
            return;
        }
        if (gameMenu) {
            role.put(id, MOUSE);
            GameActivity.touchMouse(id, 0, x / getWidth(), y / getHeight());
            return;
        }
        if (x < getWidth() * 0.4f && !stickOn) {
            role.put(id, STICK);
            stickOn = true;
            stickX = knobX = x;
            stickY = knobY = y;
            invalidate();
            return;
        }
        role.put(id, LOOK);
        last.put(id, new float[] {x, y});
    }

    private void move(int id, float x, float y) {
        Integer r = role.get(id);
        if (r == null) {
            return;
        }
        if (r == MOUSE) {
            GameActivity.touchMouse(id, 1, x / getWidth(), y / getHeight());
        } else if (r == STICK) {
            float max = stickRadius();
            float dx = x - stickX, dy = y - stickY;
            float len = (float) Math.hypot(dx, dy);
            if (len > max) {
                dx = dx / len * max;
                dy = dy / len * max;
            }
            knobX = stickX + dx;
            knobY = stickY + dy;
            GameActivity.stick(dx / max, -dy / max);
            invalidate();
        } else if (r == LOOK || (r >= 0 && (buttons[r].key == FIRE || buttons[r].key == AIM))) {
            // FIRE and AIM turn the view while held, as on most phone shooters.
            float[] p = last.get(id);
            if (p != null) {
                GameActivity.look(x - p[0], y - p[1]);
                p[0] = x;
                p[1] = y;
            }
        }
    }

    private void up(int id, float x, float y, boolean cancel) {
        Integer r = role.get(id);
        role.remove(id);
        last.remove(id);
        if (r == null) {
            return;
        }
        if (r == MOUSE) {
            GameActivity.touchMouse(id, cancel ? 3 : 2, x / getWidth(), y / getHeight());
        } else if (r == STICK) {
            stickOn = false;
            GameActivity.stick(0f, 0f);
            invalidate();
        } else if (r >= 0) {
            if (buttons[r].key == AIM && aimToggle) {
                return; // stays as the switch left it
            }
            buttons[r].down = false;
            GameActivity.pressButton(buttons[r].key, false);
            invalidate();
        }
    }

    /** Lets go of everything (a menu opened, the app was left). */
    void releaseAll() {
        aimOn = false;
        for (Btn b : buttons) {
            if (b.down) {
                b.down = false;
                GameActivity.pressButton(b.key, false);
            }
        }
        role.clear();
        last.clear();
        stickOn = false;
        GameActivity.stick(0f, 0f);
        invalidate();
    }
}
