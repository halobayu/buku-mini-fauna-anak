# Buku Mini Fauna Anak

Repo ini adalah rumah untuk aplikasi baca Buku Mini Fauna Anak:

- **content/** — isi buku (ditulis & digambar dari Space "Buku Mini Fauna Anak",
  lalu di-upload ke sini). APK membaca folder ini setiap dibuka, jadi buku baru
  atau edit halaman langsung muncul di tablet TANPA install ulang APK.
- **android-app/** — kode aplikasi Android (reader). Hanya berubah kalau ada
  fitur baru; build-nya otomatis lewat GitHub Actions dan hasilnya dibagikan
  lewat GitHub Releases (tag apk-v*).

## Cara update isi buku

1. Di Space, buka menu Publikasikan, lalu klik "Publish ke GitHub".
2. Unduh paket konten (ZIP), ekstrak.
3. Upload isi folder content/ dari ZIP ke folder content/ di repo ini
   (tulis/timpa file yang sama), lalu commit.
4. Buka aplikasi di tablet — buku terbaru langsung tampil.

## Cara update aplikasi (jarang, hanya kalau ada fitur baru)

1. Ubah kode di android-app/.
2. Push dengan tag, misalnya: git tag apk-v1.1 && git push origin apk-v1.1
3. GitHub Actions membangun APK dan melampirkannya ke GitHub Release.
