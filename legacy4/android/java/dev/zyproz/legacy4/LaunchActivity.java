package dev.zyproz.legacy4;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Starts the game when the CoD4 files are in place and the last start went well. Otherwise it says what is
 * missing, or shows why the last start stopped (crash.txt, the end of journal.txt), with a button to try again.
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Asking for the media folder makes Android create it, so a file manager can copy the game into it.
        File media = null;
        for (File f : getExternalMediaDirs()) {
            if (f != null) {
                media = f;
                break;
            }
        }
        if (media == null) {
            media = new File("/storage/emulated/0/Android/media/" + getPackageName());
        }
        File game = new File(media, "cod4");
        game.mkdirs();
        File crash = new File(media, "crash.txt");
        File running = new File(media, "running.txt");
        File journal = new File(media, "journal.txt");

        boolean ready = new File(game, "main").isDirectory() && new File(game, "zone").isDirectory();
        String crashText = read(crash);
        boolean killed = running.exists();

        int banner = getResources().getIdentifier("banner", "drawable", getPackageName());
        if (ready && crashText.isEmpty() && !killed) {
            // The banner fills the screen for a moment, then the game starts.
            ImageView splash = new ImageView(this);
            splash.setBackgroundColor(Color.BLACK);
            splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (banner != 0) {
                splash.setImageResource(banner);
            }
            setContentView(splash);
            new Handler(Looper.getMainLooper()).postDelayed(this::startGame, 1800);
            return;
        }

        String text;
        if (!ready) {
            text = "LEGACY 4 - version 0.1\n\n"
                    + "Copy the Call of Duty 4 (PC, 1.7) folders main and zone, and the file localization.txt, into:\n\n"
                    + game.getAbsolutePath() + "\n\n"
                    + "Then open the app again.";
        } else {
            String why = crashText.isEmpty()
                    ? "The last start was closed by Android or crashed (no message).\n\nLast lines of journal.txt:\n"
                            + tail(read(journal), 40)
                    : crashText;
            text = "LEGACY 4 - version 0.1\n\nThe last start stopped:\n\n" + why
                    + "\n\nSend crash.txt and journal.txt from:\n" + media.getAbsolutePath();
        }

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setTextIsSelectable(true);
        view.setPadding(40, 40, 40, 40);

        Button go = new Button(this);
        go.setText(ready ? "START" : "CHECK AGAIN");
        go.setOnClickListener(v -> {
            crash.delete();
            running.delete();
            if (new File(game, "main").isDirectory() && new File(game, "zone").isDirectory()) {
                startGame();
            } else {
                recreate();
            }
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.BLACK);
        if (banner != 0) {
            ImageView top = new ImageView(this);
            top.setAdjustViewBounds(true);
            top.setImageResource(banner);
            box.addView(top);
        }
        box.addView(go);
        box.addView(view);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(box);
        setContentView(scroll);
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    private static String read(File f) {
        // readAllBytes only exists from Android 13; the app starts at Android 12.
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
            out.append(all[i]).append('\n');
        }
        return out.toString();
    }
}
