package eu.kanade.tachiyomi.extension.zh.jm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.io.ByteArrayOutputStream
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
        var imageStreamIndex = 1
        var dailyCheckInInProgress = false
    }
    override val client: OkHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", JM_PKG_NAME)
            .build()
        chain.proceed(req)
    }
    .addInterceptor(JmImageInterceptor())
    .readTimeout(30, TimeUnit.SECONDS)
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

// 图片拦截器：处理反盗链图片分块重排
class JmImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        
        // 只处理 JM 图片 CDN 的请求
        if (!url.contains("/media/photos/")) {
            return chain.proceed(request)
        }

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

        // 计算分块数
        val num = calculateNum(epId, pictureName)
        if (num <= 1) return response
        if (pictureName.endsWith(".gif", ignoreCase = true)) return response

        val bytes = response.body?.bytes() ?: return response
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return response
        val modified = scrambleImage(bitmap, num)
        
        val outputStream = ByteArrayOutputStream()
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
                val hash = JmCrypto.md5(JmCrypto.encodeUtf8("$epId$pictureName"))
                (JmCrypto.hexEncode(hash).last().code % 8) * 2 + 2
            }
            else -> {
                val hash = JmCrypto.md5(JmCrypto.encodeUtf8("$epId$pictureName"))
                (JmCrypto.hexEncode(hash).last().code % 10) * 2 + 2
            }
        }
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
// ========== API 请求 ==========

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
    val token = JmCrypto.generateToken(time, JM_AUTH_KEY)
    return baseHeaders() + mapOf(
        "Authorization" to "Bearer",
        "Sec-Fetch-Storage-Access" to "active",
        "token" to token,
        "tokenparam" to "${time},${JM_VERSION}",
        "User-Agent" to USER_AGENT,
    )
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
            val json = Json.parseToJsonElement(body).jsonObject
            val msg = json["errorMsg"]?.jsonPrimitive?.content ?: "请先登录会员"
            throw Exception(msg)
        }
        throw Exception("HTTP $code")
    }
    val body = response.body?.string() ?: throw Exception("Empty response")
    val json = Json.parseToJsonElement(body).jsonObject
    val data = json["data"]?.jsonPrimitive?.content ?: throw Exception("Invalid Data")
    return JmCrypto.convertData(data, "${time}${JM_SECRET}")
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
            val json = Json.parseToJsonElement(body).jsonObject
            val msg = json["errorMsg"]?.jsonPrimitive?.content ?: "请先登录会员"
            throw Exception(msg)
        }
        throw Exception("HTTP $code")
    }
    val body = response.body?.string() ?: throw Exception("Empty response")
    val json = Json.parseToJsonElement(body).jsonObject
    val data = json["data"]?.jsonPrimitive?.content ?: throw Exception("Invalid Data")
    return JmCrypto.convertData(data, "${time}${JM_SECRET}")
}
// ========== URL 生成 ==========

private fun getCoverUrl(id: String) = "${IMAGE_URL}/media/albums/${id}_3x4.jpg"
private fun getImageUrl(epId: String, imageName: String) = "${IMAGE_URL}/media/photos/${epId}/${imageName}"
private fun getAvatarUrl(imageName: String) = "${IMAGE_URL}/media/users/${imageName}"

private fun extractId(url: String): String {
    var id = url.substringAfter("id=")
    if (id.startsWith("jm")) id = id.substring(2)
    return id
}

