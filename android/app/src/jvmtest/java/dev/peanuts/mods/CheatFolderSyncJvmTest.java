package dev.peanuts.mods;   // PEANUTS: the linked cheats folder (Eden Cheats Manager integration)

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The linked cheats folder on the JVM, driven by peanuts/tests/test_cheat_link.py. args[0]: an empty folder. Prints "ok" or throws. Covers: a set Eden Cheats
 * Manager installs becomes a source, an update changes its files and keeps its on / off, another game's title ID and hand imports are left alone, a removed set
 * removes its source, an unreadable folder changes nothing, and the merge sees the linked cheats.
 */
public final class CheatFolderSyncJvmTest {
    static final String TID = "0100AAAA0BBB0000", UPDATE_TID = "0100AAAA0BBB0800", OTHER_TID = "0100CCCC0DDD0000";
    static final String B = "0123456789ABCDEF", B2 = "1111222233334444";

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static final String FILE_A = "[Max Money]\n04000000 00001000 0098967F\n";
    static final String FILE_A2 = "[Max Money]\n04000000 00001000 05F5E0FF\n[Max HP]\n04000000 00002000 000003E7\n";
    static final String FILE_B = "[Moon Jump]\n04000000 00003000 00000001\n";

    /** What Eden Cheats Manager's install_cheat writes: &lt;load&gt;/&lt;TID&gt;/&lt;name&gt;/cheats/&lt;BID&gt;.txt */
    static void edcInstall(File load, String tid, String name, String bid, String text) throws Exception {
        File f = new File(load, tid + "/" + name + "/cheats/" + bid + ".txt");
        check(f.getParentFile().isDirectory() || f.getParentFile().mkdirs(), "mkdirs " + f);
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static void edcDelete(File load, String tid, String name, String bid) {
        File f = new File(load, tid + "/" + name + "/cheats/" + bid + ".txt");
        check(f.delete(), "delete " + f);
        f.getParentFile().delete();
        f.getParentFile().getParentFile().delete();
    }

    static CheatSources.Source byName(File folder, String name) {
        for (CheatSources.Source s : CheatSources.list(folder)) if (s.name.equals(name)) return s;
        return null;
    }

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]), load = new File(root, "PeanutsNative/load"), folder = new File(root, "files/cheats/" + TID);
        check(load.mkdirs() && folder.mkdirs(), "setup");

        // 0. A folder that is not there changes nothing and says so
        CheatFolderSync.Report r = CheatFolderSync.sync(new File(root, "missing"), folder, TID, 1);
        check(r.unavailable != null && !r.changed(), "missing folder: " + r);

        // 1. A hand import is there before the link
        CheatSources.add(folder, "My file", CheatSources.FROM_FILE, java.util.Map.of(B, FILE_B), 10);

        // 2. EDC installs two sets for the game (one under the update's title ID), and one for another game
        edcInstall(load, TID, "Max Money (Custom)", B, FILE_A);
        edcInstall(load, UPDATE_TID, "Moon", B2, FILE_B);
        edcInstall(load, OTHER_TID, "Not this game", B, FILE_A);
        r = CheatFolderSync.sync(load, folder, TID, 100);
        check(r.unavailable == null && r.added.size() == 2 && r.removed.isEmpty(), "first sync: " + r);
        CheatSources.Source money = byName(folder, "Max Money (Custom)");
        check(money != null && CheatFolderSync.FROM_LINK.equals(money.from) && money.enabled, "linked source made, on: " + CheatSources.list(folder));
        check(FILE_A.equals(money.text(B)), "its file");
        check(byName(folder, "Not this game") == null, "another game's set is left out");
        check(byName(folder, "My file") != null, "the hand import stays");

        // 3. Nothing changed: a second sync does nothing
        r = CheatFolderSync.sync(load, folder, TID, 200);
        check(!r.changed(), "idempotent: " + r);

        // 4. The player switches the linked source off; EDC replaces the file; the source keeps off and its name
        CheatSources.enabled(folder, money.id, false);
        edcInstall(load, TID, "Max Money (Custom)", B, FILE_A2);
        r = CheatFolderSync.sync(load, folder, TID, 300);
        check(r.updated.equals(List.of("Max Money (Custom)")), "update: " + r);
        money = byName(folder, "Max Money (Custom)");
        check(money != null && !money.enabled && FILE_A2.equals(money.text(B)), "updated, still off");

        // 5. The merge sees the linked cheats once the source is on again
        CheatSources.enabled(folder, money.id, true);
        CheatSources.Merged m = CheatSources.merge(CheatSources.blocks(folder, null, false, B, -1));
        check(m.keys().contains("Max HP") && m.keys().contains("Max Money") && m.keys().contains("Moon Jump"), "merged keys: " + m.keys());

        // 6. EDC deletes a set: its source goes; the hand import stays
        edcDelete(load, UPDATE_TID, "Moon", B2);
        r = CheatFolderSync.sync(load, folder, TID, 400);
        check(r.removed.equals(List.of("Moon")) && byName(folder, "Moon") == null, "removed: " + r);
        check(byName(folder, "My file") != null, "the hand import still stays");

        // 7. A bad file is skipped, not taken, and does not remove the set's good file
        File bad = new File(load, TID + "/Max Money (Custom)/cheats/" + B2 + ".txt");
        Files.write(bad.toPath(), "just prose, no cheats".getBytes(StandardCharsets.UTF_8));
        r = CheatFolderSync.sync(load, folder, TID, 500);
        check(!r.skipped.isEmpty() && !r.changed(), "bad file skipped: " + r);

        // 7b. Every file of a set refused this time (one half-written): its source is kept as it was
        File good = new File(load, TID + "/Max Money (Custom)/cheats/" + B + ".txt");
        Files.write(good.toPath(), new byte[] {0x5B, 0x00});
        r = CheatFolderSync.sync(load, folder, TID, 550);
        check(!r.changed() && FILE_A2.equals(byName(folder, "Max Money (Custom)").text(B)), "half-written set kept: " + r);
        Files.write(good.toPath(), FILE_A2.getBytes(StandardCharsets.UTF_8));

        // 8. The whole load folder gone (an SD card out): nothing removed
        File moved = new File(root, "away");
        check(load.renameTo(moved), "move load");
        r = CheatFolderSync.sync(load, folder, TID, 600);
        check(r.unavailable != null && byName(folder, "Max Money (Custom)") != null, "unmounted: kept: " + r);

        System.out.println("ok");
    }
}
