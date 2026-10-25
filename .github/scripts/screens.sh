#!/bin/bash
# Ambil SS aplikasi di emulator. Hasil: shots/*.png
set -x
PKG=com.ronald.chordfinder
mkdir -p shots
adb root; sleep 3
adb wait-for-device
adb install -r out/app.apk
adb shell settings put system screen_off_timeout 600000
adb shell input keyevent 82

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml ui.xml >/dev/null
  b=$(python3 - "$1" <<'PY'
import re,sys
t=sys.argv[1]; x=open('ui.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    tm=re.search(r'text="([^"]*)"', n)
    if tm and tm.group(1).startswith(t):
        a=list(map(int,re.findall(r'\d+', re.search(r'bounds="([^"]*)"',n).group(1))))
        print((a[0]+a[2])//2, (a[1]+a[3])//2); break
PY
)
  [ -n "$b" ] && adb shell input tap $b
}

# 1. Layar awal
adb shell am start -W -n $PKG/.MainActivity
shot 01-awal 4

# 2. Pencarian Google (diutamakan JRChord & PsalmNote)
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'https://www.google.com/search?hl=id&q=chord+Ajaib+Kau+Tuhan+(site:jrchord.com+OR+site:psalmnote.com)'" \
  -n $PKG/.MainActivity
shot 02-cari 10

# 3. Buka halaman JRChord -> chord diambil otomatis
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.jrchord.com/ajaib-kau-tuhan-jpcc-worship" \
  -n $PKG/.MainActivity
shot 03-jrchord 14

# 4. PsalmNote
adb shell input keyevent 4; sleep 1
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.psalmnote.com/" \
  -n $PKG/.MainActivity
shot 04-psalmnote 10

# 5. Lagu contoh (lirik buatan) - notasi angka sejajar
adb shell input keyevent 4; sleep 1
SONG="$(cat .github/scripts/contoh.txt)"
adb shell "am start -W -n $PKG/.SongActivity --es title 'Lagu Contoh' --es text '$SONG'"
shot 05-angka-sejajar 4

# 6. Mode angka di bawah chord
tap_text "Angka:"
shot 06-angka-bawah 2

# 7. Transpose +2
tap_text "Angka:"; sleep 1; tap_text "Angka:"   # kembali ke sejajar
tap_text "+"; sleep 1; tap_text "+"
shot 07-transpose 2

# 8. Simpan + bintang, lalu buka Koleksi
tap_text "Simpan"; sleep 1
tap_text "☆"; sleep 1
adb shell input keyevent 4; sleep 2
tap_text "Koleksi"
shot 08-koleksi 3

adb logcat -d -t 300 > shots/logcat.txt
ls -la shots
