package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A linked cheats folder (HANDOFF: Eden Cheats Manager integration): a folder on shared storage in Eden's load layout,
 * &lt;load&gt;/&lt;TID&gt;/&lt;set&gt;/cheats/&lt;BID&gt;.txt, that another app (Eden Cheats Manager) writes, kept in step with the game's cheat sources
 * ({@link CheatSources}). No Android classes: a JVM test runs it.
 * <ul>
 * <li>Each &lt;set&gt; folder of the game's title IDs (the base and its update / DLC IDs, {@link CheatImport#sameGame}) is one source, from {@link #FROM_LINK},
 *     with the set's folder name kept as {@link #LINK_KEY} in its source.tsv. Its files are every build's &lt;BID&gt;.txt in the set (16 or 32 hex digits).</li>
 * <li>A set that is new becomes a source (on). A set whose files changed gets the same files (its on / off and its name stay). A linked source whose set is gone
 *     is removed. Sources the player imported by hand are never touched.</li>
 * <li>A folder that cannot be read (not mounted, permission gone) changes nothing: an absent card must never empty the game's cheats. A set whose files are all
 *     refused this time (one being written) keeps its source as it is.</li>
 * </ul>
 * The player's toggles are kept by key in the launcher (Prefs.cheatsOn); a linked source keeps its name across syncs, so its keys stay the same.
 */
public final class CheatFolderSync {
    private CheatFolderSync() {}

    public static final String FROM_LINK = "link", LINK_KEY = "link";
    static final int MAX_SETS = 64;

    private static final Pattern HEX16 = Pattern.compile("(?i)[0-9a-f]{16}");
    private static final Pattern BID_FILE = Pattern.compile("(?i)([0-9a-f]{16})(?:[0-9a-f]{16})?\\.txt");

    /** What a sync did, for the log and the page's note */
    public static final class Report {
        public final List<String> added = new ArrayList<>(), updated = new ArrayList<>(), removed = new ArrayList<>(), skipped = new ArrayList<>();
        public String unavailable;   // why nothing was looked at; null when the folder was read
        public boolean changed() { return !added.isEmpty() || !updated.isEmpty() || !removed.isEmpty(); }
        @Override public String toString() {
            if (unavailable != null) return "linked cheats folder unavailable: " + unavailable;
            return "linked cheats: +" + added + " ~" + updated + " -" + removed + (skipped.isEmpty() ? "" : " skipped " + skipped);
        }
    }

