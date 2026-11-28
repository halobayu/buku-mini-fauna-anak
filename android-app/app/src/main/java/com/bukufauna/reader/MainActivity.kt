
package com.bukufauna.reader

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread

// ============================================================
// GANTI baris di bawah ini dengan username GitHub kamu.
// Repo: https://github.com/halobayu/buku-mini-fauna-anak
// ============================================================
const val GITHUB_USER = "halobayu"
const val REPO = "buku-mini-fauna-anak"
const val RAW = "https://raw.githubusercontent.com/" + GITHUB_USER + "/" + REPO + "/main/content"

// Reader APK ini SENGAJA dibuat sama persis dengan Reader di Space:
// rak buku NEUMORPHISM NETRAL di depan (abu-abu hangat, elemen
// timbul/cekung dari bayangan lembut sewarna latar, TANPA hijau;
// buku berdiri per baris di atas papan rak netral, judul tercetak
// di sampul), lalu
// buku dibuka SATU HALAMAN PENUH tepi-ke-tepi — tanpa scroll, bukan
// dua halaman, bukan PDF. Halamannya dibalik seperti buku beneran:
// animasinya berengsel di TEPI KIRI kertas (punggung buku,
// transform-origin: left center, bukan tengah), dan bisa dibalik
// lewat geser, ketuk tepi kiri/kanan, tombol panah, atau bar bawah
// minimal (dua lingkaran panah SVG netral melayang + label halaman
// kecil dengan garis kemajuan tipis di tengah bawah — tanpa pill
// gelap). Semua tampilan
// itu dirender HTML/CSS/JS di bawah ini dari JSON konten yang sama
// dengan yang dibaca Space (manifest.json + books/<slug>.json).
// Tombol "Perbarui Konten" di rak menarik ulang manifest.json dari
// GitHub tanpa install ulang / restart; manifest + JSON buku terakhir
// yang berhasil diunduh disimpan di cache lokal, jadi saat internet
// mati rak tetap menampilkan konten terakhir dengan pesan yang jelas.
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var manifestText: String? = null
    private var inBook = false
    private var updateDownloadId: Long = -1L
    private var updateReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = Client()
        // Unduhan update APK: saat DownloadManager selesai, pasang
        // file-nya (atau beri tahu kalau gagal).
        updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != updateDownloadId) return
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val q = DownloadManager.Query().setFilterById(id)
                val c = dm.query(q)
                var ok = false
                var uri: Uri? = null
                if (c != null) {
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            ok = true
                            uri = dm.getUriForDownloadedFile(id)
                        }
                    }
                    c.close()
                }
                if (ok && uri != null) installApk(uri)
                else Toast.makeText(this@MainActivity, "Unduhan update gagal.", Toast.LENGTH_LONG).show()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
        setContentView(web)
        web.loadDataWithBaseURL(RAW + "/", simplePage("<h1>Rak Buku Fauna</h1><p class='dim'>Memuat rak buku...</p>"), "text/html", "utf-8", null)
        thread { loadShelf(firstLoad = true) }
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn.inputStream.bufferedReader().readText()
    }

    // Cache lokal: konten terakhir yang berhasil diunduh, dipakai lagi
    // saat internet mati supaya rak tidak pernah kosong mendadak.
    private fun readCache(name: String): String? {
        return try {
            val f = File(cacheDir, name)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(name: String, text: String) {
        try {
            File(cacheDir, name).writeText(text)
        } catch (e: Exception) {
            // Cache gagal ditulis tidak boleh menggagalkan membaca.
        }
    }

    private fun loadShelf(firstLoad: Boolean) {
        try {
            val text = fetchText(RAW + "/manifest.json")
            writeCache("manifest.json", text)
            manifestText = text
            val manifest = JSONObject(text)
            val count = manifest.optJSONArray("books")?.length() ?: 0
            val version = manifest.optInt("contentVersion")
            showShelf(
                if (firstLoad) ""
                else "Konten terbaru · " + count + " buku · versi konten v" + version
            )
        } catch (e: Exception) {
            val cached = manifestText ?: readCache("manifest.json")
            if (cached != null) {
                manifestText = cached
                showShelf("Tidak ada internet — menampilkan konten terakhir yang tersimpan.")
            } else {
                runOnUiThread {
                    web.loadDataWithBaseURL(null, errorPage(), "text/html", "utf-8", null)
                }
            }
        }
    }

    private fun showShelf(status: String) {
        val m = manifestText ?: return
        runOnUiThread {
            inBook = false
            web.loadDataWithBaseURL(RAW + "/", shelfPage(m, status), "text/html", "utf-8", null)
        }
    }

    // Tombol "Perbarui Konten": tampilkan status memperbarui dari data
    // yang sudah ada dulu, lalu tarik manifest terbaru dari GitHub.
    private fun refreshShelf() {
        if (manifestText != null) showShelf("Memperbarui...")
        thread { loadShelf(firstLoad = false) }
    }

    private fun showBook(file: String) {
        thread {
            val cacheName = "book_" + file.replace("/", "_")
            var text: String? = null
            try {
                text = fetchText(RAW + "/" + file)
                writeCache(cacheName, text)
            } catch (e: Exception) {
                text = readCache(cacheName)
            }
            if (text == null) {
                if (manifestText == null) manifestText = readCache("manifest.json")
                if (manifestText != null) {
                    showShelf("Tidak bisa membuka buku itu — periksa internet lalu coba lagi.")
                } else {
                    runOnUiThread {
                        web.loadDataWithBaseURL(null, errorPage(), "text/html", "utf-8", null)
                    }
                }
                return@thread
            }
            val bookText = text
            runOnUiThread {
                inBook = true
                web.loadDataWithBaseURL(RAW + "/", bookPage(bookText), "text/html", "utf-8", null)
            }
        }
    }

    override fun onBackPressed() {
        if (inBook) showShelf("") else super.onBackPressed()
    }

    override fun onDestroy() {
        updateReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        super.onDestroy()
    }

    private fun installApk(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    // Unduh APK rilis terbaru lewat DownloadManager. Sebelum mengunduh,
    // Android 8+ wajib mengizinkan "install dari sumber ini" untuk
    // aplikasi ini — kalau belum, buka halaman izin sistemnya dulu dan
    // minta pengguna mengetuk "Unduh & pasang" lagi sesudahnya.
    // Upgrade di tempat hanya berhasil kalau APK unduhan ditandatangani
    // kunci yang sama dengan APK terpasang (aset rilis Actions cocok
    // dengan instalasi dari aset yang sama).
    private fun startApkDownload(url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName))
            startActivity(intent)
            Toast.makeText(this, "Izinkan install dari aplikasi ini, lalu ketuk \"Unduh & pasang\" lagi.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val req = DownloadManager.Request(Uri.parse(url))
            req.setTitle("Buku Mini Fauna \u2014 update")
            req.setDescription("Mengunduh versi terbaru\u2026")
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "buku-mini-fauna-reader.apk")
            req.setMimeType("application/vnd.android.package-archive")
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            updateDownloadId = dm.enqueue(req)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa memulai unduhan update.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // Tidak ada browser: abaikan saja.
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (url.startsWith("book:")) { showBook(url.removePrefix("book:")); return true }
            if (url.startsWith("shelf:")) { showShelf(""); return true }
            if (url.startsWith("refresh:")) { refreshShelf(); return true }
            if (url.startsWith("update:")) { startApkDownload(URLDecoder.decode(url.removePrefix("update:"), "UTF-8")); return true }
            if (url.startsWith("open:")) { openExternal(URLDecoder.decode(url.removePrefix("open:"), "UTF-8")); return true }
            return false
        }
    }

    // Sisipkan JSON ke dalam <script> dengan aman: urutan "</" di
    // dalam teks buku tidak boleh bisa menutup tag script lebih awal.
    private fun jsJson(json: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < json.length) {
            if (json[i] == '<' && i + 1 < json.length && json[i + 1] == '/') {
                sb.append('<'); sb.append('\\'); sb.append('/'); i += 2
            } else {
                sb.append(json[i]); i++
            }
        }
        return sb.toString()
    }

    private fun shelfPage(manifestJson: String, status: String): String {
        return SHELF_HEAD +
            "<script>var MANIFEST = " + jsJson(manifestJson) +
            ";var STATUS = " + JSONObject.quote(status) + ";</script>" +
            STYLE_SCRIPT + SHELF_SCRIPT + "</body></html>"
    }

    private fun bookPage(bookJson: String): String {
        return READER_HEAD +
            "<script>var BOOK = " + jsJson(bookJson) + ";var APP_VERSION = " + JSONObject.quote(BuildConfig.VERSION_NAME) + ";</script>" +
            STYLE_SCRIPT + READER_SCRIPT + "</body></html>"
    }

    private fun errorPage(): String {
        return simplePage("<h1>Ups!</h1><p>Tidak bisa memuat buku. Periksa internet lalu coba lagi.</p><p><a href='refresh:'>&#8635; Coba lagi</a></p>")
    }

    private fun simplePage(body: String): String {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>body{font-family:sans-serif;background:#E9E5DD;color:#35322C;margin:16px}" +
            "h1{font-size:28px} .dim{color:#655E52} a{color:#6F6152;font-weight:bold}</style></head><body>" +
            body + "</body></html>"
    }
}

