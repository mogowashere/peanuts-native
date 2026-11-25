"""The linked cheats folder (Eden Cheats Manager integration: dev.peanuts.mods.CheatFolderSync): the JVM part (android/app/src/jvmtest's
CheatFolderSyncJvmTest; skipped without a JDK) installs, updates and deletes sets the way Eden Cheats Manager writes them and checks the game's cheat sources
follow. Then the hook: CheatsBridge.wanted syncs before a start or the Mods & Cheats page reads the sources."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
MODS = APP / 'main/java/dev/peanuts/mods'
TEST = APP / 'jvmtest/java/dev/peanuts/mods/CheatFolderSyncJvmTest.java'
CORE = [MODS / 'CheatFile.java', MODS / 'CheatImport.java', MODS / 'CheatSources.java', MODS / 'CheatStatus.java', MODS / 'CheatValues.java',
        MODS / 'CheatFolderSync.java']


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'needs a JDK')
class Jvm(unittest.TestCase):
    def test_cheat_link(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, CORE), str(TEST)],
                           check=True, capture_output=True, text=True)
            (d / 'work').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.mods.CheatFolderSyncJvmTest', str(d / 'work')],
                               capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


class Hooks(unittest.TestCase):
    """These fail until the hooks exist (HANDOFF.md, Peanuts P3): they are the targets."""

    def test_wanted_syncs_before_it_decides(self):
        """CheatsBridge.wanted (a Peanuts file) runs at a start (Prefs.cheats, before GameActivity.cheatsEnv decides) and when Mods & Cheats opens
        (CheatsPage.Model): one call there covers both, and no upstream file changes."""
        bridge = (APP / 'main/java/dev/nxnative/android/CheatsBridge.java').read_text(encoding='utf-8')
        body = bridge[bridge.find('public static boolean wanted('):]
        i, j = body.find('CheatLink.sync('), body.find('CheatSources.appEnabled(folder))')
        self.assertTrue(0 <= i < j, 'CheatsBridge.wanted calls dev.peanuts.mods.CheatLink.sync before it reads the sources')

    def test_linked_sources_have_words(self):
        page = (MODS / 'CheatsPage.java').read_text(encoding='utf-8')
        self.assertTrue('CheatFolderSync.FROM_LINK' in page, 'CheatsPage.fromWords names a linked source')

if __name__ == '__main__':
    unittest.main()