    /** The sets of a game in a load folder: set name → BID (16 hex, upper case) → text. Null when the folder cannot be read */
    static Map<String, Map<String, String>> scan(File load, String titleId, List<String> skipped) {
        if (load == null || !load.isDirectory()) return null;
        File[] tids = load.listFiles(f -> f.isDirectory() && HEX16.matcher(f.getName()).matches() && CheatImport.sameGame(f.getName(), titleId));
        if (tids == null) return null;   // listFiles fails: no permission
        java.util.Arrays.sort(tids, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        Map<String, Map<String, String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (File tid : tids) {
            File[] sets = tid.listFiles(File::isDirectory);
            if (sets == null) return null;
            java.util.Arrays.sort(sets, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            for (File set : sets) {
                if (set.getName().startsWith(".")) continue;
                File cheats = new File(set, "cheats");
                File[] files = cheats.listFiles(f -> f.isFile() && BID_FILE.matcher(f.getName()).matches());
                if (files == null || files.length == 0) continue;   // a mod's folder (romfs / exefs) without cheats: not ours
                if (out.size() >= MAX_SETS && !out.containsKey(set.getName())) { skipped.add(set.getName() + ": too many sets"); continue; }
                java.util.Arrays.sort(files, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                Map<String, String> byBuild = out.computeIfAbsent(set.getName(), k -> new TreeMap<>());
                for (File f : files) {
                    String why = check(f);
                    if (why != null) { skipped.add(set.getName() + "/" + f.getName() + ": " + why); continue; }
                    Matcher m = BID_FILE.matcher(f.getName());
                    if (!m.matches()) continue;
                    try {
                        byBuild.putIfAbsent(m.group(1).toUpperCase(Locale.ROOT), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n"));
                    } catch (IOException e) { return null; }   // a file listed but not readable: the folder is not fully there, change nothing
                }
                // A set whose every file was skipped stays as an empty map: its source is kept as it is (a file half-written by the other app must not remove it)
            }
        }
        return out;
    }

    /** Why a file is not taken (CheatImport's rules for a pick), null when it is */
    static String check(File f) {
        if (f.length() > CheatImport.MAX_FILE) return "larger than " + CheatImport.MAX_FILE / 1024 + " KB";
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) return "not text";
            if (!CheatFile.parse(text, -1).any()) return "no cheat in it";
        } catch (IOException e) { return "cannot be read"; }
        return null;
    }

    /** The linked sources of the game: set name (case aside) → source */
    static Map<String, CheatSources.Source> linked(File folder) {
        Map<String, CheatSources.Source> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CheatSources.Source s : CheatSources.list(folder)) {
            if (!FROM_LINK.equals(s.from)) continue;
            String link = CheatSources.readState(new File(s.dir, CheatSources.STATE)).get(LINK_KEY);
            if (link != null && !link.isEmpty()) out.put(link, s);
        }
        return out;
    }

    /**
     * Keep the game's linked sources in step with the load folder. folder: the game's cheats folder (&lt;files&gt;/cheats/&lt;title ID&gt;); titleId: the game's;
     * now: the time new sources are added at (their order in the merge)
     */
    public static Report sync(File load, File folder, String titleId, long now) throws IOException {
        Report r = new Report();
        if (titleId == null || !HEX16.matcher(titleId).matches()) { r.unavailable = "no title ID"; return r; }
        Map<String, Map<String, String>> sets = scan(load, titleId, r.skipped);
        if (sets == null) { r.unavailable = load == null ? "not set" : "cannot read " + load; return r; }
        Map<String, CheatSources.Source> have = linked(folder);
        int k = 0;
        for (Map.Entry<String, Map<String, String>> e : sets.entrySet()) {
            CheatSources.Source s = have.remove(e.getKey());
            if (e.getValue().isEmpty()) continue;   // nothing readable this time: an existing source is kept, no new one is made
            if (s == null) {
                CheatSources.Source made = CheatSources.add(folder, e.getKey(), FROM_LINK, e.getValue(), now + k++);
                Map<String, String> st = CheatSources.readState(new File(made.dir, CheatSources.STATE));
                st.put(LINK_KEY, e.getKey());
                CheatSources.writeState(new File(made.dir, CheatSources.STATE), st);
                r.added.add(e.getKey());
            } else if (replaceFiles(s, e.getValue())) r.updated.add(e.getKey());
        }
        for (Map.Entry<String, CheatSources.Source> gone : have.entrySet()) {
            if (CheatSources.remove(folder, gone.getValue().id)) r.removed.add(gone.getKey());
        }
        return r;
    }

    /** A linked source's files made the set's (BID → text): changed files written, files of builds the set no longer has removed. True when anything changed */
    static boolean replaceFiles(CheatSources.Source s, Map<String, String> want) throws IOException {
        boolean changed = false;
        Map<String, File> have = s.files();
        for (Map.Entry<String, File> e : have.entrySet()) {
            if (want.containsKey(e.getKey())) continue;
            if (!e.getValue().delete()) throw new IOException("cannot remove " + e.getValue());
            changed = true;
        }
        for (Map.Entry<String, String> e : want.entrySet()) {
            File f = have.get(e.getKey());
            String now = f == null ? null : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (e.getValue().equals(now)) continue;
            if (f != null && !f.getName().equals(e.getKey() + ".txt") && !f.delete()) throw new IOException("cannot remove " + f);
            CheatSources.write(new File(s.dir, e.getKey() + ".txt"), e.getValue());
            changed = true;
        }
        return changed;
    }

    /** The sets a load folder holds for a game, for a page that shows the link (set name → its builds), empty when it cannot be read */
    public static Map<String, List<String>> preview(File load, String titleId) {
        Map<String, Map<String, String>> sets = scan(load, titleId, new ArrayList<>());
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (sets != null) for (Map.Entry<String, Map<String, String>> e : sets.entrySet()) if (!e.getValue().isEmpty()) out.put(e.getKey(), new ArrayList<>(e.getValue().keySet()));
        return out;
    }
}
