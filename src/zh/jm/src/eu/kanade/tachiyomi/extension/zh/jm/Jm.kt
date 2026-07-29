package eu.kanade.tachiyomi.extension.zh.jm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import rx.Observable
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class Jm : HttpSource() {

    override val name = "禁漫天堂"
    override val baseUrl = "https://${apiDomains[0]}"
    override val lang = "zh"
    override val supportsLatest = true

    companion object {
        const val JM_VERSION = "2.0.16"
        const val JM_PKG_NAME = "com.example.app"
        const val JM_AUTH_KEY = "18comicAPPContent"
        const val JM_SECRET = "185Hcomic3PAPP7R"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"

        val FALLBACK_SERVERS = arrayOf(
            "www.cdntwice.org",
            "www.cdnsha.org",
            "www.cdnaspa.cc",
            "www.cdnntr.cc",
        )

        var IMAGE_URL = "https://cdn-msp.jmapinodeudzn.net"
        var apiDomains: Array<String> = FALLBACK_SERVERS
        var dailyCheckInInProgress = false
    }

    override val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val orig = chain.request()
            val newReq = orig.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", JM_PKG_NAME)
                .build()
            chain.proceed(newReq)
        }
        .addInterceptor(JmImageInterceptor())
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    class JmImageInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            if (!url.contains("/media/photos/")) return chain.proceed(request)
            val response = chain.proceed(request)
            if (!response.isSuccessful) return response
            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.startsWith("image/")) return response
            val path = request.url.encodedPath
            val parts = path.split("/")
            if (parts.size < 3) return response
            val pictureName = parts.last()
            val epIdStr = parts[parts.size - 2]
            val epId = epIdStr.toIntOrNull() ?: return response
            val num = calculateNum(epId, pictureName)
            if (num <= 1) return response
            if (pictureName.endsWith(".gif", ignoreCase = true)) return response
            val bytes = response.body?.bytes() ?: return response
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return response
            val modified = scrambleImage(bitmap, num)
            val outputStream = java.io.ByteArrayOutputStream()
            modified.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            modified.recycle()
            bitmap.recycle()
            val newBytes = outputStream.toByteArray()
            return response.newBuilder()
                .body(newBytes.toResponseBody(contentType.toMediaTypeOrNull()))
                .build()
        }

        private fun calculateNum(epId: Int, pictureName: String): Int {
            val scrambleId = 220980
            return when {
                epId < scrambleId -> 0
                epId < 268850 -> 10
                epId > 421926 -> {
                    val hash = md5("$epId$pictureName".toByteArray(Charsets.UTF_8))
                    (hexEncode(hash).last().code % 8) * 2 + 2
                }
                else -> {
                    val hash = md5("$epId$pictureName".toByteArray(Charsets.UTF_8))
                    (hexEncode(hash).last().code % 10) * 2 + 2
                }
            }
        }

        private fun md5(input: ByteArray): ByteArray {
            return java.security.MessageDigest.getInstance("MD5").digest(input)
        }

        private fun hexEncode(bytes: ByteArray): String {
            val chars = "0123456789abcdef".toCharArray()
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(chars[(b.toInt() shr 4) and 0xf])
                sb.append(chars[b.toInt() and 0xf])
            }
            return sb.toString()
        }

        private fun scrambleImage(image: Bitmap, num: Int): Bitmap {
            val height = image.height
            val width = image.width
            val blockSize = height / num
            val remainder = height % num
            val blocks = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until num) {
                val start = i * blockSize
                val end = start + blockSize + if (i != num - 1) 0 else remainder
                blocks.add(Pair(start, end))
            }
            val result = Bitmap.createBitmap(width, height, image.config ?: Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            var y = 0
            for (i in num - 1 downTo 0) {
                val block = blocks[i]
                val currentHeight = block.second - block.first
                val src = Bitmap.createBitmap(image, 0, block.first, width, currentHeight)
                canvas.drawBitmap(src, 0f, y.toFloat(), null)
                src.recycle()
                y += currentHeight
            }
            return result
        }
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive",
        "Origin" to "https://localhost",
        "Referer" to "https://localhost/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "X-Requested-With" to JM_PKG_NAME,
    )

    private fun buildApiHeaders(time: Long): Map<String, String> {
        val input = "${time}${JM_AUTH_KEY}".toByteArray(Charsets.UTF_8)
        val token = hexEncode(md5(input))
        return baseHeaders() + mapOf(
            "Authorization" to "Bearer",
            "Sec-Fetch-Storage-Access" to "active",
            "token" to token,
            "tokenparam" to "${time},${JM_VERSION}",
            "User-Agent" to USER_AGENT,
        )
    }

    private fun hexEncode(bytes: ByteArray): String {
        val chars = "0123456789abcdef".toCharArray()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(chars[(b.toInt() shr 4) and 0xf])
            sb.append(chars[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    private fun md5(input: ByteArray): ByteArray {
        return java.security.MessageDigest.getInstance("MD5").digest(input)
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val aesKey = when {
            key.size == 16 || key.size == 24 || key.size == 32 -> key
            else -> md5(key).copyOf(16)
        }
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"))
        return cipher.doFinal(data)
    }

    private fun convertData(input: String, secret: String): String {
        val key = hexEncode(md5(secret.toByteArray(Charsets.UTF_8))).toByteArray(Charsets.UTF_8)
        val data = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
        val decrypted = aesDecrypt(data, key)
        val res = String(decrypted, Charsets.UTF_8)
        var start = 0
        while (start < res.length && res[start] != '{' && res[start] != '[') start++
        var end = res.length - 1
        while (end > start && res[end] != '}' && res[end] != ']') end--
        if (start > end) throw Exception("Cannot find JSON")
        return res.substring(start, end + 1)
    }

    private fun apiGet(path: String): String {
        val time = System.currentTimeMillis() / 1000
        val url = "https://${apiDomains[0]}$path"
        val request = GET(url, headers = buildApiHeaders(time))
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            val body = response.body?.string() ?: ""
            if (code == 401) {
                throw Exception(JSONObject(body).optString("errorMsg", "请先登录会员"))
            }
            throw Exception("HTTP $code")
        }
        val body = response.body?.string() ?: throw Exception("Empty response")
        val json = JSONObject(body)
        val data = json.optString("data", "")
        if (data.isEmpty()) throw Exception("Invalid Data")
        return convertData(data, "${time}${JM_SECRET}")
    }

    private fun apiPost(path: String, bodyParams: String): String {
        val time = System.currentTimeMillis() / 1000
        val url = "https://${apiDomains[0]}$path"
        val headers = buildApiHeaders(time) + mapOf("Content-Type" to "application/x-www-form-urlencoded")
        val request = POST(url, headers = headers, body = bodyParams.toByteArray())
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            val body = response.body?.string() ?: ""
            if (code == 401) {
                throw Exception(JSONObject(body).optString("errorMsg", "请先登录会员"))
            }
            throw Exception("HTTP $code")
        }
        val body = response.body?.string() ?: throw Exception("Empty response")
        val json = JSONObject(body)
        val data = json.optString("data", "")
        if (data.isEmpty()) throw Exception("Invalid Data")
        return convertData(data, "${time}${JM_SECRET}")
    }

    private fun getCoverUrl(id: String) = "${IMAGE_URL}/media/albums/${id}_3x4.jpg"
    private fun getImageUrl(epId: String, imageName: String) = "${IMAGE_URL}/media/photos/${epId}/${imageName}"
    private fun getAvatarUrl(imageName: String) = "${IMAGE_URL}/media/users/${imageName}"

    private fun extractId(url: String): String {
        var id = url.substringAfter("id=")
        if (id.startsWith("jm")) id = id.substring(2)
        return id
    }

    private fun parseComic(json: JSONObject): SManga {
        val id = json.getString("id")
        return SManga.create().apply {
            this.url = "/album?id=$id"
            this.title = json.optString("name", "Unknown")
            this.author = json.optString("author", "")
            this.description = json.optString("description", "")
            this.thumbnail_url = getCoverUrl(id)
            val tags = mutableListOf<String>()
            json.optJSONObject("category")?.optString("title", "")?.let { if (it.isNotEmpty()) tags.add(it) }
            json.optJSONObject("category_sub")?.optString("title", "")?.let { if (it.isNotEmpty()) tags.add(it) }
            this.genre = tags.joinToString(", ")
            this.initialized = true
        }
    }

    override fun popularManga(page: Int): MangasPage {
        val decrypted = apiGet("/promote?page=0")
        val sections = JSONArray(decrypted)
        val allComics = mutableListOf<SManga>()
        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            val type = section.optString("type", "")
            if (type == "library" || type == "novels") continue
            val content = section.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                allComics.add(parseComic(content.getJSONObject(j)))
            }
        }
        return MangasPage(allComics.distinctBy { it.url }, false)
    }

    override fun searchManga(query: String, page: Int, filters: FilterList): MangasPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("%20", "+")
        val decrypted = apiGet("/search?search_query=$encoded&o=mr&page=$page")
        val json = JSONObject(decrypted)
        val total = json.getLong("total")
        val maxPage = Math.ceil(total / 80.0).toInt()
        val content = json.getJSONArray("content")
        val comics = mutableListOf<SManga>()
        for (i in 0 until content.length()) {
            comics.add(parseComic(content.getJSONObject(i)))
        }
        return MangasPage(comics, page < maxPage)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val id = extractId(manga.url)
            val decrypted = apiGet("/album?id=$id")
            val json = JSONObject(decrypted)
            parseMangaDetails(json, id)
        }
    }

    private fun parseMangaDetails(json: JSONObject, id: String): SManga {
        val name = json.optString("name", "Unknown")
        val desc = json.optString("description", "")
        val authorArr = json.optJSONArray("author")
        val author = if (authorArr != null) (0 until authorArr.length()).joinToString(", ") { authorArr.getString(it) } else ""
        val tagsArr = json.optJSONArray("tags")
        val tags = if (tagsArr != null) (0 until tagsArr.length()).joinToString(", ") { tagsArr.getString(it) } else ""
        val views = json.optString("total_views", "")
        val likes = json.optString("likes", "0")
        val timestamp = json.optLong("addtime", 0L)
        val updateDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp * 1000))
        return SManga.create().apply {
            this.url = "/album?id=$id"
            this.title = name
            this.author = author
            this.description = buildString {
                append(desc)
                append("\n\n📅 更新: $updateDate")
                if (views.isNotEmpty()) append("\n👁 浏览: $views")
                append("\n❤️ 点赞: $likes")
                if (tags.isNotEmpty()) append("\n🏷 标签: $tags")
            }
            this.thumbnail_url = getCoverUrl(id)
            this.genre = tags
            this.status = SManga.COMPLETED
            this.initialized = true
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val id = extractId(manga.url)
            val decrypted = apiGet("/album?id=$id")
            val json = JSONObject(decrypted)
            val seriesArr = json.optJSONArray("series")
            if (seriesArr == null || seriesArr.length() == 0) {
                return@fromCallable listOf(SChapter.create().apply {
                    this.url = "/chapter?id=$id"
                    this.name = "第1話"
                    this.chapter_number = 1f
                })
            }
            val seriesList = mutableListOf<JSONObject>()
            for (i in 0 until seriesArr.length()) seriesList.add(seriesArr.getJSONObject(i))
            seriesList.sortBy { it.optLong("sort", 0L) }
            seriesList.mapIndexed { index, obj ->
                val cid = obj.getString("id")
                var title = obj.optString("name", "")
                if (title.isBlank()) title = "第${obj.optString("sort", (index + 1).toString())}話"
                SChapter.create().apply {
                    this.url = "/chapter?id=$cid"
                    this.name = title
                    this.chapter_number = (index + 1).toFloat()
                    this.scanlator = "禁漫天堂"
                }
            }
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val epId = chapter.url.substringAfter("id=")
            val decrypted = apiGet("/chapter?id=$epId")
            val json = JSONObject(decrypted)
            val images = json.getJSONArray("images")
            val pages = mutableListOf<Page>()
            for (i in 0 until images.length()) {
                pages.add(Page(i, getImageUrl(epId, images.getString(i))))
            }
            pages
        }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl, headers = mapOf(
            "Referer" to "https://localhost/",
            "User-Agent" to USER_AGENT,
            "X-Requested-With" to JM_PKG_NAME,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        ))
    }

    fun login(account: String, password: String): Boolean {
        return try {
            val result = apiPost("/login", "username=${URLEncoder.encode(account, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}")
            JSONObject(result).has("uid")
        } catch (_: Exception) { false }
    }

    fun addFavorite(comicId: String) { apiPost("/favorite", "aid=$comicId") }
    fun removeFavorite(comicId: String) { apiPost("/favorite", "aid=$comicId") }

    fun loadFavorites(page: Int = 1, folderId: String = "0"): MangasPage {
        val decrypted = apiGet("/favorite?folder_id=$folderId&page=$page&o=mr")
        val json = JSONObject(decrypted)
        val total = json.getLong("total")
        val maxPage = Math.ceil(total / 20.0).toInt()
        val list = json.optJSONArray("list") ?: JSONArray()
        val comics = mutableListOf<SManga>()
        for (i in 0 until list.length()) comics.add(parseComic(list.getJSONObject(i)))
        return MangasPage(comics, page < maxPage)
    }

    fun dailyCheckIn(): String? {
        if (dailyCheckInInProgress) return null
        dailyCheckInInProgress = true
        return try {
            val savedUid = preferences?.getString("uid", null) ?: return "请先登录"
            val today = SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date())
            val last = preferences?.getString("lastCheckInDate", null)
            if (last == today) return "今日已签到"
            val record = apiGet("/daily?user_id=$savedUid")
            val dailyId = JSONObject(record).optString("daily_id", "")
            if (dailyId.isEmpty()) return "签到失败"
            val result = apiPost("/daily_chk", "user_id=$savedUid&daily_id=$dailyId")
            val msg = JSONObject(result).optString("msg", "签到成功")
            preferences?.edit()?.putString("lastCheckInDate", today)?.apply()
            msg
        } catch (e: Exception) { "签到失败: ${e.message}" }
        finally { dailyCheckInInProgress = false }
    }

    fun loadComments(comicId: String, page: Int = 1): List<Comment> {
        val decrypted = apiGet("/forum?mode=manhua&aid=$comicId&page=$page")
        val json = JSONObject(decrypted)
        val list = json.optJSONArray("list") ?: JSONArray()
        val comments = mutableListOf<Comment>()
        for (i in 0 until list.length()) {
            val obj = list.getJSONObject(i)
            comments.add(Comment(
                getAvatarUrl(obj.optString("photo", "")),
                obj.optString("username", "匿名"),
                obj.optString("addtime", ""),
                Jsoup.parse(obj.optString("content", "")).text()
            ))
        }
        return comments
    }

    fun sendComment(comicId: String, content: String): Boolean {
        return try {
            val encoded = URLEncoder.encode(content, "UTF-8")
            val result = apiPost("/comment", "aid=$comicId&comment=$encoded&status=undefined")
            JSONObject(result).optString("status", "") != "fail"
        } catch (_: Exception) { false }
    }

    data class Comment(val avatar: String, val userName: String, val time: String, val content: String)

    override fun mangaUrlParse(url: String): SManga = SManga.create().apply { this.url = url }
    override fun getMangaUrl(manga: SManga): String = "https://${apiDomains[0]}${manga.url}"

    override fun popularMangaRequest(page: Int): Request = GET("")
    override fun popularMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun searchMangaRequest(query: String, page: Int, filters: FilterList): Request = GET("")
    override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun chapterListRequest(manga: SManga): Request = GET("")
    override fun chapterListParse(response: Response): List<SChapter> = emptyList()
    override fun pageListRequest(chapter: SChapter): Request = GET("")
    override fun pageListParse(response: Response): List<Page> = emptyList()
    override fun mangaDetailsParse(response: Response): SManga = SManga.create()
    override fun mangaDetailsRequest(manga: SManga): Request = GET("")
}