package dev.zyproz.legacy4;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * The Legacy mod menu, opened by the MENU button: TRICKSHOT, BOTS, LAN, GRAPHICS, CONTROLS, ACCOUNT, like Legacy
 * Warfare and Legacy Ops. Each entry is a console line for the game (GameActivity.command); the trickshot ones are
 * carried out by the server (crates/server/src/legacy4.rs).
 */
public class ModMenu extends LinearLayout {

    interface Host {
        void closeMods();

        void controlsChanged();
    }

    private static final int GREEN = Color.rgb(57, 255, 20);
    private static final String[] TABS = {"TRICKSHOT", "BOTS", "LAN", "GRAPHICS", "CONTROLS", "ACCOUNT"};
    private static final String[][] MAPS = {
            {"Backlot", "mp_backlot"}, {"Bloc", "mp_bloc"}, {"Bog", "mp_bog"}, {"Broadcast", "mp_broadcast"},
            {"Chinatown", "mp_carentan"}, {"Countdown", "mp_countdown"}, {"Crash", "mp_crash"},
            {"Crossfire", "mp_crossfire"}, {"District", "mp_citystreets"}, {"Downpour", "mp_farm"},
            {"Overgrown", "mp_overgrown"}, {"Pipeline", "mp_pipeline"}, {"Shipment", "mp_shipment"},
            {"Showdown", "mp_showdown"}, {"Strike", "mp_strike"}, {"Vacant", "mp_vacant"},
            {"Wet Work", "mp_cargoship"}, {"Creek", "mp_creek"}, {"Killhouse", "mp_killhouse"},
            {"Winter Crash", "mp_crash_snow"},
    };

    private final Host host;
    private final SharedPreferences prefs;
    private final LinearLayout tabRow;
    private final LinearLayout body;
    private final TextView note;
    private int tab;
    private String gametype;

