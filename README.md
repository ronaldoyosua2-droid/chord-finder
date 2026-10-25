# Chord Finder

Aplikasi Android untuk mencari chord lagu (utama dari JRChord dan PsalmNote) lewat judul atau potongan lirik,
lalu menampilkannya dengan notasi angka sesuai nada dasar. Contoh kunci A: `Bm(2)  E(5)  A/C#(1/3)`.

## Fitur
- Cari online lewat Google, diutamakan `jrchord.com` dan `psalmnote.com` (bisa pilih "Semua situs")
- Chord otomatis diambil dari halaman dan ditampilkan rapi, lengkap dengan notasi angka (sejajar, di bawah, atau mati)
- Deteksi nada dasar otomatis (dari tulisan "Do = …"/"Key: …" atau dari chord-chordnya), bisa dikoreksi manual
- Transpose −/+ (notasi angka tetap)
- Koleksi offline: simpan lagu, cari lagi pakai judul atau potongan lirik tanpa internet
- Daftar ★ Minggu ini, auto-scroll, ukuran huruf, layar tetap menyala
- Tempel chord manual atau bagikan teks/link chord dari aplikasi lain ke Chord Finder

## Build
APK dibuat otomatis oleh GitHub Actions setiap push ke `main` dan diunggah ke halaman Releases.
