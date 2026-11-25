package dev.peanuts.mods;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.nxnative.android.CheatsBridge;
import dev.peanuts.PeanutsPrefs;

/**
 * The linked cheats folder on Android (HANDOFF: Eden Cheats Manager integration): Settings › Library › Linked cheats folder names a folder in Eden's load layout
 * that Eden Cheats Manager writes through the system's folder access; {@link #sync} keeps a game's cheat sources in step with it ({@link CheatFolderSync}), and
 * {@link #writeLibrary} tells that app which games and builds this one has (peanuts-library.json and one &lt;title ID&gt;/ folder a game).
 * <p>Both are quiet: no folder set or no All files access returns at once, and nothing here ever throws to the caller. A start or the Mods &amp; Cheats page must
 * work the same whatever the folder holds.
 */
public final class CheatLink {
    private CheatLink() {}

    /** The manifest's name in the load folder and the format it is written in (a reader refuses a higher one) */
    public static final String MANIFEST = "peanuts-library.json";
    public static final int FORMAT = 1;
    private static final String TAG = "peanuts";
    private static String lastNote;   // the last line logged, so a page drawn again and again logs a lasting state once

    /** The folder Settings offers first: Internal storage/PeanutsNative/load (its name must be load: Eden Cheats Manager takes only a folder of that name) */
    public static File defaultDir() { return new File(new File(Environment.getExternalStorageDirectory(), "PeanutsNative"), "load"); }

    /** The linked folder, null when none is set or the app cannot read shared storage by path */
    public static File dir(Context c) {
        String p = PeanutsPrefs.cheatLinkDir(c);
        if (p == null || p.isEmpty()) return null;
        try { if (!Environment.isExternalStorageManager()) return null; } catch (RuntimeException e) { return null; }
        return new File(p);
    }

    /** CheatsBridge.wanted (PEANUTS hook): the game's linked sets made sources before a start or the page reads them. One log line when something changed */
    public static synchronized void sync(Context c, String titleId) {
        try {
            File load = dir(c);
            if (load == null) return;
            CheatFolderSync.Report r = CheatFolderSync.sync(load, CheatsBridge.folder(c, titleId), titleId == null ? null : titleId.toUpperCase(Locale.ROOT), System.currentTimeMillis());
            if (r.changed() || !r.skipped.isEmpty() || r.unavailable != null) note("cheats: " + titleId + ": " + r);
            else lastNote = null;
        } catch (IOException | RuntimeException e) {
            note("cheats: " + titleId + ": linked cheats folder not read: " + e);
        }
    }

    private static void note(String line) {
        if (line.equals(lastNote)) return;
        lastNote = line;
        Log.i(TAG, line);
    }

    /** A game of the library, as the manifest lists it */
    public static final class Game {
        public final String titleId, name, packDir;
        public final boolean ready;
        public Game(String titleId, String name, String packDir, boolean ready) { this.titleId = titleId; this.name = name; this.packDir = packDir; this.ready = ready; }
    }

    /**
     * The library written into the linked folder: an empty &lt;title ID&gt;/ folder per game (Eden Cheats Manager lists a game by that folder alone) and
     * peanuts-library.json with each game's main builds and their versions (HANDOFF §5.2). Written through a temporary file and a rename, so a reader never sees half
     * of it. Call it off the main thread: it touches shared storage
     */
    public static synchronized void writeLibrary(Context c, List<Game> games) {
        try {
            File load = dir(c);
            if (load == null) return;
            if (!load.isDirectory() && !load.mkdirs()) { note("cheats: linked cheats folder cannot be made: " + load); return; }
            JSONArray list = new JSONArray();
            for (Game g : games) {
                if (!PeanutsPrefs.validTitleId(g.titleId)) continue;
                String tid = g.titleId.toUpperCase(Locale.ROOT);
                File folder = new File(load, tid);
                if (!folder.isDirectory() && !folder.mkdirs()) continue;
                JSONArray builds = new JSONArray();
                Set<String> bids = CheatsBridge.builds(tid, g.packDir);
                for (String bid : bids) {
                    JSONObject b = new JSONObject().put("build_id", bid);
                    String v = CheatsBridge.version(tid, bid);
                    if (v != null) b.put("version", v);
                    builds.put(b);
                }
                list.put(new JSONObject().put("title_id", tid).put("name", g.name == null || g.name.isEmpty() ? tid : g.name).put("ready", g.ready).put("builds", builds));
            }
            JSONObject m = new JSONObject().put("format", FORMAT).put("app_version", appVersion(c)).put("package", c.getPackageName())
                    .put("written", System.currentTimeMillis()).put("games", list);
            File tmp = new File(load, "." + MANIFEST + ".tmp"), out = new File(load, MANIFEST);
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(m.toString(2).getBytes(StandardCharsets.UTF_8));
                os.getFD().sync();
            }
            if (!tmp.renameTo(out)) {
                if (!out.delete() || !tmp.renameTo(out)) { tmp.delete(); throw new IOException("cannot replace " + out); }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            note("cheats: linked cheats folder: the library list was not written: " + e);
        }
    }

    private static String appVersion(Context c) {
        try { String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; return v == null ? "" : v; }
        catch (Exception e) { return ""; }
    }
}
