"""Apply, verify or revert the Redmi's in-call music vendor audio fix.

Root cause (measured 2026-09-27, Redmi Note 8 "ginkgo", Android 16 community ROM):
the vendor audio configuration maps USECASE_INCALL_MUSIC_UPLINK (SIM slot 1) to PCM 27 /
MultiMedia9, but leaves USECASE_INCALL_MUSIC_UPLINK2 (SIM slot 2) unmapped while its mixer path
uses MultiMedia2. Every in-call music write for a slot-2 call then fails pcm_prepare and the HAL
restarts the stream ~14 times per second (the old "helicopter" uplink).

The fix makes slot 2 use the same, already-valid front end as slot 1:
  audio_platform_info_intcodec.xml: add USECASE_INCALL_MUSIC_UPLINK2 -> PCM 27
  mixer_paths_idp.xml:              incall_music_uplink2 -> Incall_Music_2 Audio Mixer MultiMedia9

Needs adb running as root with /vendor mounted read-write (true on this ROM).
Originals are kept on the device in /vendor/etc/phonebridge-backup/ and restored by "revert".

usage: python vendor_audio_fix.py status|apply|revert
"""
import os
import subprocess
import sys
import tempfile

ADB = os.environ.get("ADB", r"C:\Users\ilkay.turna\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "17df6357")
MIXER = "/vendor/etc/mixer_paths_idp.xml"
PLATFORM = "/vendor/etc/audio_platform_info_intcodec.xml"
BACKUP_DIR = "/vendor/etc/phonebridge-backup"
LABEL = "u:object_r:vendor_configs_file:s0"

MIXER_OLD = '<ctl name="Incall_Music_2 Audio Mixer MultiMedia2" value="1" />'
MIXER_NEW = '<ctl name="Incall_Music_2 Audio Mixer MultiMedia9" value="1" />'
PLATFORM_ANCHOR = '<usecase name="USECASE_INCALL_MUSIC_UPLINK" type="out" id="27" />'
PLATFORM_LINE = '<usecase name="USECASE_INCALL_MUSIC_UPLINK2" type="out" id="27" />'


def adb(*args, check=True):
    result = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def shell(cmd, check=True):
    return adb("shell", cmd, check=check)


def pull(path):
    fd, local = tempfile.mkstemp(suffix=os.path.basename(path))
    os.close(fd)
    adb("pull", path, local)
    with open(local, encoding="utf-8") as fh:
        text = fh.read()
    os.remove(local)
    return text


def push(text, path):
    fd, local = tempfile.mkstemp()
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    tmp = "/data/local/tmp/" + os.path.basename(path) + ".new"
    adb("push", local, tmp)
    os.remove(local)
    shell(f"cat {tmp} > {path} && rm {tmp} && chmod 644 {path} && chown root:root {path} && chcon {LABEL} {path}")


def state():
    mixer = pull(MIXER)
    platform = pull(PLATFORM)
    mixer_fixed = MIXER_NEW in mixer and MIXER_OLD not in mixer
    platform_fixed = PLATFORM_LINE in platform
    return mixer, platform, mixer_fixed, platform_fixed


def restart_audio_hal():
    shell("kill $(pidof android.hardware.audio.service)", check=False)
    shell("sleep 4; getprop init.svc.vendor.audio-hal")


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "status"
    if "root" not in shell("id"):
        sys.exit("adb is not running as root")
    if shell("mount | grep -c -E 'mixer_paths_idp|audio_platform_info'", check=False).strip() not in ("", "0"):
        sys.exit("temporary bind mounts are active; unmount them first")
    mixer, platform, mixer_fixed, platform_fixed = state()
    print(f"mixer path fixed: {mixer_fixed}; platform mapping fixed: {platform_fixed}")
    if action == "status":
        return
    if action == "apply":
        if mixer_fixed and platform_fixed:
            print("already applied")
            return
        if mixer.count(MIXER_OLD) != 1 or platform.count(PLATFORM_ANCHOR) != 1:
            sys.exit("vendor files differ from the known layout; not touching them")
        shell(f"mkdir -p {BACKUP_DIR} && "
              f"[ -f {BACKUP_DIR}/mixer_paths_idp.xml ] || cp -p {MIXER} {BACKUP_DIR}/mixer_paths_idp.xml; "
              f"[ -f {BACKUP_DIR}/audio_platform_info_intcodec.xml ] || cp -p {PLATFORM} {BACKUP_DIR}/audio_platform_info_intcodec.xml")
        push(mixer.replace(MIXER_OLD, MIXER_NEW), MIXER)
        indent = platform[:platform.index(PLATFORM_ANCHOR)].rsplit("\n", 1)[1]
        push(platform.replace(PLATFORM_ANCHOR, PLATFORM_ANCHOR + "\n" + indent + PLATFORM_LINE), PLATFORM)
        shell("sync")
        restart_audio_hal()
    elif action == "revert":
        shell(f"test -f {BACKUP_DIR}/mixer_paths_idp.xml && test -f {BACKUP_DIR}/audio_platform_info_intcodec.xml")
        for name, path in (("mixer_paths_idp.xml", MIXER), ("audio_platform_info_intcodec.xml", PLATFORM)):
            shell(f"cat {BACKUP_DIR}/{name} > {path} && chcon {LABEL} {path}")
        shell("sync")
        restart_audio_hal()
    else:
        sys.exit(__doc__)
    _, _, mixer_fixed, platform_fixed = state()
    print(f"after {action}: mixer path fixed: {mixer_fixed}; platform mapping fixed: {platform_fixed}")


if __name__ == "__main__":
    main()