    public ModMenu(Context context, Host host) {
        super(context);
        this.host = host;
        prefs = context.getSharedPreferences("legacy4", Context.MODE_PRIVATE);
        gametype = prefs.getString("gametype", "sd");
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 12, 14, 12));
        bg.setStroke(dp(2), GREEN);
        bg.setCornerRadius(dp(10));
        setBackground(bg);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("LEGACY 4 MOBILE", 18, GREEN);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("CLOSE", false);
        close.setOnClickListener(v -> host.closeMods());
        top.addView(close);
        addView(top);

        tabRow = new LinearLayout(context);
        HorizontalScrollView tabs = new HorizontalScrollView(context);
        tabs.addView(tabRow);
        addView(tabs);

        note = label("", 13, Color.LTGRAY);
        addView(note);

        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        show(0);
    }

    // ---------- tabs ----------

    private void show(int index) {
        tab = index;
        tabRow.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            Button b = button(TABS[i], i == index);
            b.setOnClickListener(v -> show(t));
            tabRow.addView(b);
        }
        body.removeAllViews();
        note.setText("");
        switch (TABS[index]) {
            case "TRICKSHOT":
                trickshot();
                break;
            case "BOTS":
                bots();
                break;
            case "LAN":
                lan();
                break;
            case "GRAPHICS":
                graphics();
                break;
            case "CONTROLS":
                controls();
                break;
            default:
                account();
                break;
        }
    }

    private boolean vip() {
        return GameActivity.tier() > 0;
    }

    private void trickshot() {
        row(cmd("SAVE POSITION", "l4save"), cmd("LOAD POSITION", "l4load"), cmd("BOT HERE", "l4bothere"));
        if (vip()) {
            hint("VIP (works in a game you host):");
            row(cmd("NOCLIP ON/OFF", "l4noclip"), cmd("EXPLOSIVE BULLETS", "l4explosive"),
                    cmd("GOD MODE ON/OFF", "l4god"));
            row(cmd("FREEZE BOT", "l4botfreeze"), cmd("MOVE BOT", "l4botmove"));
        } else {
            hint("Noclip, explosive bullets, god mode, freeze/move bot: VIP only (ACCOUNT tab).");
        }
        hint("Trickshot game: LAN tab > pick the mode and a map, then add a bot in the BOTS tab.");
    }

    private void bots() {
        hint("Bots work in a game you host (LAN tab, or Start New Server).");
        row(cmd("1 BOT", "bots 1"), cmd("2 BOTS", "bots 2"), cmd("4 BOTS", "bots 4"));
        row(cmd("REMOVE BOTS", "bots 0"));
        if (vip()) {
            row(cmd("BOT HERE", "l4bothere"), cmd("FREEZE BOT", "l4botfreeze"), cmd("MOVE BOT", "l4botmove"));
        } else {
            row(cmd("BOT HERE", "l4bothere"));
        }
    }

    private void lan() {
        hint("HOST: your phone runs the game, friends join you. Mode: " + modeName(gametype));
        row(mode("SEARCH & DESTROY", "sd"), mode("TEAM DEATHMATCH", "war"), mode("FREE FOR ALL", "dm"));
        LinearLayout r = null;
        for (int i = 0; i < MAPS.length; i++) {
            if (i % 4 == 0) {
                r = new LinearLayout(getContext());
                body.addView(r);
            }
            final String map = MAPS[i][1];
            Button b = button(MAPS[i][0].toUpperCase(), false);
            b.setOnClickListener(v -> {
                prefs.edit().putString("gametype", gametype).apply();
                GameActivity.command("set g_gametype " + gametype + "; map " + map);
                host.closeMods();
            });
            r.addView(b, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        hint("JOIN: same Wi-Fi: the game's Join Game menu lists the games. Over Tailscale: type the host's"
                + " address (100.x.x.x).");
        EditText ip = field("Host address, e.g. 100.64.1.2", prefs.getString("last_ip", ""));
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(ip);
        Button join = button("JOIN BY ADDRESS", false);
        join.setOnClickListener(v -> {
            String a = ip.getText().toString().trim();
            if (!a.isEmpty()) {
                prefs.edit().putString("last_ip", a).apply();
                GameActivity.command("connect " + a);
                host.closeMods();
            }
        });
        row(join, cmd("LEAVE GAME", "disconnect"));
        hint("This phone's addresses (give one to your friends): " + addresses());
    }

    private void graphics() {
        hint("Lower settings = more frames per second and a cooler phone.");
        row(cmd("PERFORMANCE", "seta sm_enable 0; seta r_dof_enable 0; seta com_maxfps 60"),
                cmd("BALANCED", "seta sm_enable 1; seta r_dof_enable 0; seta com_maxfps 60"),
                cmd("QUALITY", "seta sm_enable 1; seta r_dof_enable 1; seta com_maxfps 60"));
        row(cmd("30 FPS", "seta com_maxfps 30"), cmd("60 FPS", "seta com_maxfps 60"),
                cmd("90 FPS", "seta com_maxfps 90"), cmd("120 FPS", "seta com_maxfps 120"));
        row(cmd("FOV 65", "seta cg_fov 65"), cmd("FOV 80", "seta cg_fov 80"), cmd("FOV 90", "seta cg_fov 90"));
    }

    private void controls() {
        float speed = prefs.getFloat("look_speed", 0.15f);
        float opacity = prefs.getFloat("opacity", 0.55f);
        float size = prefs.getFloat("size", 1f);
        hint(String.format(java.util.Locale.US, "Look speed %.2f   Buttons visibility %d%%   Buttons size %d%%",
                speed, Math.round(opacity * 100), Math.round(size * 100)));
        row(setting("LOOK SLOWER", "look_speed", speed / 1.15f), setting("LOOK FASTER", "look_speed", speed * 1.15f));
        row(setting("BUTTONS FAINTER", "opacity", Math.max(0.15f, opacity - 0.1f)),
                setting("BUTTONS BRIGHTER", "opacity", Math.min(1f, opacity + 0.1f)));
        row(setting("BUTTONS SMALLER", "size", Math.max(0.7f, size - 0.1f)),
                setting("BUTTONS BIGGER", "size", Math.min(1.4f, size + 0.1f)));
        int stick = prefs.getInt("stick_sens", 5);
        hint("Controller turn speed: " + stick + " / 10   (" + stickYaw(stick) + " degrees per second)");
        row(intSetting("STICK SLOWER", "stick_sens", Math.max(1, stick - 1)),
                intSetting("STICK FASTER", "stick_sens", Math.min(10, stick + 1)));
        boolean toggle = prefs.getBoolean("aim_toggle", true);
        Button aim = button(toggle ? "AIM: TAP TO SWITCH (ON)" : "AIM: HOLD (ON)", false);
        aim.setOnClickListener(v -> {
            prefs.edit().putBoolean("aim_toggle", !toggle).apply();
            host.controlsChanged();
            show(tab);
        });
        row(aim);
        hint("Controller: plug it in or pair it over Bluetooth, the buttons hide themselves.");
    }

    private void account() {
        hint("Your name in game.");
        EditText name = field("Name", prefs.getString("name", ""));
        body.addView(name);
        Button set = button("CHANGE NAME", false);
        set.setOnClickListener(v -> {
            String n = name.getText().toString().trim().replace("\"", "'");
            if (!n.isEmpty()) {
                prefs.edit().putString("name", n).apply();
                GameActivity.command("name \"" + n + "\"");
                note.setText("Name: " + n);
            }
        });
        row(set);

        int tier = GameActivity.tier();
        String id = playerId();
        String grouped = id.length() == 16
                ? id.substring(0, 4) + "-" + id.substring(4, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12)
                : id;
        TextView status = label("Your ID: " + grouped + "    Status: " + tierName(tier), 15, GREEN);
        status.setTextIsSelectable(true);
        body.addView(status);
        if (tier == 0) {
            hint("Send your ID to ZyProz to get a VIP key, then paste it here.");
        }
        EditText key = field("Licence key (vip.XXXX... or admin.XXXX...)", "");
        body.addView(key);
        Button enter = button("ENTER LICENCE KEY", false);
        enter.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) {
                return;
            }
            int result = GameActivity.submitKey(k);
            if (result < 0) {
                note.setText("Key refused: wrong ID, expired or not genuine.");
            } else {
                show(tab);
                note.setText("Key accepted: " + tierName(result));
            }
        });
        row(enter);
        if (tier > 0) {
            hint("VIP:");
            row(cmd("UNLOCK ALL", "l4unlockall"));
            hint("Rank 55, every weapon, perk and attachment, Create a Class. Takes effect at the next game.");
        }
    }

    private static String tierName(int tier) {
        return tier == 2 ? "ADMIN" : tier == 1 ? "VIP" : "FREE";
    }

    /** The id the game wrote in the app folder (Android/media/dev.zyproz.legacy4/player-id.txt). */
    private String playerId() {
        for (java.io.File dir : getContext().getExternalMediaDirs()) {
            if (dir == null) {
                continue;
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(new java.io.File(dir, "player-id.txt")))) {
                String line = r.readLine();
                if (line != null) {
                    return line.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return "(unknown)";
    }

    // ---------- pieces ----------

    private Button cmd(String text, String line) {
        Button b = button(text, false);
        b.setOnClickListener(v -> {
            GameActivity.command(line);
            note.setText("Sent: " + text);
        });
        return b;
    }

    private Button mode(String text, String gt) {
        Button b = button(text, gt.equals(gametype));
        b.setOnClickListener(v -> {
            gametype = gt;
            show(tab);
        });
        return b;
    }

    private Button setting(String text, String key, float value) {
        Button b = button(text, false);
        b.setOnClickListener(v -> {
            prefs.edit().putFloat(key, value).apply();
            host.controlsChanged();
            show(tab);
        });
        return b;
    }

    private Button intSetting(String text, String key, int value) {
        Button b = button(text, false);
        b.setOnClickListener(v -> {
            prefs.edit().putInt(key, value).apply();
            host.controlsChanged();
            show(tab);
            note.setText(text + ": " + value + " / 10");
        });
        return b;
    }

    private static String modeName(String gt) {
        switch (gt) {
            case "war":
                return "Team Deathmatch";
            case "dm":
                return "Free for all";
            default:
                return "Search & Destroy";
        }
    }

    private void row(Button... buttons) {
        LinearLayout r = new LinearLayout(getContext());
        for (Button b : buttons) {
            r.addView(b, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        body.addView(r);
    }

    private void hint(String s) {
        body.addView(label(s, 13, Color.LTGRAY));
    }

    private TextView label(String s, int size, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        return t;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(getContext());
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        return e;
    }

    private Button button(String text, boolean selected) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(true);
        b.setTextSize(12);
        b.setTextColor(selected ? Color.BLACK : Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? GREEN : Color.argb(200, 40, 44, 40));
        bg.setStroke(dp(1), selected ? GREEN : Color.argb(200, 90, 100, 90));
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        // A controller shows where it is: the focused button gets a white frame.
        b.setOnFocusChangeListener((v, has) -> {
            GradientDrawable f = new GradientDrawable();
            f.setColor(selected ? GREEN : Color.argb(200, 40, 44, 40));
            f.setStroke(dp(has ? 3 : 1), has ? Color.WHITE : selected ? GREEN : Color.argb(200, 90, 100, 90));
            f.setCornerRadius(dp(6));
            v.setBackground(f);
        });
        return b;
    }

    /** Puts the controller's focus on the first tab. */
    void focusFirst() {
        if (tabRow.getChildCount() > tab) {
            tabRow.getChildAt(tab).requestFocus();
        }
    }

    /** LB / RB: the previous or next tab. */
    void nextTab(int step) {
        show((tab + step + TABS.length) % TABS.length);
        focusFirst();
    }

    /** Degrees per second at full stick for sensitivity 1..10. */
    static int stickYaw(int level) {
        return 80 + 40 * Math.max(1, Math.min(10, level));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String addresses() {
        StringBuilder out = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        out.append(ip).append(ip.startsWith("100.") ? " (Tailscale)" : "").append("   ");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out.length() == 0 ? "none (no network?)" : out.toString().trim();
    }
}
