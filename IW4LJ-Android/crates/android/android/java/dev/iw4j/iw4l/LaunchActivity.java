package dev.iw4j.iw4l;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * The app's front door. It starts the game, unless the last start never got
 * as far as being paused (a crash during startup), in which case it shows
 * what is known about why instead of starting a game that will die again:
 * the panic the native code saved, the system log of the failed run, whether
 * the game files are where the app looks for them, and the device.
 */
public class LaunchActivity extends Activity {
    static final String STATE_FILE = "launch_state.txt";
    static final String CRASH_FILE = "crash.txt";

    static void writeState(Context context, String state) {
        try {
            Files.write(new File(context.getFilesDir(), STATE_FILE).toPath(),
                state.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String readFile(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * Two files from a development test run were left beside the game on one
     * phone: a script that opened a Terminal match on every launch, and an
     * environment line that handed out an akimbo Uzi. They are removed when
     * their contents are exactly the test's; any other file is left alone.
     */
    private void removeTestLeftovers() {
        File[] media = getExternalMediaDirs();
        if (media.length == 0 || media[0] == null) {
            return;
        }
        File cmds = new File(media[0], "cmds.txt");
        if (readFile(cmds).equals(
            "wait progression; unlock all; map mp_terminal; wait world; spawn 0; force_match_start; bot add 1")) {
            cmds.delete();
        }
        File env = new File(media[0], "env.txt");
        if (readFile(env).contains("IW4L_LATE_CMDS=give weapon/iw4:weapon/uzi_mp akimbo")) {
            env.delete();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && getIntent().getBooleanExtra("iw4l_dev", false)) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        removeTestLeftovers();
        // Without the game's folders there is nothing to start: say what to do.
        if (!haveGameFiles()) {
            showSetup();
            return;
        }
        boolean crashed = readFile(new File(getFilesDir(), STATE_FILE)).equals("starting");
        if (!crashed) {
            startGame();
            return;
        }
        showReport(buildReport());
    }

    private void startGame() {
        writeState(this, "starting");
        new File(getFilesDir(), CRASH_FILE).delete();
        Intent game = new Intent(this, GameActivity.class);
        // A development launch (`--ez iw4l_dev true`) carries on to the game.
        if (getIntent() != null && getIntent().getExtras() != null) {
            game.putExtras(getIntent().getExtras());
        }
        startActivity(game);
        finish();
    }

    private String buildReport() {
        StringBuilder out = new StringBuilder();
        out.append("Legacy Warfare did not start last time.\n\n");
        out.append("== Game files ==\n").append(gameFiles()).append("\n");
        String panic = readFile(new File(getFilesDir(), CRASH_FILE));
        out.append("== Error saved by the app ==\n")
            .append(panic.isEmpty() ? "(none: the app was killed or crashed natively)" : panic)
            .append("\n\n");
        out.append("== System log of the failed run ==\n").append(systemLog()).append("\n");
        out.append("== Device ==\n")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(")\n")
            .append("Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            .append("ABI ").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n')
            .append("SoC ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n')
            .append("Hardware ").append(Build.HARDWARE).append('\n');
        return out.toString();
    }

    // ---- First run: the game's folders are not there yet. ----

    /** The folder the app keeps its files in, which survives a reinstall. */
    private File baseDir() {
        File[] media = getExternalMediaDirs();
        return media.length > 0 && media[0] != null ? media[0] : getFilesDir();
    }

    /** Whether `zone` and `main` of the game are where the app looks for them. */
    private boolean haveGameFiles() {
        File[] media = getExternalMediaDirs();
        File[] roots = new File[] {
            media.length > 0 ? media[0] : null,
            getExternalFilesDir(null),
        };
        for (File root : roots) {
            if (root == null) {
                continue;
            }
            File mw2 = new File(root, "mw2");
            if (new File(mw2, "zone/english/common_mp.ff").isFile()
                && new File(mw2, "main").isDirectory()) {
                return true;
            }
        }
        return false;
    }

    private String setupStatus(boolean french) {
        File mw2 = new File(baseDir(), "mw2");
        boolean folder = mw2.isDirectory();
        boolean zone = new File(mw2, "zone/english/common_mp.ff").isFile();
        boolean main = new File(mw2, "main").isDirectory();
        String yes = french ? "OK" : "found";
        String no = french ? "ABSENT" : "missing";
        return (french ? "Dossier mw2 : " : "mw2 folder: ") + (folder ? yes : no)
            + "\nzone/english/common_mp.ff : " + (zone ? yes : no)
            + "\nmain : " + (main ? yes : no);
    }

    /** Opens the folder the game files go in, creating it if need be. */
    private void openGameFolder() {
        File mw2 = new File(baseDir(), "mw2");
        mw2.mkdirs();
        String path = mw2.getAbsolutePath();
        String prefix = "/storage/emulated/0/";
        if (path.startsWith(prefix)) {
            Uri uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + path.substring(prefix.length()));
            try {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivity(view);
                return;
            } catch (RuntimeException ignored) {
                // No app takes that: try the system file picker, opened on the folder.
            }
            try {
                Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                tree.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
                startActivity(tree);
                return;
            } catch (RuntimeException ignored) {
                // Fall through to showing the path.
            }
        }
        Toast.makeText(this, path, Toast.LENGTH_LONG).show();
    }

    private void showSetup() {
        final boolean fr = "fr".equals(Locale.getDefault().getLanguage());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(fr ? "Legacy Warfare a besoin des fichiers du jeu"
            : "Legacy Warfare needs the game files");
        title.setTextColor(0xFF39FF14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(20);

        TextView body = new TextView(this);
        body.setTextColor(Color.WHITE);
        body.setTextSize(14);
        body.setLineSpacing(0f, 1.15f);
        body.setText(fr
            ? "Cette application ne contient pas Call of Duty : Modern Warfare 2. Il te faut les dossiers "
                + "\u00AB zone \u00BB et \u00AB main \u00BB de ta propre copie du jeu PC (2009).\n\n"
                + "1. Sur un PC o\u00F9 le jeu est install\u00E9 (Steam : clic droit sur le jeu > G\u00E9rer > Parcourir "
                + "les fichiers locaux), copie les dossiers \u00AB zone \u00BB et \u00AB main \u00BB sur ton t\u00E9l\u00E9phone.\n\n"
                + "2. Touche OUVRIR LE DOSSIER. Le dossier \u00AB mw2 \u00BB est cr\u00E9\u00E9 s'il n'existe pas.\n\n"
                + "3. Mets \u00AB zone \u00BB et \u00AB main \u00BB dans ce dossier \u00AB mw2 \u00BB. \u00C0 la fin tu dois avoir :\n"
                + "   Android/media/dev.iw4j.iw4l/mw2/zone/english/common_mp.ff\n"
                + "   Android/media/dev.iw4j.iw4l/mw2/main/\n\n"
                + "4. Reviens ici et touche V\u00C9RIFIER. Si les dossiers sont d\u00E9j\u00E0 au bon endroit, "
                + "le jeu d\u00E9marre tout de suite."
            : "This app does not contain Call of Duty: Modern Warfare 2. You need the 'zone' and 'main' "
                + "folders of your own copy of the PC game (2009).\n\n"
                + "1. On a PC with the game installed (Steam: right-click the game > Manage > Browse "
                + "local files), copy the 'zone' and 'main' folders to your phone.\n\n"
                + "2. Tap OPEN THE FOLDER. The 'mw2' folder is created if it is not there yet.\n\n"
                + "3. Put 'zone' and 'main' inside that 'mw2' folder. You should end up with:\n"
                + "   Android/media/dev.iw4j.iw4l/mw2/zone/english/common_mp.ff\n"
                + "   Android/media/dev.iw4j.iw4l/mw2/main/\n\n"
                + "4. Come back here and tap CHECK. If the folders are already in place, the game "
                + "starts right away.");
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(title);
        column.addView(body);
        scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView status = new TextView(this);
        status.setTextColor(0xFF9AE59A);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(11);
        status.setText(setupStatus(fr));
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button(fr ? "OUVRIR LE DOSSIER" : "OPEN THE FOLDER", v -> openGameFolder()));
        buttons.addView(button(fr ? "V\u00C9RIFIER" : "CHECK", v -> {
            if (haveGameFiles()) {
                startGame();
            } else {
                status.setText(setupStatus(fr));
                Toast.makeText(this,
                    fr ? "Fichiers toujours introuvables" : "Files still not found",
                    Toast.LENGTH_SHORT).show();
            }
        }));
        root.addView(buttons);
        setContentView(root);
    }

    /** Whether the folder the app reads the game from holds one. */
    private String gameFiles() {
        StringBuilder out = new StringBuilder();
        boolean found = false;
        File[] media = getExternalMediaDirs();
        File[] roots = new File[] {
            media.length > 0 ? media[0] : null,
            getExternalFilesDir(null),
        };
        for (File root : roots) {
            if (root == null) {
                continue;
            }
            File mw2 = new File(root, "mw2");
            boolean zone = new File(mw2, "zone").isDirectory();
            boolean main = new File(mw2, "main").isDirectory();
            out.append(mw2.getAbsolutePath()).append(": ")
                .append(mw2.isDirectory() ? "folder exists" : "folder missing")
                .append(", zone ").append(zone ? "yes" : "NO")
                .append(", main ").append(main ? "yes" : "NO").append('\n');
            found |= zone && main;
        }
        if (!found) {
            File base = media.length > 0 && media[0] != null ? media[0] : getFilesDir();
            out.append("The MW2 files were NOT found. Copy your Modern Warfare 2 ")
                .append("multiplayer 'zone' and 'main' folders into a folder named 'mw2' at:\n")
                .append(base.getAbsolutePath()).append("/mw2/\n");
        } else {
            out.append("Game files found.\n");
        }
        return out.toString();
    }

    /** The warnings, errors and crash lines this app logged; an app may read its own log. */
    private String systemLog() {
        ArrayDeque<String> lines = new ArrayDeque<>();
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-t", "1500", "-v", "brief")
                .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    boolean keep = line.startsWith("E/") || line.startsWith("F/")
                        || line.contains("panicked") || line.contains("DEBUG")
                        || line.contains("Fatal signal") || line.contains("AndroidRuntime")
                        || line.contains("RustStdoutStderr") || line.contains("Vulkan")
                        || line.contains("wgpu") || line.contains("iw4");
                    if (keep) {
                        lines.addLast(line.length() > 300 ? line.substring(0, 300) : line);
                        if (lines.size() > 80) {
                            lines.removeFirst();
                        }
                    }
                }
            }
        } catch (Exception error) {
            return "(system log unavailable: " + error + ")\n";
        }
        if (lines.isEmpty()) {
            return "(nothing logged)\n";
        }
        return String.join("\n", lines) + "\n";
    }

    private void showReport(final String report) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(pad, pad, pad, pad);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextColor(Color.WHITE);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(11);
        body.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button("Copy", v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Legacy Warfare report", report));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }));
        buttons.addView(button("Share", v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(send, "Share report"));
        }));
        buttons.addView(button("Try again", v -> startGame()));
        root.addView(buttons);
        setContentView(root);
    }

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }
}
