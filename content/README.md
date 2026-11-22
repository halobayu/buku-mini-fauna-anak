# Folder konten

Isi folder ini dibuat otomatis oleh Space "Buku Mini Fauna Anak"
(menu Publikasikan -> Publish ke GitHub). Jangan edit manual kecuali paham
strukturnya:

- manifest.json — daftar semua buku + nomor versi konten.
- books/<slug>.json — isi lengkap satu buku (judul, halaman, fakta, layout
  tiap halaman, storyboard, dan jangkar penampilan hewan + habitat).
- books/<slug>.md — versi Markdown buku yang sama, enak dibaca manusia,
  lengkap dengan tabel storyboard & layout di bagian akhir.
- images/<slug>/cover.webp — sampul buku.
- images/<slug>/page-N.webp — gambar halaman ke-N.

Aplikasi Android membaca manifest.json dari alamat raw repo ini:
https://raw.githubusercontent.com/halobayu/buku-mini-fauna-anak/main/content/manifest.json