// Palet terkunci per gaya gambar — cerminan persis dari styles-data.ts
// di Space (nama gaya, nama palet, 5 warna terkunci, kertas, tinta,
// wash, warna callout, nada gelap). Palet gaya tetap kunci ilustrasi
// (pelat palet di sampul), tetapi CHROME rak + halaman baca memakai
// sistem NEUMORPHISM NETRAL yang sama dengan Space (READER_NEU di
// styles-data.ts): NEU di bawah menimpa paper/ink/wash/callout/deep/
// accent saat render — abu-abu hangat, aksen taupe, TANPA hijau —
// dan karena gradient melt ilustrasi + zona teks sama-sama diambil
// dari paper yang sama, sambungan gambar-ke-teks tetap seamless.
private val STYLE_SCRIPT = """<script>
var STYLES = {
"cat-air": {"name": "Cat Air Semi-Realistis", "accent": "#14665a", "palette": {"name": "Rimba Lembut", "colors": ["#4c6b4f", "#a8c8bc", "#d9a441", "#a8c3cf", "#c47f5a"], "paper": "#fbf4e2", "ink": "#2f3a2c", "wash": "#e9efdb", "callout": "#d9a441", "calloutInk": "#5b4305", "deep": "#22301f"}},
"kartun": {"name": "Ilustrasi Semi-Realistis", "accent": "#386641", "palette": {"name": "Alam Nyata", "colors": ["#2d4a32", "#7a9a6d", "#c99a3c", "#8fb8c8", "#b56545"], "paper": "#fbf4e2", "ink": "#243324", "wash": "#e6ecd9", "callout": "#d9a441", "calloutInk": "#4a3205", "deep": "#1c2a1e"}},
"krayon": {"name": "Pensil Warna Naturalis", "accent": "#9a3412", "palette": {"name": "Krayon Klasik", "colors": ["#d64533", "#4e7a3a", "#8a5a33", "#f4a261", "#e9c46a"], "paper": "#fcf3df", "ink": "#3a2e22", "wash": "#fbeed3", "callout": "#f4a261", "calloutInk": "#5f3410", "deep": "#2e2118"}},
"kolase": {"name": "Kolase Tekstur Naturalis", "accent": "#7c2d52", "palette": {"name": "Potong Tempel", "colors": ["#ef476f", "#ffd166", "#06d6a0", "#118ab2", "#7c2d52"], "paper": "#fbf1e4", "ink": "#33272e", "wash": "#fdebd3", "callout": "#ffd166", "calloutInk": "#5b4305", "deep": "#2a1b26"}}
};
function styleInfo(id) { return STYLES[id] || STYLES["cat-air"]; }
var NEU = {"paper": "#E9E5DD", "ink": "#35322C", "accent": "#6F6152", "accentInk": "#5C5041", "deep": "#2B2721", "calloutInk": "#FBF7EE", "raised": "7px 7px 14px rgba(133,122,102,0.5), -7px -7px 14px rgba(255,255,255,0.95)", "raisedSm": "5px 5px 10px rgba(133,122,102,0.5), -5px -5px 10px rgba(255,255,255,0.95)", "inset": "inset 5px 5px 10px rgba(133,122,102,0.45), inset -5px -5px 10px rgba(255,255,255,0.9)", "rule": ["#6F6152", "#A79C88", "#CFC7B6"]};
function esc(s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; }
</script>"""