private fun parseComic(json: JsonObject): SManga {
    val id = json["id"]!!.jsonPrimitive.content
    return SManga.create().apply {
        this.url = "/album?id=$id"
        this.title = json["name"]?.jsonPrimitive?.content ?: "Unknown"
        this.author = json["author"]?.jsonPrimitive?.content ?: ""
        this.description = json["description"]?.jsonPrimitive?.content ?: ""
        this.thumbnail_url = getCoverUrl(id)
        val tags = mutableListOf<String>()
        if (json["category"] is JsonObject) {
            json["category"]?.jsonObject?.get("title")?.jsonPrimitive?.content?.let { tags.add(it) }
        }
        if (json["category_sub"] is JsonObject) {
            json["category_sub"]?.jsonObject?.get("title")?.jsonPrimitive?.content?.let { tags.add(it) }
        }
        this.genre = tags.joinToString(", ")
        this.initialized = true
    }
}
// ========== 热门 / 探索 ==========

    override fun popularManga(page: Int): MangasPage {
        val decrypted = apiGet("/promote?page=0")
        val sections = Json.parseToJsonElement(decrypted).jsonArray
        val allComics = mutableListOf<SManga>()
        for (section in sections) {
            val obj = section.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: ""
            if (type == "library" || type == "novels") continue
            val content = obj["content"]?.jsonArray ?: continue
            for (comicEl in content) {
                allComics.add(parseComic(comicEl.jsonObject))
            }
        }
        return MangasPage(allComics.distinctBy { it.url }, false)
    }

    // ========== 搜索 ==========

    override fun searchManga(query: String, page: Int, filters: FilterList): MangasPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("%20", "+")
        val decrypted = apiGet("/search?search_query=$encoded&o=mr&page=$page")
        val json = Json.parseToJsonElement(decrypted).jsonObject
        val total = json["total"]!!.jsonPrimitive.long
        val maxPage = Math.ceil(total / 80.0).toInt()
        val comics = json["content"]!!.jsonArray.map { parseComic(it.jsonObject) }
        return MangasPage(comics, page < maxPage)
    }

    // ========== 漫画详情 ==========

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val id = extractId(manga.url)
            val decrypted = apiGet("/album?id=$id")
            val json = Json.parseToJsonElement(decrypted).jsonObject
            parseMangaDetails(json, id)
        }
    }

    private fun parseMangaDetails(json: JsonObject, id: String): SManga {
        val name = json["name"]?.jsonPrimitive?.content ?: "Unknown"
        val description = json["description"]?.jsonPrimitive?.content ?: ""
        val author = json["author"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""
        val tags = json["tags"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""
        val views = json["total_views"]?.jsonPrimitive?.content ?: ""
        val likes = json["likes"]?.jsonPrimitive?.content ?: "0"

        val timestamp = json["addtime"]?.jsonPrimitive?.long ?: 0L
        val updateDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp * 1000))

        return SManga.create().apply {
            this.url = "/album?id=$id"
            this.title = name
            this.author = author
            this.description = buildString {
                append(description)
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

    // ========== 章节列表 ==========

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val id = extractId(manga.url)
            val decrypted = apiGet("/album?id=$id")
            val json = Json.parseToJsonElement(decrypted).jsonObject
            val series = json["series"]?.jsonArray
                ?.sortedBy { it.jsonObject["sort"]?.jsonPrimitive?.long ?: 0L }
                ?: emptyList()

            if (series.isEmpty()) {
                return@fromCallable listOf(SChapter.create().apply {
                    this.url = "/chapter?id=$id"
                    this.name = "第1話"
                    this.chapter_number = 1f
                })
            }

            series.mapIndexed { index, element ->
                val obj = element.jsonObject
                val cid = obj["id"]!!.jsonPrimitive.content
                var title = obj["name"]?.jsonPrimitive?.content ?: ""
                if (title.isBlank()) title = "第${obj["sort"]?.jsonPrimitive?.content ?: (index + 1)}話"
                SChapter.create().apply {
                    this.url = "/chapter?id=$cid"
                    this.name = title
                    this.chapter_number = (index + 1).toFloat()
                    this.scanlator = "禁漫天堂"
                }
            }
        }
    }

    // ========== 页面列表 ==========

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val epId = chapter.url.substringAfter("id=")
            val decrypted = apiGet("/chapter?id=$epId")
            val json = Json.parseToJsonElement(decrypted).jsonObject
            val images = json["images"]?.jsonArray ?: JsonArray(emptyList())
            images.mapIndexed { index, element ->
                val imageName = element.jsonPrimitive.content
                Page(index, getImageUrl(epId, imageName))
            }
        }
    }

    // ========== 图片请求头 ==========

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl, headers = mapOf(
            "Referer" to "https://localhost/",
            "User-Agent" to USER_AGENT,
            "X-Requested-With" to JM_PKG_NAME,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        ))
    }

    // ========== 登录 ==========

    fun login(account: String, password: String): Boolean {
        return try {
            val result = apiPost("/login", 
                "username=${URLEncoder.encode(account, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}")
            val json = Json.parseToJsonElement(result).jsonObject
            json.containsKey("uid")
        } catch (_: Exception) { false }
    }

    // ========== 收藏 ==========

    fun addFavorite(comicId: String) {
        apiPost("/favorite", "aid=$comicId")
    }

    fun removeFavorite(comicId: String) {
        apiPost("/favorite", "aid=$comicId")
    }

    fun loadFavorites(page: Int = 1, folderId: String = "0"): MangasPage {
        val decrypted = apiGet("/favorite?folder_id=$folderId&page=$page&o=mr")
        val json = Json.parseToJsonElement(decrypted).jsonObject
        val total = json["total"]!!.jsonPrimitive.long
        val maxPage = Math.ceil(total / 20.0).toInt()
        val list = json["list"]?.jsonArray ?: JsonArray(emptyList())
        return MangasPage(list.map { parseComic(it.jsonObject) }, page < maxPage)
    }

    // ========== 每日签到 ==========

    fun dailyCheckIn(): String? {
        if (dailyCheckInInProgress) return null
        dailyCheckInInProgress = true
        return try {
            val uid = preferences?.getString("uid", null) ?: return "请先登录"
            val today = SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date())
            val last = preferences?.getString("lastCheckInDate", null)
            if (last == today) return "今日已签到"

            val record = apiGet("/daily?user_id=$uid")
            val dailyId = Json.parseToJsonElement(record).jsonObject["daily_id"]?.jsonPrimitive?.content
                ?: return "签到失败"

            val result = apiPost("/daily_chk", "user_id=$uid&daily_id=$dailyId")
            val msg = Json.parseToJsonElement(result).jsonObject["msg"]?.jsonPrimitive?.content ?: "签到成功"
            preferences?.edit()?.putString("lastCheckInDate", today)?.apply()
            msg
        } catch (e: Exception) { "签到失败: ${e.message}" }
        finally { dailyCheckInInProgress = false }
    }

    // ========== 辅助方法 ==========

    override fun mangaUrlParse(url: String): SManga {
        return SManga.create().apply { this.url = url }
    }

    override fun getMangaUrl(manga: SManga): String {
        return "https://${apiDomains[0]}${manga.url}"
    }

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
