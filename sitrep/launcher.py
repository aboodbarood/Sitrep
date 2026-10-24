"""
Standalone launcher for Sitrep.

Starts the local server (sitrep.py) on a background thread, waits for it to
accept connections, then pops Firefox pointed at it: sized to fill the screen
by default, with the tab bar/address bar/bookmarks bar hidden via a
userChrome.css tweak in a dedicated profile, but still a normal OS window —
real title bar, real resizable/draggable edges, closable via the titlebar X
or Alt+F4. Built into a single .exe via PyInstaller so it can be launched
without a visible console or a separate "start the server" step.

Closing the Firefox window exits this process (and the server with it).
"""
import csv
import ctypes
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
from ctypes import wintypes

# PyInstaller's frozen import machinery doesn't resolve codecs.lookup()'s
# lazy `encodings.<name>` imports, even when the module is bundled (a known
# PyInstaller gotcha). socket.getfqdn(), called by HTTPServer.server_bind(),
# needs the 'idna' codec. Importing it eagerly here puts it in sys.modules
# before that lazy lookup ever runs.
import encodings.idna

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sitrep

# subprocess.CREATE_NO_WINDOW isn't defined until Python 3.7; this machine
# runs 3.6, where referencing it raises AttributeError — silently swallowed
# by the broad excepts below, which then report "still open"/"still alive"
# forever and never let the app shut down. Use the raw Win32 value instead.
CREATE_NO_WINDOW = 0x08000000

FIREFOX_CANDIDATES = [
    r"C:\Program Files\Mozilla Firefox\firefox.exe",
    r"C:\Program Files (x86)\Mozilla Firefox\firefox.exe",
]


def find_firefox():
    for path in FIREFOX_CANDIDATES:
        if os.path.isfile(path):
            return path
    return None


def wait_for_server(host, port, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return True
        except OSError:
            time.sleep(0.2)
    return False


def get_screen_size():
    user32 = ctypes.windll.user32
    return user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)


def _find_hwnd_for_pid(pid):
    """Top-level, visible window handle owned by the given PID, if any."""
    user32 = ctypes.windll.user32
    result = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
    def callback(hwnd, _lparam):
        owner_pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner_pid))
        if owner_pid.value == pid and user32.IsWindowVisible(hwnd):
            result.append(hwnd)
            return False
        return True

    user32.EnumWindows(callback, 0)
    return result[0] if result else None


def _pin_to_primary_monitor(pid, w, h):
    """Force our just-launched window to (0,0) on the primary monitor.

    -width/-height alone weren't enough: Firefox remembers a window's
    position per profile across runs independently of the size we pass on
    the command line, and once this dedicated profile's window had ever been
    on a secondary monitor, every later launch quietly reopened it there —
    confirmed live via GetWindowRect on a stuck instance, which showed a
    window sized exactly as requested (3440x1440) but positioned starting at
    x=3440, i.e. entirely on the second of two monitors. Since nothing was
    visibly wrong on the primary display, this looked exactly like "the app
    doesn't start". Repositioning (and resizing, redundantly but harmlessly)
    to (0,0) with our own w/h on every launch makes it impossible for this to
    silently recur, at the cost of no longer remembering a manually-moved
    position between runs — worth it for guaranteed visibility.
    """
    hwnd = None
    deadline = time.time() + 4
    while time.time() < deadline and not hwnd:
        hwnd = _find_hwnd_for_pid(pid)
        if not hwnd:
            time.sleep(0.3)
    if hwnd:
        SWP_NOZORDER, SWP_NOACTIVATE = 0x0004, 0x0010
        ctypes.windll.user32.SetWindowPos(hwnd, None, 0, 0, w, h, SWP_NOZORDER | SWP_NOACTIVATE)


def prepare_profile(profile_dir):
    """Write prefs/CSS into our dedicated profile to hide Firefox's own UI.

    Keeps a real window (title bar, resizable, closable) instead of -kiosk's
    exclusive fullscreen — the owner needs the ability to resize and to close
    the app via a normal titlebar/Alt+F4, which true kiosk mode has no chrome
    for at all. userChrome.css needs the legacy-stylesheets pref enabled
    (default off since Firefox 69) to be loaded at all.
    """
    chrome_dir = os.path.join(profile_dir, "chrome")
    os.makedirs(chrome_dir, exist_ok=True)

    with open(os.path.join(profile_dir, "user.js"), "w") as f:
        f.write(
            'user_pref("toolkit.legacyUserProfileCustomizations.stylesheets", true);\n'
            # Firefox normally draws the tab strip *inside* the OS titlebar
            # (merging them, Windows default) — that merged strip is also the
            # window's only drag handle. Hiding #TabsToolbar via userChrome.css
            # below removed the drag handle along with it, making the window
            # immovable. Turning this off gives back a separate, real OS
            # titlebar above the (still-hidden) tab strip, so it's draggable
            # again.
            'user_pref("browser.tabs.drawInTitlebar", false);\n'
            'user_pref("browser.shell.checkDefaultBrowser", false);\n'
            'user_pref("browser.aboutwelcome.enabled", false);\n'
            'user_pref("startup.homepage_welcome_url", "");\n'
            'user_pref("startup.homepage_welcome_url.additional", "");\n'
            'user_pref("browser.tabs.warnOnClose", false);\n'
            'user_pref("browser.warnOnQuit", false);\n'
            'user_pref("browser.sessionstore.resume_from_crash", false);\n'
        )

    with open(os.path.join(chrome_dir, "userChrome.css"), "w") as f:
        f.write(
            "#TabsToolbar, #nav-bar, #PersonalToolbar {\n"
            "  visibility: collapse !important;\n"
            "  display: none !important;\n"
            "}\n"
        )


