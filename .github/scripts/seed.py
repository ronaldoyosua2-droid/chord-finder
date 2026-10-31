# Data contoh untuk SS: 3 lagu + 3 daftar ibadah (tanggal relatif ke hari ini)
import json, datetime, sys, uuid
out = sys.argv[1]
contoh = open(".github/scripts/contoh.txt").read()
today = datetime.date.today()
sun = today + datetime.timedelta(days=(6 - today.weekday()) % 7)
def song(i, title, raw, key, bpm):
    return {"id": f"song-{i}", "title": title, "source": "", "raw": raw, "key": key, "transpose": 0,
            "starred": i == 1, "updated": 1, "bpm": bpm}
songs = [
    song(1, "Lagu Contoh", contoh, 0, 76),
    song(2, "Lagu Kedua", "Do = G\nVerse :\nG    D/F#   Em   C\nLirik contoh\nAm   D   G\nLirik contoh", 7, 70),
    song(3, "Lagu Ketiga", "Do = D\nReff :\nD   A/C#   Bm   G\nLirik contoh\nEm   A   D\nLirik contoh", 2, 84),
]
def sl(days, name, ids, tr=None):
    return {"id": str(uuid.uuid4()), "date": str(sun + datetime.timedelta(days=days)), "name": name,
            "items": [dict({"s": s}, **({"t": tr} if (tr is not None and s == "song-3") else {})) for s in ids]}
setlists = [sl(0, "Ibadah Raya", ["song-1", "song-2", "song-3"], tr=2),
            sl(7, "Ibadah Raya", ["song-1", "song-3"]),
            sl(-7, "Ibadah Pemuda", ["song-2", "song-1"])]
json.dump(songs, open(out + "/songs.json", "w"))
json.dump(setlists, open(out + "/setlists.json", "w"))
