# Buku Mini Fauna Anak

Konten buku fakta hewan untuk anak SD kelas 2 — dibaca oleh aplikasi pembaca (APK) tanpa perlu install ulang saat konten bertambah.

## Struktur konten

- `content/manifest.json` — daftar buku + `contentVersion` (APK mengecek versi ini untuk update konten)
- `content/books/` — tiap buku dalam format `.json` dan `.md`
- `content/images/<id-buku>/` — sampul (`cover.webp`) + ilustrasi halaman (`page-1.webp` … `page-6.webp`)

## Buku saat ini

1. **Ular Hebat Tanpa Makan Lama** — Kenapa ular bisa tahan tanpa makan (6 halaman)

## Cara update konten

Tambah / ubah file di `content/`, naikkan `contentVersion` di `content/manifest.json`, lalu commit + push. APK akan mengambil konten terbaru dari repo ini.

Panduan gambar yang mengikat untuk semua ilustrasi ada di tiap file buku (bagian "Panduan Gambar Buku Fauna").