def _sitrep_titled_pids():
    """PIDs of every firefox.exe window currently titled to show our page.

    Title alone is NOT enough to identify *our* window: if the owner has
    separately navigated their own everyday Firefox to this same page (very
    plausible for a dashboard they check on), that window's title also
    contains "Sitrep" and is indistinguishable from ours by title text alone.
    Confirmed live during testing — a pre-existing, unrelated window (owner's
    real browsing session, dozens of tabs) had "Sitrep — Mozilla Firefox" as
    its title simply because that was its active tab. Matching on title text
    is only safe as a *before/after* diff (see main()), never on its own.

    CREATE_NO_WINDOW matters here: without it, every call flashes a console
    window on screen, since tasklist.exe is a console subprocess and Windows
    gives spawned console subprocesses their own console by default even
    though this launcher itself is built with --noconsole.
    """
    try:
        out = subprocess.run(
            ["tasklist", "/V", "/FO", "CSV", "/FI", "IMAGENAME eq firefox.exe", "/NH"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True,
            timeout=5, creationflags=CREATE_NO_WINDOW,
        ).stdout
    except Exception:
        return set()
    pids = set()
    for line in out.splitlines():
        if "sitrep" in line.lower():
            try:
                pids.add(next(csv.reader([line]))[1])
            except Exception:
                pass
    return pids


def _pid_alive(pid):
    try:
        out = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True,
            timeout=5, creationflags=CREATE_NO_WINDOW,
        ).stdout
    except Exception:
        return True  # can't tell -> assume still alive rather than killing the server
    return pid in out


def main():
    t = threading.Thread(target=sitrep.run_server, daemon=True)
    t.start()

    if not wait_for_server("127.0.0.1", sitrep.PORT):
        print("Sitrep server did not come up in time.")
        sys.exit(1)

    firefox = find_firefox()
    url = f"http://localhost:{sitrep.PORT}"
    if not firefox:
        print(f"Firefox not found in the usual install locations — open {url} manually.")
        # Keep the server alive so the user can still reach it from any browser.
        t.join()
        return

    # A dedicated profile directory, separate from the owner's real Firefox
    # profile. Tested and found necessary: "-no-remote" alone was NOT enough
    # to stop Firefox from remoting into an already-running window — it took
    # over the owner's actual browser window (mid-session, with real tabs
    # open) and repainted it as this app's view instead of opening a new one.
    # Pointing -profile at a directory Firefox has never used keys the
    # single-instance lock to that profile instead of the default one, which
    # forces a genuinely separate process no matter what else is running.
    profile_dir = os.path.join(
        os.environ.get("LOCALAPPDATA", tempfile.gettempdir()), "Sitrep", "ffprofile"
    )
    prepare_profile(profile_dir)

    # Sized to fill the screen by default (a normal window, not exclusive
    # fullscreen) — the owner can still resize/move it by dragging its edges,
    # and close it via the titlebar X or Alt+F4. Firefox remembers window
    # size/position per profile after the first manual resize, so this only
    # matters on the very first run.
    w, h = get_screen_size()

    # Snapshot Sitrep-titled windows that exist *before* we spawn ours, so the
    # one that appears afterward can be identified by diff instead of by
    # title text alone (title text isn't unique enough — see
    # _sitrep_titled_pids's docstring).
    before = _sitrep_titled_pids()
    subprocess.Popen([
        firefox, "-profile", profile_dir, "-no-remote",
        "-width", str(w), "-height", str(h), url,
    ])

    new_pids = set()
    deadline = time.time() + 10
    while time.time() < deadline:
        time.sleep(1)
        new_pids = _sitrep_titled_pids() - before
        if new_pids:
            break

    our_pid = next(iter(new_pids)) if len(new_pids) == 1 else None
    # Tell sitrep.py's Quit-button handler exactly which window is ours, so
    # it can taskkill that one PID instead of guessing by title too.
    sitrep.OUR_FIREFOX_PID = our_pid

    if our_pid:
        _pin_to_primary_monitor(int(our_pid), w, h)
        while _pid_alive(our_pid):
            time.sleep(2)
    else:
        # Couldn't uniquely identify our window (e.g. the owner already had
        # another Sitrep-titled window open at the exact same moment) — fall
        # back to "every Sitrep-titled window has closed", same coarse check
        # this used before the diff-based fix.
        while _sitrep_titled_pids():
            time.sleep(2)


if __name__ == "__main__":
    main()