private val SHELF_HEAD = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@400;500;600;700&family=Nunito:wght@400;600;700;800;900&display=swap" rel="stylesheet">
<style>
*{box-sizing:border-box}
html,body{margin:0;padding:0}
body{font-family:'Nunito',sans-serif;background:#E9E5DD;color:#35322C}
button{font-family:inherit}
.wrap{max-width:768px;margin:0 auto;padding:16px 16px 40px}
h1{font-family:'Fredoka','Nunito',sans-serif;font-size:36px;margin:8px 0 4px;font-weight:600}
.sub{color:#655E52;font-size:16px;font-weight:600;margin:0;line-height:1.45}
.statusbar{margin-top:16px;display:flex;flex-wrap:wrap;gap:8px;align-items:center;background:#E9E5DD;border-radius:24px;padding:14px 16px;box-shadow:10px 10px 20px #C7C0AF, -10px -10px 20px #FFFFFF}
.statusbar .ver{font-size:13px;font-weight:700;color:#655E52}
.refresh{margin-left:auto;background:#6F6152;color:#FFFFFF;border:0;border-radius:999px;padding:10px 18px;font-size:15px;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;cursor:pointer;min-height:44px;box-shadow:inset 0 2px 0 rgba(255,255,255,.28), inset 0 -2px 0 rgba(0,0,0,.10), 0 10px 20px -10px rgba(90,78,58,.45)}
.refresh:active{transform:translateY(1px)}
.status{font-size:14px;font-weight:700;color:#5C5041;margin:10px 2px 0}
.case{margin-top:18px;background:#E9E5DD;overflow:hidden;border-radius:28px;box-shadow:inset 6px 6px 14px #CBC4B3, inset -6px -6px 14px #FFFFFF}
.shelf-row{display:flex;gap:10px;padding:14px 10px 0;list-style:none;margin:0}
.bookcell{flex:1;min-width:0;display:flex;flex-direction:column;padding-bottom:12px}
.book{position:relative;display:block;width:100%;max-width:190px;margin:0 auto;border-radius:4px 12px 12px 4px;overflow:hidden;box-shadow:0 16px 24px -8px rgba(90,80,64,.55);text-decoration:none;background:#DED8CB}
.book img{width:100%;aspect-ratio:4/5;object-fit:cover;display:block}
.book .scrim{position:absolute;left:0;right:0;top:0;height:52%}
.book .ctitle{position:absolute;left:0;right:0;top:0;padding:10px 12px 4px;text-align:center;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;font-size:15px;line-height:1.25}
.book .spine{position:absolute;top:0;bottom:0;left:0;width:9px;background:linear-gradient(to right, rgba(0,0,0,.35), rgba(0,0,0,.10), rgba(0,0,0,0))}
.book .fore{position:absolute;top:0;bottom:0;right:0;width:4px;background:repeating-linear-gradient(to right, rgba(120,100,60,.25) 0 1px, rgba(255,252,244,.95) 1px 2.5px)}
.plank{height:20px;background:linear-gradient(to bottom, #F4F0E7 0%, #E7E2D6 45%, #DAD3C1 100%);box-shadow:inset 0 2px 0 rgba(255,255,255,.55), 0 14px 18px -10px rgba(101,90,70,.55)}
.plankshadow{height:8px;background:rgba(0,0,0,.10)}
.empty{padding:40px 24px 8px;text-align:center}
.empty .lion{font-size:56px;margin:0}
.empty h2{font-family:'Fredoka','Nunito',sans-serif;font-size:24px;font-weight:600;margin:10px 0 4px}
.empty p{color:#655E52;font-weight:600;margin:0 auto;max-width:360px;line-height:1.5}
.gap{height:40px}
@media (prefers-color-scheme: dark){
body{background:#26231E;color:#F2EDE2}
.sub{color:#B4AB99}
.statusbar{background:#26231E;box-shadow:10px 10px 20px #161310, -10px -10px 20px #38332B}
.statusbar .ver{color:#B4AB99}
.refresh{background:#7D6C55}
.status{color:#C9BBA3}
.case{background:#26231E;box-shadow:inset 6px 6px 14px #171410, inset -6px -6px 14px #38332B}
.plank{background:linear-gradient(to bottom, #3A352B 0%, #2E2A22 45%, #26231E 100%)}
.book{background:#332E26}
.empty p{color:#B4AB99}
}
</style></head>
<body>
<div class="wrap">
<h1>&#129409; Rak Buku Fauna</h1>
<p class="sub">Pilih buku di rak, lalu buka dan balik halamannya seperti buku beneran — satu halaman penuh, tanpa scroll.</p>
<div id="app"></div>
</div>
"""

private val SHELF_SCRIPT = """<script>
function perShelf() { return window.innerWidth < 560 ? 2 : 3; }
function goRefresh() { location.href = "refresh:"; }
function bookCell(b) {
  // Title scrim = shelf chrome: the neutral NEU paper/ink, not the
  // book's art palette (Space parity).
  var p = NEU;
  return '<li class="bookcell">'
    + '<a class="book" href="book:' + b.file + '">'
    + '<img src="' + b.cover + '" alt=""/>'
    + '<span class="scrim" style="background:linear-gradient(to bottom, ' + p.paper + 'F0 0%, ' + p.paper + 'A6 52%, ' + p.paper + '00 100%)"></span>'
    + '<span class="ctitle" style="color:' + p.ink + '">' + esc(b.title) + '</span>'
    + '<span class="spine"></span><span class="fore"></span></a></li>';
}
function renderShelf() {
  var books = (typeof MANIFEST !== "undefined" && MANIFEST && MANIFEST.books) || [];
  var h = '<div class="statusbar"><span class="ver">Versi konten: '
    + (typeof MANIFEST !== "undefined" && MANIFEST && MANIFEST.contentVersion ? "v" + MANIFEST.contentVersion : "belum ada")
    + ' · ' + books.length + ' buku</span>'
    + '<button class="refresh" onclick="goRefresh()">&#8635; Perbarui Konten</button></div>';
  if (typeof STATUS !== "undefined" && STATUS) h += '<p class="status">' + esc(STATUS) + '</p>';
  if (!books.length) {
    h += '<div class="case"><div class="empty"><p class="lion">&#129409;</p><h2>Raknya masih kosong</h2>'
      + '<p>Belum ada buku yang diterbitkan ke repo ini. Buat buku di Space, publikasikan ke GitHub, lalu ketuk Perbarui Konten.</p></div>'
      + '<div class="plank"></div><div class="plankshadow"></div><div class="gap"></div>'
      + '<div class="plank"></div><div class="plankshadow"></div></div>';
  } else {
    h += '<div class="case">';
    var n = perShelf();
    for (var i = 0; i < books.length; i += n) {
      h += '<ul class="shelf-row">';
      var j;
      for (j = i; j < Math.min(i + n, books.length); j++) h += bookCell(books[j]);
      for (var e = j; e < i + n; e++) h += '<li class="bookcell"></li>';
      h += '</ul><div class="plank"></div><div class="plankshadow"></div>';
    }
    h += '</div>';
  }
  document.getElementById("app").innerHTML = h;
}
window.addEventListener("resize", renderShelf);
renderShelf();
</script>"""

private val READER_HEAD = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@400;500;600;700&family=Nunito:ital,wght@0,400;0,600;0,700;0,800;0,900;1,400&display=swap" rel="stylesheet">
<style>
*{box-sizing:border-box}
html,body{margin:0;padding:0;height:100%}
body{font-family:'Nunito',sans-serif;overflow:hidden;background:#14120E;color:#F2EDE2}
button{font-family:inherit}
#stage{position:fixed;inset:0;perspective:2200px;perspective-origin:42% 50%;overflow:hidden}
#underleaf,#turnleaf,#leaf{position:absolute;inset:0;transform-origin:left center;transform-style:preserve-3d}
#underleaf{z-index:1;overflow:hidden}
#turnleaf{z-index:5;pointer-events:none;will-change:transform}
.face-front,.face-back{position:absolute;inset:0;backface-visibility:hidden;-webkit-backface-visibility:hidden;overflow:hidden}
.face-back{transform:rotateY(180deg);box-shadow:inset -18px 0 36px rgba(90,72,48,.18), inset 0 0 60px rgba(120,96,64,.08)}
.under-shade{position:absolute;inset:0;z-index:25;pointer-events:none;opacity:0}
.fold-sheen{position:absolute;top:0;bottom:0;right:0;width:42%;z-index:28;pointer-events:none;background:linear-gradient(to left, rgba(0,0,0,.22) 0%, rgba(255,255,255,.18) 18%, rgba(0,0,0,.06) 45%, rgba(0,0,0,0) 100%);opacity:0}
@keyframes turnOutNext{
  0%{transform:rotateY(0deg);filter:drop-shadow(-2px 4px 8px rgba(0,0,0,.18))}
  35%{transform:rotateY(-42deg) skewY(-0.8deg);filter:drop-shadow(-18px 10px 26px rgba(0,0,0,.38))}
  72%{transform:rotateY(-88deg) skewY(-0.4deg);filter:drop-shadow(-24px 12px 30px rgba(0,0,0,.32))}
  100%{transform:rotateY(-112deg);opacity:0;filter:drop-shadow(-10px 6px 14px rgba(0,0,0,.15))}
}
@keyframes turnInPrev{
  0%{transform:rotateY(-112deg);opacity:.25;filter:drop-shadow(-10px 6px 14px rgba(0,0,0,.2))}
  32%{transform:rotateY(-76deg) skewY(-0.6deg);opacity:1;filter:drop-shadow(-24px 12px 30px rgba(0,0,0,.38))}
  68%{transform:rotateY(-32deg) skewY(-0.4deg);opacity:1;filter:drop-shadow(-14px 8px 20px rgba(0,0,0,.28))}
  100%{transform:rotateY(0deg);opacity:1;filter:drop-shadow(0 0 0 rgba(0,0,0,0))}
}
@keyframes underShadeNext{
  0%{opacity:1;background:linear-gradient(to right, rgba(20,16,10,.45) 0%, rgba(20,16,10,.25) 45%, rgba(20,16,10,0) 85%)}
  60%{opacity:.55;background:linear-gradient(to right, rgba(20,16,10,.35) 0%, rgba(20,16,10,.12) 40%, rgba(20,16,10,0) 75%)}
  100%{opacity:0}
}
@keyframes underShadePrev{
  0%{opacity:0}
  45%{opacity:.65;background:linear-gradient(to right, rgba(20,16,10,.38) 0%, rgba(20,16,10,.14) 45%, rgba(20,16,10,0) 80%)}
  100%{opacity:1;background:linear-gradient(to right, rgba(20,16,10,.45) 0%, rgba(20,16,10,.2) 40%, rgba(20,16,10,0) 75%)}
}
@keyframes sheenPulse{
  0%{opacity:0;transform:translateX(15%)}
  40%{opacity:.95;transform:translateX(-10%)}
  100%{opacity:0;transform:translateX(-35%)}
}
@keyframes coverOpen{0%{opacity:.35;transform:rotateY(-78deg);filter:brightness(.78) drop-shadow(-28px 16px 32px rgba(0,0,0,.5))}100%{opacity:1;transform:rotateY(0deg);filter:brightness(1)}}
.turn-next{animation:turnOutNext 620ms cubic-bezier(.22,.68,.24,1) forwards}
.turn-prev{animation:turnInPrev 620ms cubic-bezier(.22,.68,.24,1) forwards}
.shade-next{animation:underShadeNext 620ms cubic-bezier(.22,.68,.24,1) forwards}
.shade-prev{animation:underShadePrev 620ms cubic-bezier(.22,.68,.24,1) forwards}
.sheen-anim{animation:sheenPulse 620ms cubic-bezier(.22,.68,.24,1) forwards}
.cover-open{animation:coverOpen 560ms cubic-bezier(.22,.72,.28,1)}
@media (prefers-reduced-motion: reduce){.turn-next,.turn-prev,.shade-next,.shade-prev,.sheen-anim,.cover-open{animation:none}}
.sheet{position:absolute;inset:0;overflow:hidden}
.bindshade{position:absolute;top:0;bottom:0;left:0;width:22px;background:linear-gradient(to right, rgba(0,0,0,.15), rgba(0,0,0,.05), rgba(0,0,0,0));z-index:30;pointer-events:none}
.foreedge{position:absolute;top:0;bottom:0;right:0;width:7px;background:repeating-linear-gradient(to right, rgba(120,100,60,.20) 0 1.5px, rgba(255,252,244,.9) 1.5px 3.5px);z-index:30;pointer-events:none}
.bottomstack{position:absolute;left:0;right:0;bottom:0;height:6px;background:repeating-linear-gradient(to bottom, rgba(120,100,60,.18) 0 1.5px, rgba(255,252,244,.9) 1.5px 3.5px);z-index:30;pointer-events:none}
#backbtn{position:fixed;left:12px;bottom:14px;z-index:20;width:52px;height:52px;border-radius:999px;display:flex;align-items:center;justify-content:center;background:#E9E5DD;color:#5C5041;text-decoration:none;font-size:24px;line-height:1;box-shadow:5px 5px 10px rgba(133,122,102,.5), -5px -5px 10px rgba(255,255,255,.95)}
.edge{position:fixed;top:0;bottom:0;width:13%;z-index:10;background:transparent;border:0;padding:0;cursor:pointer}
#edgeL{left:0}
#edgeR{right:0}
.edge[disabled]{cursor:default}
.navbtn{position:fixed;bottom:14px;z-index:20;width:48px;height:48px;border-radius:999px;border:0;padding:0;color:#5C5041;cursor:pointer;display:flex;align-items:center;justify-content:center;line-height:0;box-shadow:5px 5px 10px rgba(133,122,102,.5), -5px -5px 10px rgba(255,255,255,.95)}
#prevBtn{left:76px}
#nextBtn{right:12px}
.navbtn:active{transform:scale(.95)}
.navbtn[disabled]{opacity:.25}
#pageind{position:fixed;left:136px;right:72px;bottom:22px;z-index:20;display:flex;flex-direction:column;align-items:center;gap:5px;pointer-events:none}
#indlabel{font-size:13px;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;white-space:nowrap;line-height:1}
.progbar{width:116px;height:5px;border-radius:99px;overflow:hidden}
@media (max-width:399px){.progbar{width:88px}}
#prog{height:100%;border-radius:99px;transition:width .3s}
.coverimg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.coverscrim{position:absolute;left:0;right:0;top:0;height:46%;pointer-events:none}
.covertitle{position:absolute;left:0;right:0;top:8%;text-align:center}
.covertitle h2{margin:0;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;line-height:1.08}
.composed h2{font-family:'Fredoka','Nunito',sans-serif;font-weight:600}
.covertitle p{margin:8px auto 0;font-weight:600;line-height:1.35;max-width:78%}
.composed{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}
.kicker{font-family:'Fredoka','Nunito',sans-serif;font-weight:600;text-transform:uppercase;letter-spacing:.22em;margin:0}
.imgzone{position:relative;overflow:hidden;flex:0 0 auto;width:100%}
.zoneimg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.whole{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:50% 18%}
.melts{position:absolute;top:0;bottom:0;width:34%;z-index:10;pointer-events:none}
.textzone{position:relative;display:flex;flex-direction:column;justify-content:center;overflow:hidden;min-height:0;min-width:0}
.textzone>*{flex:0 0 auto}
.botscrim{position:absolute;left:0;right:0;bottom:0;height:66%;z-index:5;pointer-events:none}
.overlaytext{color:#fff;font-weight:700;line-height:1.42;text-shadow:0 2px 8px rgba(0,0,0,.6),0 1px 3px rgba(0,0,0,.5)}
.pagetitle{position:absolute;left:0;right:0;top:0;z-index:10;padding:7% 8% 0;pointer-events:none}
.pagetitle h3{margin:0;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;line-height:1.15;color:#fff;text-shadow:0 2px 10px rgba(0,0,0,.55),0 1px 3px rgba(0,0,0,.5)}
.spreadhead{display:flex;align-items:center;gap:14px}
.spreadhead h3{margin:0;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;line-height:1.15}
.rule{display:flex;gap:6px}
.rule i{display:block;height:8px;border-radius:99px}
.anchor{border-radius:24px;font-weight:700;line-height:1.42;margin:0;box-shadow:7px 7px 14px rgba(133,122,102,.5), -7px -7px 14px rgba(255,255,255,.95)}
.cluster{font-weight:600;line-height:1.5;margin:0;opacity:.88}
.callout{position:relative;border-radius:26px;box-shadow:inset 5px 5px 10px rgba(133,122,102,.45), inset -5px -5px 10px rgba(255,255,255,.9)}
.callouttab{position:absolute;left:22px;border-radius:999px;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;text-transform:uppercase;letter-spacing:.14em;box-shadow:inset 0 2px 0 rgba(255,255,255,.35), inset 0 -2px 0 rgba(0,0,0,.14), 0 6px 10px -4px rgba(90,78,58,.45)}
.callout p{margin:0;font-weight:600;line-height:1.45}
.missingbox{border:3px dashed #B9B09C;background:#E1DBCE;border-radius:26px;text-align:center;font-weight:700;color:#655E52}
.endinner{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}
.endinner h2{font-family:'Fredoka','Nunito',sans-serif;font-weight:600}
.endbtn{display:inline-block;color:#fff;text-decoration:none;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;border-radius:999px;box-shadow:inset 0 2px 0 rgba(255,255,255,.3), inset 0 -4px 0 rgba(0,0,0,.2), 0 14px 24px -10px rgba(90,78,58,.5)}
.gearbtn{position:fixed;top:12px;right:12px;z-index:50;width:48px;height:48px;border-radius:999px;border:0;padding:0;color:#5C5041;cursor:pointer;display:flex;align-items:center;justify-content:center;line-height:0;background:#E9E5DD;box-shadow:5px 5px 10px rgba(133,122,102,.5), -5px -5px 10px rgba(255,255,255,.95)}
.gearbtn:active{transform:scale(.95)}
.settings-backdrop{position:fixed;inset:0;background:rgba(20,16,10,.45);z-index:60;display:none}
.settings-backdrop.open{display:block}
.settings-panel{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:61;width:min(420px,88vw);background:#E9E5DD;color:#35322C;border-radius:28px;padding:22px;box-shadow:10px 10px 24px rgba(0,0,0,.35);display:none}
.settings-panel.open{display:block}
.settings-panel h2{margin:0 0 4px;font-family:'Fredoka','Nunito',sans-serif;font-weight:600;font-size:22px}
.settings-panel h3{margin:18px 0 8px;font-size:14px;font-weight:800;text-transform:uppercase;letter-spacing:.08em;color:#655E52}
.seg{display:flex;gap:8px}
.seg button{flex:1;border:0;border-radius:999px;padding:11px 0;font-size:15px;font-weight:700;color:#5C5041;background:#DED8CB;cursor:pointer}
.seg button[aria-pressed="true"]{background:#6F6152;color:#fff}
.settings-close{position:absolute;top:12px;right:12px;width:36px;height:36px;border-radius:999px;border:0;background:#DED8CB;color:#5C5041;font-size:18px;cursor:pointer}
.upd-btn{border:0;border-radius:999px;padding:12px 18px;font-size:15px;font-weight:700;color:#fff;background:#6F6152;cursor:pointer}
.upd-status{font-size:14px;font-weight:600;color:#5C5041;line-height:1.45;margin:10px 0 0}
.upd-status a{color:#6F6152}
</style></head>
<body>
<div id="stage"></div>
<button class="edge" id="edgeL" aria-label="Ketuk tepi kiri untuk halaman sebelumnya"></button>
<button class="edge" id="edgeR" aria-label="Ketuk tepi kanan untuk halaman berikutnya"></button>
<a id="backbtn" href="shelf:" aria-label="Kembali ke rak buku"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 6.2C10.3 4.6 8.1 4 5.5 4c-.5 0-1 .1-1.5.2v13.1c.5-.1 1-.2 1.5-.2 2.6 0 4.8.6 6.5 2.2 1.7-1.6 3.9-2.2 6.5-2.2.5 0 1 .1 1.5.2V4.2c-.5-.1-1-.2-1.5-.2-2.6 0-4.8.6-6.5 2.2z"/><path d="M12 6.2v13.1"/></svg></a>
<button class="navbtn" id="prevBtn" aria-label="Halaman sebelumnya"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19 12H5"/><path d="m11 6-6 6 6 6"/></svg></button><div id="pageind"><span id="indlabel"></span><div class="progbar" id="progbar"><div id="prog"></div></div></div><button class="navbtn" id="nextBtn" aria-label="Halaman berikutnya"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="m13 6 6 6-6 6"/></svg></button>
<button class="gearbtn" id="gearBtn" aria-label="Pengaturan"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg></button>
<div class="settings-backdrop" id="settingsBackdrop"></div>
<div class="settings-panel" id="settingsPanel" role="dialog" aria-label="Pengaturan">
<button class="settings-close" id="settingsClose" aria-label="Tutup">&#10005;</button>
<h2>Pengaturan</h2>
<h3>Ukuran teks</h3>
<div class="seg" id="fontSeg">
<button data-size="kecil" aria-pressed="false">Kecil</button>
<button data-size="sedang" aria-pressed="true">Sedang</button>
<button data-size="besar" aria-pressed="false">Besar</button>
</div>
<h3>Pembaruan aplikasi</h3>
<p class="upd-status" id="updVersion"></p>
<button class="upd-btn" id="checkUpdBtn">Periksa update</button>
<p class="upd-status" id="updStatus"></p>
<div id="updFound" style="display:none;margin-top:10px">
<p class="upd-status" id="updFoundText" style="margin:0 0 10px"></p>
<button class="upd-btn" id="dlUpdBtn">Unduh &amp; pasang</button>
</div>
</div>
"""

private val READER_SCRIPT = """<script>
var st = styleInfo(BOOK.style);
var pal = st.palette;
var accent = st.accent;
// Neutral Neumorphism chrome (Space parity, READER_NEU): paper, ink,
// wash, callout, and deep are overridden with the one neutral system
// for every book. The melt gradient and the text zone both read
// pal.paper, so the illustration still melts seamlessly into the
// text — now onto the neutral paper instead of the style's cream.
pal = Object.assign({}, pal, {"paper": NEU.paper, "ink": NEU.ink, "wash": NEU.paper, "callout": NEU.accent, "calloutInk": NEU.calloutInk, "deep": NEU.deep});
accent = NEU.accent;
var pages = BOOK.pages || [];
var total = pages.length + 2;
var idx = 0;
var dir = "next";
var aspects = {};
var imgOk = {};
var tiers = {};
var TIERS = [
  {body: 25.5, fact: 22.5, heading: 43.5, gap: 26, imgPct: 64},
  {body: 23.5, fact: 21.5, heading: 40, gap: 24, imgPct: 61},
  {body: 21.5, fact: 20.5, heading: 36.5, gap: 22, imgPct: 58},
  {body: 19.5, fact: 19, heading: 33.5, gap: 20, imgPct: 54},
  {body: 17.5, fact: 18, heading: 30.5, gap: 18, imgPct: 50},
  {body: 16, fact: 17, heading: 28, gap: 16, imgPct: 46},
  {body: 15, fact: 15.5, heading: 25.5, gap: 14, imgPct: 42}
];
// Ukuran teks (pengaturan gear): Kecil/Sedang/Besar, disimpan di
// localStorage, diterapkan sebagai faktor skala pada TIERS (body,
// funFact, heading). Judul cover TIDAK ikut skala (coverHtml tidak
// memakai TIERS), jadi tetap besar dominan. Default Sedang = ukuran
// hasil perubahan font v1.9.
var FONT_SCALES = {"kecil": 0.85, "sedang": 1.0, "besar": 1.2};
var fontChoice = "sedang";
try { var savedFont = localStorage.getItem("bm_reader_font"); if (FONT_SCALES[savedFont]) fontChoice = savedFont; } catch (e) {}
function fontScale() { return FONT_SCALES[fontChoice] || 1.0; }
function scaledTier(t) { var s = fontScale(); return {body: t.body * s, fact: t.fact * s, heading: t.heading * s, gap: t.gap, imgPct: t.imgPct}; }
function syncFontButtons() { var b = document.querySelectorAll("#fontSeg button"); for (var i = 0; i < b.length; i++) b[i].setAttribute("aria-pressed", b[i].getAttribute("data-size") === fontChoice ? "true" : "false"); }
function setFontSize(choice) { if (!FONT_SCALES[choice]) return; fontChoice = choice; try { localStorage.setItem("bm_reader_font", choice); } catch (e) {} syncFontButtons(); render(false); }
function openSettings() { syncFontButtons(); var v = (typeof APP_VERSION !== "undefined" && APP_VERSION) ? APP_VERSION : "?"; var el = document.getElementById("updVersion"); if (el) el.textContent = "Versi terpasang: v" + v; document.getElementById("settingsPanel").classList.add("open"); document.getElementById("settingsBackdrop").classList.add("open"); }
function closeSettings() { document.getElementById("settingsPanel").classList.remove("open"); document.getElementById("settingsBackdrop").classList.remove("open"); }
function compareVer(a, b) { var pa = String(a).split("."), pb = String(b).split("."); for (var i = 0; i < Math.max(pa.length, pb.length); i++) { var na = parseInt(pa[i] || "0", 10) || 0, nb = parseInt(pb[i] || "0", 10) || 0; if (na !== nb) return na > nb ? 1 : -1; } return 0; }
function checkUpdate() {
  var st = document.getElementById("updStatus");
  var found = document.getElementById("updFound");
  if (found) found.style.display = "none";
  if (st) st.textContent = "Memeriksa…";
  fetch("https://api.github.com/repos/halobayu/buku-mini-fauna-anak/releases/latest")
    .then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(rel) {
      var tag = rel.tag_name || "";
      var ver = tag.replace(/^apk-v/i, "");
      var cur = (typeof APP_VERSION !== "undefined" && APP_VERSION) ? APP_VERSION : "0";
      if (!ver) { if (st) st.textContent = "Tidak bisa membaca versi rilis terbaru."; return; }
      if (compareVer(ver, cur) > 0) {
        var apk = null;
        var assets = rel.assets || [];
        for (var i = 0; i < assets.length; i++) { if (/\.apk$/i.test(assets[i].name || "")) { apk = assets[i]; break; } }
        var ft = document.getElementById("updFoundText");
        if (ft) ft.textContent = "Versi baru tersedia: v" + ver + " (terpasang v" + cur + ").";
        if (apk) {
          var dl = document.getElementById("dlUpdBtn");
          if (dl) dl.setAttribute("data-url", apk.browser_download_url);
          if (found) found.style.display = "block";
          if (st) st.textContent = "";
        } else {
          if (st) st.innerHTML = "Versi baru v" + ver + " tersedia, tapi rilisnya tidak membawa file APK. <a href=\"open:" + rel.html_url + "\">Buka halaman rilis</a>";
        }
      } else {
        if (st) st.textContent = "Sudah versi terbaru (v" + cur + ").";
      }
    })
    .catch(function() { if (st) st.textContent = "Gagal memeriksa update — periksa internet lalu coba lagi."; });
}
function isLandscape() { return window.innerWidth / Math.max(1, window.innerHeight) > 1.05; }
function S() {
  // Tablet: wider design canvas -> smaller scale -> type renders
  // relatively smaller and the illustration share grows (Space parity).
  var dw = isLandscape()
    ? (window.innerWidth >= 1500 ? 1420 : 1240)
    : (window.innerWidth >= 700 ? 1080 : 700);
  return window.innerWidth / dw;
}
function px(v) { return Math.round(v * S() * 10) / 10; }
function paperBg() {
  return "background-color:" + pal.paper
    + ";background-image:radial-gradient(rgba(120,90,40,0.07) 1.3px, transparent 1.4px), radial-gradient(rgba(120,90,40,0.05) 1px, transparent 1.1px), linear-gradient(155deg, rgba(255,255,255,0.55) 0%, rgba(255,255,255,0) 55%)"
    + ";background-size:22px 22px, 13px 13px, 100% 100%;background-position:0 0, 6px 9px, 0 0;";
}
function slideLabel() {
  if (idx === 0) return "Sampul";
  if (idx === total - 1) return "Tamat";
  return "Halaman " + idx + " dari " + pages.length;
}
var flipTimer = null;
function goNext() { if (idx < total - 1) { var prev = sheetHtml(); dir = "next"; idx++; render(true, prev); } }
function goPrev() { if (idx > 0) { var prev = sheetHtml(); dir = "prev"; idx--; render(true, prev); } }
function currentKey() {
  if (idx === 0) return "cover";
  if (idx === total - 1) return "";
  return "p" + (idx - 1);
}
function preload(src, key) {
  var im = new Image();
  im.onload = function() {
    imgOk[key] = true;
    if (im.naturalHeight > 0) aspects[key] = im.naturalWidth / im.naturalHeight;
    if (currentKey() === key) render(false);
  };
  im.onerror = function() {
    imgOk[key] = false;
    if (currentKey() === key) render(false);
  };
  im.src = src;
}
function initialTier(p) {
  var c = (p.body || "").length + (p.funFact || "").length;
  var t = c <= 340 ? 0 : c <= 480 ? 1 : c <= 620 ? 2 : 3;
  if (p.layout === "gambar-kiri" || p.layout === "gambar-kanan") t += 1;
  return Math.min(TIERS.length - 1, t);
}
function tierOf(pi, p) { return tiers[pi] !== undefined ? tiers[pi] : initialTier(p); }
function trimStr(s) {
  var a = 0, b = s.length;
  while (a < b) { var c = s.charAt(a); if (c === " " || c === "\n" || c === "\t" || c === "\r") a++; else break; }
  while (b > a) { var d = s.charAt(b - 1); if (d === " " || d === "\n" || d === "\t" || d === "\r") b--; else break; }
  return s.substring(a, b);
}
function splitAnchor(body) {
  var end = -1;
  for (var i = 0; i < body.length; i++) {
    var c = body.charAt(i);
    if (c === "." || c === "!" || c === "?") {
      var next = i + 1 >= body.length ? "" : body.charAt(i + 1);
      if (next === "" || next === " " || next === "\n") { end = i; break; }
    }
  }
  if (end < 0) return {anchor: body, cluster: ""};
  var anchor = trimStr(body.substring(0, end + 1));
  var cluster = trimStr(body.substring(end + 1));
  if (cluster.length < 40) return {anchor: body, cluster: ""};
  return {anchor: anchor, cluster: cluster};
}
function spreadHtml(p, pi, t) {
  var parts = splitAnchor(p.body || "");
  // The callout already carries a "💡 Tahukah Kamu?" tab, so strip the
  // same words when the JSON funFact itself starts with them — otherwise
  // the reader prints "Tahukah Kamu?" twice (tab + first words).
  var fact = (p.funFact || "").replace(/^\s*tahukah kamu\s*[!?.:;,]?\s*/i, "");
  var widths = [64, 38, 22];
  var rule = "";
  for (var i = 0; i < 3; i++) rule += '<i style="background:' + NEU.rule[i] + ';width:' + Math.max(10, px(widths[i])) + 'px"></i>';
  // The heading stands alone — no number badge beside it (v1.5,
  // Space parity): the page number lives only in the bottom bar's
  // "Halaman X dari 6" label + progress bar.
  var h = '<div class="spreadhead">'
    + '<h3 style="font-size:' + Math.max(16, px(t.heading)) + 'px;color:' + pal.ink + '">' + esc(p.heading) + '</h3></div>'
    + '<div class="rule">' + rule + '</div>'
    + '<p class="anchor" style="font-size:' + Math.max(13, px(t.body + 4.5)) + 'px;color:' + pal.ink
    + ';background:' + pal.wash
    + ';padding:' + Math.max(8, px(16)) + 'px ' + Math.max(10, px(22)) + 'px">' + esc(parts.anchor) + '</p>';
  if (parts.cluster) {
    h += '<p class="cluster" style="font-size:' + Math.max(12, px(t.body)) + 'px;color:' + pal.ink + '">' + esc(parts.cluster) + '</p>';
  }
  h += '<div class="callout" style="background:' + pal.paper
    + ';padding:' + Math.max(14, px(30)) + 'px ' + Math.max(12, px(30)) + 'px ' + Math.max(10, px(22)) + 'px'
    + ';margin-top:' + Math.max(6, px(12)) + 'px">'
    + '<span class="callouttab" style="background:' + pal.callout + ';color:' + pal.calloutInk
    + ';top:' + (-Math.max(9, px(17))) + 'px;font-size:' + Math.max(10, px(17)) + 'px;padding:'
    + Math.max(2, px(3)) + 'px ' + Math.max(8, px(14)) + 'px">&#128161; Tahukah Kamu?</span>'
    + '<p style="font-size:' + Math.max(12, px(t.fact)) + 'px;color:' + pal.ink + '">' + esc(fact) + '</p></div>';
  return h;
}
// Portrait, Space parity v1.8 (overlay penuh): the picture fills the
// whole sheet and the text sits ON it directly — white bold text with
// a dark text-shadow, no bubble, no paper panel anywhere — exactly
// like the Space reading page with layout "overlay" (all Hiu + Dino
// pages are set to overlay in the Space). A black gradient scrim
// sits behind the text so the white text stays readable.
// The heading is a white overlay title at the top (except when the
// text zone itself is at the top, where it joins the text block).
// The whole body is one white block; the fun fact follows as white
// text with a plain "Tahukah Kamu?" label. The text wrapper keeps
// id="textzone" (with no background of its own) so fitCheck() can
// still shrink the type tier when the text overflows.
// Placement is forward-compatible with the Space: when the book JSON
// carries per-page `textZone` ("atas"/"bawah"/"tengah" + optional
// "-kiri"/"-kanan"), `separatorOpacity` (0-100) or `layout`, the
// reader uses them; otherwise it falls back to the overlay default
// (bawah, 55%). Note the JSON value "gambar-penuh" is the v18
// mapping of the Space "overlay" layout, so it renders as overlay.
// (The book JSON today carries none of these per-page fields, so
// every portrait page uses the default until Bayu's Space placement
// is carried into the JSON, e.g. content v19.)
function portraitHtml(p, pi, t) {
  // Same de-dup as spreadHtml: the label already says
  // "Tahukah Kamu?", so strip those words from the JSON fact.
  var fact = (p.funFact || "").replace(/^\s*tahukah kamu\s*[!?.:;,]?\s*/i, "");
  // Per-page placement from the JSON when present, else Space defaults.
  var zone = String(p.textZone || "bawah").toLowerCase();
  var zp = zone.split("-");
  var vert = zp[0] === "atas" ? "atas" : zp[0] === "tengah" ? "tengah" : "bawah";
  var horiz = zp[1] === "kiri" ? "kiri" : zp[1] === "kanan" ? "kanan" : "tengah";
  var sep = (p.separatorOpacity === undefined || p.separatorOpacity === null)
    ? 55 : Math.max(0, Math.min(100, p.separatorOpacity));
  var a = sep / 100;
  var aMid = Math.round(a * 0.5 * 100) / 100;
  var align = horiz === "kiri" ? "flex-start" : horiz === "kanan" ? "flex-end" : "center";
  var talign = horiz === "kiri" ? "left" : horiz === "kanan" ? "right" : "center";
  var head = "";
  var headInZone = "";
  if (p.heading) {
    if (vert === "atas") {
      // The zone itself is at the top: the heading joins the text
      // block instead of floating separately over it.
      headInZone = '<p class="overlaytext" style="margin:0;font-size:' + Math.max(18, px(t.heading)) + 'px;font-weight:800;background:rgba(255,255,255,0.75);color:#35322C;border-radius:' + Math.max(10, px(16)) + 'px;padding:' + Math.max(6, px(10)) + 'px ' + Math.max(10, px(18)) + 'px;text-shadow:none">' + esc(p.heading) + '</p>';
    } else {
      head = '<div class="pagetitle"><h3 style="display:inline-block;font-size:' + Math.max(16, px(t.heading)) + 'px;background:rgba(255,255,255,0.75);color:#35322C;border-radius:' + Math.max(10, px(16)) + 'px;padding:' + Math.max(6, px(10)) + 'px ' + Math.max(10, px(18)) + 'px;text-shadow:none">' + esc(p.heading) + '</h3></div>';
    }
  }
  var bodyT = '<p class="overlaytext" style="margin:0;text-align:' + talign + ';font-size:' + Math.max(13, px(t.body + 4.5)) + 'px">' + esc(p.body || "") + '</p>';
  var factT = "";
  if (fact) {
    factT = '<p class="overlaytext" style="margin:0;text-align:' + talign + ';font-size:' + Math.max(12, px(t.fact)) + 'px;font-weight:600;line-height:1.45;background:rgba(255,255,255,0.75);color:#35322C;border-radius:' + Math.max(10, px(18)) + 'px;padding:' + Math.max(8, px(12)) + 'px ' + Math.max(10, px(18)) + 'px;text-shadow:none">'
      + '<span style="font-weight:800">&#128161; Tahukah Kamu?</span> ' + esc(fact) + '</p>';
  }
  // The scrim follows the zone's side: bottom gradient for "bawah",
  // top gradient for "atas", full dark overlay for "tengah". A thin
  // top scrim stays for the heading whenever the zone is not "atas".
  var scrim = "";
  var topScrim = '<div class="topscrim" style="background:linear-gradient(to bottom, rgba(0,0,0,0.38) 0%, rgba(0,0,0,0.12) 55%, rgba(0,0,0,0) 100%)"></div>';
  var tzPos = "";
  var tzPad = "";
  if (vert === "atas") {
    scrim = '<div class="topscrim" style="height:66%;background:linear-gradient(to bottom, rgba(0,0,0,' + a + ') 0%, rgba(0,0,0,' + aMid + ') 45%, rgba(0,0,0,0) 100%)"></div>';
    tzPos = "top:0;";
    tzPad = "padding:" + px(20) + "px " + px(48) + "px " + px(16) + "px;";
  } else if (vert === "tengah") {
    scrim = '<div style="position:absolute;inset:0;z-index:5;pointer-events:none;background:rgba(0,0,0,' + a + ')"></div>';
    tzPos = "top:0;bottom:0;";
    tzPad = "padding:" + px(16) + "px " + px(48) + "px " + px(16) + "px;";
  } else {
    scrim = '<div class="botscrim" style="background:linear-gradient(to top, rgba(0,0,0,' + a + ') 0%, rgba(0,0,0,' + aMid + ') 45%, rgba(0,0,0,0) 100%)"></div>';
    tzPos = "bottom:0;";
    tzPad = "padding:" + px(16) + "px " + px(48) + "px " + (px(44) + 76) + "px;";
  }
  var tzJustify = vert === "atas" ? "flex-start" : vert === "tengah" ? "center" : "flex-end";
  return '<div class="sheet" style="background:' + pal.paper + ';color:' + pal.ink + '">'
    + '<img class="whole" src="' + p.image + '" alt=""/>' + scrim + (vert === "atas" ? "" : topScrim) + head
    + '<div id="textzone" class="textzone" style="position:absolute;left:0;right:0;' + tzPos + 'z-index:10;max-height:62%;justify-content:' + tzJustify + ';align-items:' + align + ';gap:' + px(t.gap) + 'px;' + tzPad + '">'
    + headInZone + bodyT + factT + '</div>'
    + '</div>';
}
function coverHtml() {
  if (imgOk["cover"] === false) {
    return '<div class="sheet" style="' + paperBg() + 'color:' + pal.ink + '">'
      + '<div class="composed" style="padding:0 ' + px(70) + 'px">'
      + '<p class="kicker" style="color:' + accent + ';font-size:' + Math.max(12, px(26)) + 'px">Buku Mini Fauna</p>'
      + '<h2 style="font-size:' + Math.max(24, px(110)) + 'px;font-weight:800;line-height:1.08;margin:' + px(18) + 'px 0 0">' + esc(BOOK.title) + '</h2>'
      + '<p style="font-size:' + Math.max(14, px(30)) + 'px;font-weight:600;opacity:.7;margin:' + px(14) + 'px 0 0">' + esc(BOOK.subtitle) + '</p>'
      + '</div></div>';
  }
  return '<div class="sheet" style="background:' + pal.paper + '">'
    + '<img class="coverimg" src="' + BOOK.cover + '" alt=""/>'
    + '<div class="coverscrim" style="background:linear-gradient(to bottom, ' + pal.paper + 'F2 0%, ' + pal.paper + 'D9 26%, ' + pal.paper + '94 50%, ' + pal.paper + '47 72%, ' + pal.paper + '00 100%)"></div>'
    + '<div class="covertitle" style="padding:0 ' + px(56) + 'px">'
    + '<h2 style="font-size:' + Math.max(20, px(92)) + 'px;font-weight:800;color:' + pal.ink + '">' + esc(BOOK.title) + '</h2>'
    + '<p style="font-size:' + Math.max(12, px(25)) + 'px;color:' + pal.ink + ';opacity:.75">' + esc(BOOK.subtitle) + '</p>'
    + '</div>'
    + '</div>';
}
function endHtml() {
  return '<div class="sheet" style="' + paperBg() + 'color:' + pal.ink + '">'
    + '<div class="endinner" style="padding:0 ' + px(70) + 'px">'
    + '<p style="font-size:' + Math.max(40, px(92)) + 'px;margin:0">&#127881;</p>'
    + '<p style="color:' + accent + ';font-weight:700;text-transform:uppercase;letter-spacing:.3em;font-size:' + Math.max(13, px(30)) + 'px;margin:' + px(20) + 'px 0 0">— Tamat —</p>'
    + '<h2 style="font-size:' + Math.max(24, px(58)) + 'px;font-weight:800;line-height:1.15;margin:' + px(14) + 'px 0 0">Selesai Dibaca!</h2>'
    + '<p style="font-size:' + Math.max(14, px(29)) + 'px;font-weight:600;line-height:1.5;opacity:.75;max-width:' + px(560) + 'px;margin:' + px(14) + 'px 0 0">Hebat, kamu sudah membaca sampai akhir. Hewan apa yang mau kamu kenal berikutnya?</p>'
    + '<a class="endbtn" href="shelf:" style="background:' + accent + ';font-size:' + Math.max(14, px(26)) + 'px;padding:' + Math.max(10, px(14)) + 'px ' + Math.max(18, px(34)) + 'px;margin-top:' + px(22) + 'px">Kembali ke Rak Buku</a>'
    + '</div></div>';
}
function pageHtml(p, pi) {
  var t = scaledTier(TIERS[tierOf(pi, p)]);
  var key = "p" + pi;
  // No folio printed on the sheet (v1.5, Space parity) — the page
  // number appears once, in the bottom bar's label + progress bar.
  if (imgOk[key] === false) {
    return '<div class="sheet" style="' + paperBg() + 'color:' + pal.ink + '">'
      + '<div style="position:absolute;inset:0;display:flex;flex-direction:column;padding:' + px(44) + 'px ' + px(54) + 'px">'
      + '<div class="missingbox" style="padding:' + px(36) + 'px ' + px(20) + 'px;font-size:' + Math.max(13, px(24)) + 'px">Halaman ini belum ada gambarnya</div>'
      + '<div id="textzone" class="textzone" style="flex:1;justify-content:flex-start;gap:' + px(t.gap) + 'px;margin-top:' + px(30) + 'px">' + spreadHtml(p, pi, t) + '</div>'
      + '</div></div>';
  }
  // The storyboard's sceneAction is a production note for the
  // illustrator only — it is never printed on the page (Space
  // parity: it read as the image prompt stuck on the picture).
  if (isLandscape()) {
    // Row ratio (Space parity): the 4:5 picture fills its column's
    // full height (cover-fit biased to the top of the frame, where
    // the animal lives) and melts sideways into the paper — no
    // blurred ambience anywhere.
    var imgPct = Math.min(58, t.imgPct - 6 + (p.layout === "gambar-penuh" ? 4 : 0));
    var right = p.layout === "gambar-kanan";
    var meltStyle = right
      ? "left:0;background:linear-gradient(to left, " + pal.paper + "00 0%, " + pal.paper + "2E 42%, " + pal.paper + "85 74%, " + pal.paper + " 100%)"
      : "right:0;background:linear-gradient(to right, " + pal.paper + "00 0%, " + pal.paper + "2E 42%, " + pal.paper + "85 74%, " + pal.paper + " 100%)";
    return '<div class="sheet" style="background:' + pal.paper + ';color:' + pal.ink + ';display:flex;flex-direction:' + (right ? "row-reverse" : "row") + '">'
      + '<div class="imgzone" style="width:' + imgPct + '%;height:100%">'
      + '<img class="whole" src="' + p.image + '" alt=""/>'
      + '<div class="melts" style="' + meltStyle + '"></div></div>'
      + '<div id="textzone" class="textzone" style="flex:1;height:100%;gap:' + px(t.gap) + 'px;padding:' + px(36) + 'px ' + px(44) + 'px ' + (px(36) + 76) + 'px;' + paperBg() + '">'
      + spreadHtml(p, pi, t) + '</div>'
      + '</div>';
  }
  return portraitHtml(p, pi, t);
}
function sheetHtml() {
  if (idx === 0) return coverHtml();
  if (idx === total - 1) return endHtml();
  return pageHtml(pages[idx - 1], idx - 1);
}
function fitCheck() {
  if (idx === 0 || idx === total - 1) return;
  var tz = document.querySelector('[data-active="1"] #textzone') || document.getElementById("textzone");
  if (!tz) return;
  if (tz.scrollHeight > tz.clientHeight + 4) {
    var pi = idx - 1;
    var t = tierOf(pi, pages[pi]);
    if (t < TIERS.length - 1) { tiers[pi] = t + 1; render(false); }
  }
}
function leafChrome() {
  return '<div class="bindshade"></div><div class="foreedge"></div><div class="bottomstack"></div>';
}
function settleLeaf(stage, html, isCoverOpen) {
  stage.innerHTML = "";
  var leaf = document.createElement("div");
  leaf.id = "leaf";
  leaf.setAttribute("data-active", "1");
  if (isCoverOpen) leaf.className = "cover-open";
  leaf.innerHTML = html + leafChrome();
  stage.appendChild(leaf);
}
function render(animate, prevContent) {
  document.body.style.background = "radial-gradient(120% 90% at 50% 0%, " + pal.deep + " 0%, #14120E 130%)";
  var stage = document.getElementById("stage");
  var newContent = sheetHtml();
  if (flipTimer) { clearTimeout(flipTimer); flipTimer = null; }

  if (animate && prevContent) {
    stage.innerHTML = "";
    var under = document.createElement("div");
    under.id = "underleaf";
    var turn = document.createElement("div");
    turn.id = "turnleaf";

    if (dir === "next") {
      under.setAttribute("data-active", "1");
      under.innerHTML = newContent + leafChrome() + '<div class="under-shade shade-next"></div>';
      turn.className = "turn-next";
      turn.innerHTML = '<div class="face-front">' + prevContent + leafChrome() + '<div class="fold-sheen sheen-anim"></div></div>'
        + '<div class="face-back" style="' + paperBg() + '"></div>';
    } else {
      under.innerHTML = prevContent + leafChrome() + '<div class="under-shade shade-prev"></div>';
      turn.setAttribute("data-active", "1");
      turn.className = "turn-prev";
      turn.innerHTML = '<div class="face-front">' + newContent + leafChrome() + '<div class="fold-sheen sheen-anim"></div></div>'
        + '<div class="face-back" style="' + paperBg() + '"></div>';
    }

    stage.appendChild(under);
    stage.appendChild(turn);
    flipTimer = setTimeout(function() {
      flipTimer = null;
      settleLeaf(stage, newContent, false);
    }, 640);
  } else {
    settleLeaf(stage, newContent, idx === 0 && animate !== false);
  }

  document.getElementById("indlabel").textContent = slideLabel();
  document.getElementById("indlabel").style.color = pal.ink;
  document.getElementById("indlabel").style.textShadow = "0 1px 6px " + pal.paper + ", 0 0 2px " + pal.paper;
  document.getElementById("backbtn").style.background = pal.paper;
  document.getElementById("backbtn").style.color = NEU.accentInk;
  var prog = document.getElementById("prog");
  prog.style.width = ((idx + 1) / total * 100) + "%";
  prog.style.background = accent;
  document.getElementById("progbar").style.background = pal.ink + "2E";
  document.getElementById("progbar").style.boxShadow = "0 0 0 1px " + pal.paper + "59, inset 0 1px 2px rgba(0,0,0,.18)";
  document.getElementById("prevBtn").style.background = pal.paper;
  document.getElementById("prevBtn").style.color = NEU.accentInk;
  document.getElementById("nextBtn").style.background = pal.paper;
  document.getElementById("nextBtn").style.color = NEU.accentInk;
  document.getElementById("prevBtn").disabled = idx === 0;
  document.getElementById("nextBtn").disabled = idx >= total - 1;
  document.getElementById("edgeL").disabled = idx === 0;
  document.getElementById("edgeR").disabled = idx >= total - 1;
  fitCheck();
}
document.getElementById("prevBtn").addEventListener("click", goPrev);
document.getElementById("nextBtn").addEventListener("click", goNext);
document.getElementById("edgeL").addEventListener("click", goPrev);
document.getElementById("edgeR").addEventListener("click", goNext);
var touchX = null;
document.addEventListener("touchstart", function(e) { touchX = e.touches[0] ? e.touches[0].clientX : null; });
document.addEventListener("touchend", function(e) {
  if (touchX === null) return;
  var end = e.changedTouches[0] ? e.changedTouches[0].clientX : touchX;
  if (touchX - end > 48) goNext(); else if (end - touchX > 48) goPrev();
  touchX = null;
});
document.addEventListener("keydown", function(e) {
  if (e.key === "ArrowRight") goNext();
  if (e.key === "ArrowLeft") goPrev();
});
document.getElementById("gearBtn").addEventListener("click", openSettings);
document.getElementById("settingsClose").addEventListener("click", closeSettings);
document.getElementById("settingsBackdrop").addEventListener("click", closeSettings);
document.getElementById("checkUpdBtn").addEventListener("click", checkUpdate);
document.getElementById("dlUpdBtn").addEventListener("click", function() { var u = this.getAttribute("data-url"); if (u) location.href = "update:" + encodeURIComponent(u); });
var segBtns = document.querySelectorAll("#fontSeg button");
for (var i = 0; i < segBtns.length; i++) segBtns[i].addEventListener("click", function() { setFontSize(this.getAttribute("data-size")); });
var resizeTimer = null;
window.addEventListener("resize", function() {
  if (resizeTimer) clearTimeout(resizeTimer);
  resizeTimer = setTimeout(function() { render(false); }, 150);
});
preload(BOOK.cover, "cover");
for (var pi = 0; pi < pages.length; pi++) preload(pages[pi].image, "p" + pi);
render(true);
</script>"""
