# Catatan untuk Claude (Chord Finder)

## Aturan kerja dari pemilik (Ronald)
- **Aturan "gas":** sebelum membangun/mengubah apa pun, jelaskan dulu rencananya (pakai gambar/mockup kalau bisa).
  Baru kerjakan setelah pemilik membalas **gas**, dan kerjakan **persis** sesuai penjelasan: tidak kurang, tidak lebih.
  Hal lain yang ketemu saat mengerjakan → tanyakan dulu, jangan langsung ditambahkan.
- Push/rilis juga baru setelah disetujui. Uji dulu di cabang `dev` (workflow *Screenshots*: build + unit test + SS emulator),
  baru merge ke `main` (push ke `main` = rilis otomatis `v1.0.<run>`).

## Rilis
- Deskripsi rilis = `.github/release/INTRO.md` + `## ✨ What's new · vX` + `WHATS_NEW.md` + `---` + `.github/release/OUTRO.md`
  (bahasa Inggris, seperti proyek ModSync).
- **Sebelum setiap rilis:** tulis ulang `WHATS_NEW.md` (hanya perubahan versi itu, poin singkat ber-emoji) dan
  tambahkan entri versi baru di paling atas `app/src/main/assets/changelog.json` (menu ⋮ → Apa yang baru).
- Aplikasi mengecek update lewat `api.github.com/repos/ronaldoyosua2-droid/chord-finder/releases/latest`;
  nomor build diambil dari tag `v1.0.N` (= versionCode). Bagian What's new dari deskripsi ditampilkan di kartu update.
