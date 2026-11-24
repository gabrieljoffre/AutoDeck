"""Screenshot of an ADB-connected Android device + list of visible elements + a11y audit.
Usage: python screen.py <dir> <NN>"""
import io
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import a11y  # noqa: E402


def adb(*a, raw=False):
    r = subprocess.run(["adb", *a], capture_output=True, timeout=60)
    return r.stdout if raw else r.stdout.decode("utf-8", "replace")


if __name__ == "__main__":
    out, nn = Path(sys.argv[1]), sys.argv[2]
    out.mkdir(parents=True, exist_ok=True)
    from PIL import Image

    png = adb("exec-out", "screencap", "-p", raw=True)
    png = png[png.find(b"\x89PNG"):] if b"\x89PNG" in png else png
    img = Image.open(io.BytesIO(png)).convert("RGB")
    w, h = img.size
    img.resize((w // 2, h // 2)).save(out / f"{nn}.png")
    print(f"screenshot: {out}/{nn}.png (screen {w}x{h}; image is half size: multiply positions read on it by 2)")
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    try:
        root = ET.fromstring(xml[xml.find("<"):])
    except ET.ParseError:
        root = ET.Element("hierarchy")
    nodes = list(root.iter("node"))
    for n in nodes:
        text, desc = (n.get("text") or "").strip(), (n.get("content-desc") or "").strip()
        edit = n.get("class", "").endswith("EditText")
        b = re.findall(r"\d+", n.get("bounds", ""))
        if not (text or desc or edit) or len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        kind = "field" if edit else ("button" if n.get("clickable") == "true" else "text")
        print(f'- {kind} "{(text or desc)[:70]}" centre=({(x1 + x2) // 2},{(y1 + y2) // 2})')
    try:
        memo = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "density_dpi"
        if not memo.exists():
            dpi = re.findall(r"\d+", adb("shell", "wm", "density"))
            memo.write_text(dpi[-1] if dpi else "160")
        check = a11y.android(root, int(memo.read_text()), w, h)
        if check:
            print(check)
    except Exception:
        pass
    crash = adb("logcat", "-d", "-b", "crash") + adb("logcat", "-d", "AndroidRuntime:E", "*:S")
    adb("logcat", "-c")
    lines = [x for x in crash.splitlines() if x.strip() and not x.startswith("-----")]
    if lines:
        print("ERRORS SINCE LAST SCREENSHOT:\n" + "\n".join(lines[-15:]))
