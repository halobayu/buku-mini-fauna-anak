package com.bukufauna.reader

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// ============================================================
// GANTI baris di bawah ini dengan username GitHub kamu.
// Repo: https://github.com/halobayu/buku-mini-fauna-anak
// ============================================================
const val GITHUB_USER = "halobayu"
const val REPO = "buku-mini-fauna-anak"
const val RAW = "https://raw.githubusercontent.com/" + GITHUB_USER + "/" + REPO + "/main/content"

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.webViewClient = Client()
        setContentView(web)
        showShelf()
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn.inputStream.bufferedReader().readText()
    }

    // Rak buku: membaca manifest.json terbaru dari GitHub setiap dibuka.
    private fun showShelf() {
        web.loadDataWithBaseURL(RAW + "/", page("Memuat rak buku..."), "text/html", "utf-8", null)
        thread {
            try {
                val manifest = JSONObject(fetchText(RAW + "/manifest.json"))
                val books = manifest.getJSONArray("books")
                val sb = StringBuilder()
                sb.append("<h1>Rak Buku Fauna</h1>")
                sb.append("<p class='dim'>Versi konten: ").append(manifest.optInt("contentVersion")).append("</p>")
                for (i in 0 until books.length()) {
                    val b = books.getJSONObject(i)
                    sb.append("<a class='card' href='book:").append(b.getString("file")).append("'>")
                    sb.append("<img src='").append(b.getString("cover")).append("'/>")
                    sb.append("<span>").append(b.getString("title")).append("</span></a>")
                }
                val html = page(sb.toString())
                runOnUiThread { web.loadDataWithBaseURL(RAW + "/", html, "text/html", "utf-8", null) }
            } catch (e: Exception) {
                runOnUiThread {
                    web.loadDataWithBaseURL(null, page("<h1>Ups!</h1><p>Tidak bisa memuat buku. Periksa internet lalu buka lagi aplikasinya.</p>"), "text/html", "utf-8", null)
                }
            }
        }
    }

    // Satu buku: halaman ditampilkan bertumpuk, tinggal scroll — nyaman di tablet.
    private fun showBook(file: String) {
        thread {
            try {
                val book = JSONObject(fetchText(RAW + "/" + file))
                val pages = book.getJSONArray("pages")
                val sb = StringBuilder()
                sb.append("<p><a href='shelf:'>&larr; Rak Buku</a></p>")
                sb.append("<img class='cover' src='").append(book.getString("cover")).append("'/>")
                sb.append("<h1>").append(book.getString("title")).append("</h1>")
                sb.append("<p class='dim'>").append(book.getString("subtitle")).append("</p>")
                for (i in 0 until pages.length()) {
                    val p = pages.getJSONObject(i)
                    // Setiap halaman dirender sesuai layout-nya: teks dan
                    // gambar berbagi halaman seperti di buku beneran.
                    val layout = p.optString("layout", "gambar-atas")
                    val img = "<img src='" + p.getString("image") + "'/>"
                    val txt = StringBuilder()
                    txt.append("<h2>").append(i + 1).append(". ").append(p.getString("heading")).append("</h2>")
                    txt.append("<p>").append(p.getString("body")).append("</p>")
                    txt.append("<div class='fact'><b>Tahukah Kamu?</b><br/>").append(p.getString("funFact")).append("</div>")
                    when (layout) {
                        "gambar-kiri" -> sb.append("<section class='lr'><div class='colimg'>").append(img).append("</div><div class='coltxt'>").append(txt).append("</div></section>")
                        "gambar-kanan" -> sb.append("<section class='lr rev'><div class='colimg'>").append(img).append("</div><div class='coltxt'>").append(txt).append("</div></section>")
                        "gambar-penuh" -> sb.append("<section class='full'>").append(img).append("<div class='panel'>").append(txt).append("</div></section>")
                        else -> sb.append("<section>").append(img).append(txt).append("</section>")
                    }
                }
                sb.append("<h2>Selesai!</h2>")
                val html = page(sb.toString())
                runOnUiThread { web.loadDataWithBaseURL(RAW + "/", html, "text/html", "utf-8", null) }
            } catch (e: Exception) {
                runOnUiThread { showShelf() }
            }
        }
    }

    override fun onBackPressed() {
        showShelf()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (url.startsWith("book:")) { showBook(url.removePrefix("book:")); return true }
            if (url.startsWith("shelf:")) { showShelf(); return true }
            return false
        }
    }

    private fun page(body: String): String {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>body{font-family:sans-serif;background:#FBF3E4;color:#2E2A23;margin:16px}" +
            "h1{font-size:28px} .dim{color:#7A6F5C} .card{display:block;background:#FFFAF0;border-radius:16px;padding:12px;margin:12px 0;text-decoration:none;color:#2E2A23}" +
            ".card img{width:100%;border-radius:12px} .card span{font-size:20px;font-weight:bold}" +
            "img{max-width:100%;border-radius:16px} .cover{max-height:420px} section{background:#FFFAF0;border-radius:16px;padding:14px;margin:14px 0}" +
            ".lr{display:flex;gap:12px;align-items:flex-start} .lr .colimg{flex:0 0 42%} .lr .colimg img{width:100%;height:auto;display:block} .lr .coltxt{flex:1;min-width:0} .rev{flex-direction:row-reverse}" +
            ".full{padding:0;overflow:hidden} .full>img{width:100%;border-radius:0;display:block} .full .panel{padding:2px 14px 14px}" +
            ".fact{background:#FBEFD2;border-radius:12px;padding:10px;margin-top:10px} a{color:#14665A;font-weight:bold}</style></head><body>" +
            body + "</body></html>"
    }
}
