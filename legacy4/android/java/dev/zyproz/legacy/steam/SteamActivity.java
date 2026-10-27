package dev.zyproz.legacy.steam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.QrAuthSession;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Legacy Mobile: gets a game's files from the player's own Steam account. Shared by every Legacy app; the app that
 * starts it says which game through the intent extras below. Nothing is stored: no password, no Steam token.
 *
 * Extras: app_id (int), game_name, target_dir (absolute folder the engine reads), files (regexes, case-insensitive,
 * matched against paths inside the game like "main/iw_00.iwd"), language (default english), mode ("qr" or
 * "account"), check_dirs (sub-folders that must exist when it is done), next_activity (class to start after).
 */
public class SteamActivity extends Activity {

    private TextView status;
    private ImageView qr;
    private ProgressBar bar;
    private LinearLayout form;
    private LinearLayout box;
    private File log;

    private volatile boolean running = true;
    private SteamClient client;
    private SteamUser user;
    private CallbackManager manager;
    private volatile boolean downloading;

    private int appId;
    private String gameName;
    private File target;
    private String[] files;
    private String language;
    private String[] checkDirs;
    private String nextActivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent in = getIntent();
        appId = in.getIntExtra("app_id", 0);
        gameName = orDefault(in.getStringExtra("game_name"), "the game");
        target = new File(orDefault(in.getStringExtra("target_dir"), getFilesDir() + "/game"));
        files = in.getStringArrayExtra("files");
        language = orDefault(in.getStringExtra("language"), "english");
        checkDirs = in.getStringArrayExtra("check_dirs");
        nextActivity = in.getStringExtra("next_activity");
        target.mkdirs();
        log = new File(target.getParentFile(), "steam-log.txt");
        log.delete();

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 30, 40, 30);
        box.setBackgroundColor(Color.BLACK);
        TextView title = text("STEAM - " + gameName.toUpperCase(), 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        status = text("Connecting to Steam...", 14);
        box.addView(status);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        box.addView(bar);
        qr = new ImageView(this);
        qr.setVisibility(View.GONE);
        box.addView(qr, new LinearLayout.LayoutParams(600, 600));
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        box.addView(form);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(box);
        setContentView(scroll);

        String mode = orDefault(in.getStringExtra("mode"), "qr");
        if (mode.equals("account")) {
            askAccount();
        } else {
            start(null, null);
        }
    }

    // ---------- screens ----------

    private void askAccount() {
        status.setText("Sign in with your Steam account. Your password is sent only to Steam and is never saved."
                + " If Steam Guard is on, approve the sign-in in the Steam app.");
        EditText name = new EditText(this);
        name.setHint("Steam account name (login, not nickname)");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(Color.GRAY);
        name.setSingleLine(true);
        EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setTextColor(Color.WHITE);
        pass.setHintTextColor(Color.GRAY);
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button go = new Button(this);
        go.setText("SIGN IN");
        go.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String p = pass.getText().toString();
            if (n.isEmpty() || p.isEmpty()) {
                return;
            }
            form.removeAllViews();
            start(n, p);
        });
        form.addView(name);
        form.addView(pass);
        form.addView(go);
    }

    /** Asks for a Steam Guard code on screen; the future completes when the player sends it. */
    private CompletableFuture<String> askCode(String question) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        runOnUiThread(() -> {
            form.removeAllViews();
            TextView q = text(question, 14);
            EditText code = new EditText(this);
            code.setTextColor(Color.WHITE);
            code.setSingleLine(true);
            Button ok = new Button(this);
            ok.setText("SEND");
            ok.setOnClickListener(v -> {
                String c = code.getText().toString().trim();
                if (!c.isEmpty()) {
                    form.removeAllViews();
                    answer.complete(c);
                }
            });
            form.addView(q);
            form.addView(code);
            form.addView(ok);
        });
        return answer;
    }

    private void showQr(String url) {
        runOnUiThread(() -> {
            try {
                BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600);
                Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
                for (int x = 0; x < m.getWidth(); x++) {
                    for (int y = 0; y < m.getHeight(); y++) {
                        bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                    }
                }
                qr.setImageBitmap(bmp);
                qr.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                note("QR code error: " + e);
            }
            setStatus("Scan this QR code with the Steam app (Steam Guard > scan icon) from another phone or tablet."
                    + " On this phone only: tap OPEN IN STEAM APP, or go back and sign in with your account.");
            form.removeAllViews();
            Button open = new Button(this);
            open.setText("OPEN IN STEAM APP");
            open.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    note("Cannot open the link: " + e);
                }
            });
            form.addView(open);
        });
    }

    // ---------- Steam ----------

    private void start(String account, String password) {
        Thread t = new Thread(() -> {
            try {
                client = new SteamClient();
                manager = new CallbackManager(client);
                user = client.getHandler(SteamUser.class);
                List<java.io.Closeable> subs = new ArrayList<>();
                subs.add(manager.subscribe(ConnectedCallback.class, cb -> onConnected(account, password)));
                subs.add(manager.subscribe(DisconnectedCallback.class, cb -> {
                    if (running && !downloading) {
                        fail("Disconnected from Steam. Check your internet and try again.");
                    }
                }));
                subs.add(manager.subscribe(LoggedOnCallback.class, this::onLoggedOn));
                subs.add(manager.subscribe(LicenseListCallback.class, this::onLicenses));
                client.connect();
                while (running) {
                    manager.runWaitCallbacks(1000L);
                }
                for (java.io.Closeable c : subs) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable e) {
                fail("Steam error: " + e);
                note(stack(e));
            }
        }, "legacy-steam");
        t.setDaemon(true);
        t.start();
    }

    private void onConnected(String account, String password) {
        // Steam answers on this thread; the sign-in waits for the player, so it runs on its own.
        new Thread(() -> {
            try {
                AuthSessionDetails details = new AuthSessionDetails();
                details.deviceFriendlyName = "Legacy Mobile";
                details.persistentSession = false;
                AuthPollResult result;
                if (account == null) {
                    setStatus("Getting a QR code from Steam...");
                    QrAuthSession session = client.getAuthentication().beginAuthSessionViaQR(details).get();
                    session.setChallengeUrlChanged(s -> showQr(session.getChallengeUrl()));
                    showQr(session.getChallengeUrl());
                    result = session.pollingWaitForResult().get();
                } else {
                    setStatus("Signing in as " + account + "...");
                    details.username = account;
                    details.password = password;
                    details.authenticator = new Guard();
                    result = client.getAuthentication().beginAuthSessionViaCredentials(details).get()
                            .pollingWaitForResult().get();
                }
                runOnUiThread(() -> {
                    qr.setVisibility(View.GONE);
                    form.removeAllViews();
                });
                setStatus("Signed in as " + result.getAccountName() + ". Checking your games...");
                LogOnDetails logOn = new LogOnDetails();
                logOn.setUsername(result.getAccountName());
                logOn.setAccessToken(result.getRefreshToken());
                logOn.setLoginID(149);
                user.logOn(logOn);
            } catch (Throwable e) {
                note(stack(e));
                fail("Sign-in failed: " + rootMessage(e));
            }
        }, "legacy-steam-auth").start();
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            fail("Steam refused the sign-in: " + cb.getResult());
        }
    }

    private void onLicenses(LicenseListCallback cb) {
        if (downloading) {
            return;
        }
        if (cb.getResult() != EResult.OK) {
            fail("Steam did not send your game list: " + cb.getResult());
            return;
        }
        downloading = true;
        List<License> licenses = cb.getLicenseList();
        new Thread(() -> download(licenses), "legacy-steam-download").start();
    }

    private void download(List<License> licenses) {
        setStatus("Downloading " + gameName + " (only the files the game needs)...");
        runOnUiThread(() -> bar.setVisibility(View.VISIBLE));
        final String[] failure = {null};
        try (DepotDownloader dd = new DepotDownloader(client, licenses, false, false, 8, 4, 2)) {
            boolean filtered = onlyFiles(dd);
            if (!filtered) {
                note("File filter unavailable: the whole game is downloaded.");
            }
            dd.addListener(new IDownloadListener() {
                @Override
                public void onItemAdded(DownloadItem item) {
                }

                @Override
                public void onDownloadStarted(DownloadItem item) {
                }

                @Override
                public void onDownloadCompleted(DownloadItem item) {
                }

                @Override
                public void onDownloadFailed(DownloadItem item, Throwable error) {
                    failure[0] = rootMessage(error);
                    note(stack(error));
                }

                @Override
                public void onStatusUpdate(String message) {
                    note(message);
                }

                @Override
                public void onFileCompleted(int depotId, String fileName, float done) {
                    progress(done, fileName);
                }

                @Override
                public void onChunkCompleted(int depotId, float done, long compressed, long uncompressed) {
                    progress(done, null);
                }

                @Override
                public void onDepotCompleted(int depotId, long compressed, long uncompressed) {
                    note("Depot " + depotId + " done: " + (uncompressed / 1048576) + " MB");
                }
            });
            dd.add(new AppItem(appId, false, target.getAbsolutePath(), "public", "", false, "windows", false,
                    null, false, language, false, new ArrayList<>(), new ArrayList<>(), false, false));
            dd.finishAdding();
            dd.awaitCompletion();
        } catch (Throwable e) {
            failure[0] = rootMessage(e);
            note(stack(e));
        }
        running = false;
        try {
            user.logOff();
            client.disconnect();
        } catch (Throwable ignored) {
        }
        boolean ok = true;
        if (checkDirs != null) {
            for (String d : checkDirs) {
                if (!dirIn(target, d)) {
                    ok = false;
                }
            }
        }
        if (ok && failure[0] == null) {
            setStatus("Done! " + gameName + " is installed.");
            runOnUiThread(() -> {
                if (nextActivity != null) {
                    try {
                        startActivity(new Intent(this, Class.forName(nextActivity)));
                    } catch (Exception e) {
                        note("Cannot start the game: " + e);
                    }
                }
                finish();
            });
        } else {
            String why = failure[0] != null ? failure[0] : "some game folders are missing";
            fail("Download did not finish: " + why
                    + "\nIf this account does not own " + gameName + " on Steam, it cannot be downloaded.");
        }
    }

    /** Main/zone only: JavaSteam 1.8 has the file filter but does not expose it yet, so it is set directly. */
    private boolean onlyFiles(DepotDownloader dd) {
        if (files == null || files.length == 0) {
            return true;
        }
        try {
            Field cf = DepotDownloader.class.getDeclaredField("config");
            cf.setAccessible(true);
            Object config = cf.get(dd);
            Field using = config.getClass().getDeclaredField("usingFileList");
            using.setAccessible(true);
            using.setBoolean(config, true);
            List<kotlin.text.Regex> regexes = new ArrayList<>();
            for (String f : files) {
                regexes.add(new kotlin.text.Regex("(?i)" + f));
            }
            Field rf = config.getClass().getDeclaredField("filesToDownloadRegex");
            rf.setAccessible(true);
            rf.set(config, regexes);
            return true;
        } catch (Throwable e) {
            note(stack(e));
            return false;
        }
    }

    private final class Guard implements IAuthenticator {
        @Override
        public CompletableFuture<String> getDeviceCode(boolean wrong) {
            return askCode((wrong ? "Wrong code. " : "") + "Enter the Steam Guard code from your Steam app:");
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean wrong) {
            return askCode((wrong ? "Wrong code. " : "") + "Enter the Steam Guard code sent to " + email + ":");
        }

        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            setStatus("Open the Steam app and approve this sign-in (Legacy Mobile).");
            return CompletableFuture.completedFuture(true);
        }
    }

    // ---------- helpers ----------

    private long lastUi;

    private void progress(float done, String file) {
        long now = System.currentTimeMillis();
        if (now - lastUi < 300 && file == null) {
            return;
        }
        lastUi = now;
        runOnUiThread(() -> {
            bar.setProgress((int) (done * 1000));
            status.setText(String.format(java.util.Locale.US, "Downloading %s: %.1f %%%s", gameName, done * 100,
                    file != null ? "\n" + file : ""));
        });
    }

    private void fail(String message) {
        running = false;
        note("FAILED: " + message);
        runOnUiThread(() -> {
            status.setText(message + "\n\nDetails: " + log.getAbsolutePath());
            qr.setVisibility(View.GONE);
            form.removeAllViews();
            Button back = new Button(this);
            back.setText("BACK");
            back.setOnClickListener(v -> {
                if (nextActivity != null) {
                    try {
                        startActivity(new Intent(this, Class.forName(nextActivity)));
                    } catch (Exception ignored) {
                    }
                }
                finish();
            });
            form.addView(back);
        });
    }

    private void setStatus(String s) {
        note(s);
        runOnUiThread(() -> status.setText(s));
    }

    private synchronized void note(String s) {
        try (FileWriter w = new FileWriter(log, true)) {
            w.write(s + "\n");
        } catch (Exception ignored) {
        }
    }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setGravity(Gravity.START);
        t.setPadding(0, 10, 0, 10);
        return t;
    }

    private static boolean dirIn(File parent, String name) {
        File[] list = parent.listFiles();
        if (list == null) {
            return false;
        }
        for (File f : list) {
            if (f.isDirectory() && f.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isEmpty() ? d : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private static String stack(Throwable e) {
        StringWriter s = new StringWriter();
        e.printStackTrace(new PrintWriter(s));
        return s.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!downloading) {
            running = false;
            try {
                if (client != null) {
                    client.disconnect();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
