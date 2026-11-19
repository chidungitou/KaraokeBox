package com.karaokebox.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.karaokebox.BuildConfig
import com.karaokebox.data.repo.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.karaokebox.ServiceLocator
import com.karaokebox.core.net.withBundledRoots
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tự cập nhật chính app: hỏi GitHub Releases của repo (BuildConfig.UPDATE_REPO) xem có APK mới hơn bản đang chạy không,
 * tải về rồi mở trình cài đặt của Android (người dùng bấm "Cài đặt" một lần; box không root thì Android không cho cài ngầm).
 */
object AppUpdater {
    private const val APK_NAME = "KaraokeBox.apk"
    private const val API_URL = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    /** Bản phát hành đã biết: [tag] dạng v1.7.0-np-v0.26.5, [url] là link tải trực tiếp KaraokeBox.apk. */
    data class Release(val tag: String, val url: String, val size: Long) {
        val label: String get() = UpdateVersion.parseTag(tag)?.let { it.app + " (NewPipe " + it.np + ")" } ?: tag
    }

    /** error != null: lần kiểm tra này thất bại; release là giá trị đã lưu từ lần trước (nếu có). */
    data class Result(val release: Release?, val error: String?)

    data class ApkCheck(val validPackage: Boolean, val sameSigner: Boolean?, val mineFp: String? = null, val theirsFp: String? = null)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .withBundledRoots(ServiceLocator.app)   // box Android cũ thiếu chứng chỉ gốc → tải từ GitHub báo "Trust anchor"
            .build()
    }

    @Volatile private var lastTryAt = 0L

    fun isNewer(r: Release): Boolean = UpdateVersion.isNewer(r.tag, BuildConfig.VERSION_NAME, BuildConfig.NEWPIPE_VERSION)

    private fun saved(s: SettingsStore): Release? {
        if (s.appLatestTag.isEmpty() || s.appLatestUrl.isEmpty()) return null
        return Release(s.appLatestTag, s.appLatestUrl, s.appLatestSize)
    }

    /** Giá trị đã lưu từ lần kiểm tra trước (không gọi mạng). */
    fun known(s: SettingsStore): Release? = saved(s)

    /** [minIntervalMs] > 0: vừa kiểm tra trong khoảng đó thì trả luôn giá trị đã lưu. 0 = bắt buộc gọi mạng. */
    suspend fun check(s: SettingsStore, minIntervalMs: Long = 0L): Result = withContext(Dispatchers.IO) {
        val old = saved(s)
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0 && ((old != null && now - s.appCheckedAt < minIntervalMs) || now - lastTryAt < minIntervalMs)) {
            return@withContext Result(old, null)
        }
        lastTryAt = now
        try {
            val req = Request.Builder().url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KaraokeBox/" + BuildConfig.VERSION_NAME)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = if (resp.code == 403 || resp.code == 429) "GitHub tạm giới hạn truy cập (mã ${resp.code}), thử lại sau ít phút"
                    else "GitHub trả mã ${resp.code}"
                    return@withContext Result(old, msg)
                }
                val obj = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                val tag = obj.get("tag_name")?.asString?.trim().orEmpty()
                if (UpdateVersion.parseTag(tag) == null) return@withContext Result(old, "Không đọc được số phiên bản bản mới nhất ($tag)")
                var url = ""
                var size = 0L
                val assets = obj.getAsJsonArray("assets")
                if (assets != null) {
                    for (a in assets) {
                        val o = a.asJsonObject
                        if (o.get("name")?.asString == APK_NAME) {
                            url = o.get("browser_download_url")?.asString.orEmpty()
                            size = o.get("size")?.asLong ?: 0L
                        }
                    }
                }
                if (url.isEmpty()) return@withContext Result(old, "Bản $tag chưa có file $APK_NAME")
                s.appLatestTag = tag
                s.appLatestUrl = url
                s.appLatestSize = size
                s.appCheckedAt = System.currentTimeMillis()
                Result(Release(tag, url, size), null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(old, "Không kết nối được GitHub (${e.javaClass.simpleName}). Kiểm tra Internet của box.")
        }
    }

    private fun apkFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir("update") ?: File(ctx.cacheDir, "update")
        dir.mkdirs()
        return File(dir, APK_NAME)
    }

    /** Tải APK về; [onProgress] (đã tải, tổng) được gọi từ luồng nền, tối đa ~3 lần/giây. */
    suspend fun download(ctx: Context, r: Release, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val out = apkFile(ctx)
        val part = File(out.parentFile, "$APK_NAME.part")
        part.delete()
        val req = Request.Builder().url(r.url).header("User-Agent", "KaraokeBox/" + BuildConfig.VERSION_NAME).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub trả mã ${resp.code}")
            val body = resp.body ?: throw IOException("Không có dữ liệu tải về")
            val total = if (body.contentLength() > 0) body.contentLength() else r.size
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val t = SystemClock.elapsedRealtime()
                        if (t - last > 300) { last = t; onProgress(done, total) }
                    }
                    if (total > 0 && done != total) throw IOException("Tải thiếu dữ liệu ($done/$total byte)")
                    onProgress(done, total)
                }
            }
        }
        out.delete()
        if (!part.renameTo(out)) throw IOException("Không lưu được file cập nhật")
        out.setReadable(true, false)
        out
    }

    /** Kiểm tra file tải về đúng là APK của app này; sameSigner = null nếu không xác định được. */
    @Suppress("DEPRECATION")
    fun inspect(ctx: Context, apk: File): ApkCheck {
        val pm = ctx.packageManager
        val info = try { pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES) } catch (e: Exception) { null }
            ?: return ApkCheck(false, null)
        if (info.packageName != ctx.packageName) return ApkCheck(false, null)
        var mineFp: String? = null
        var theirsFp: String? = null
        val same: Boolean? = try {
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
            val theirs = info.signatures
            mineFp = signerFingerprint(mine)
            theirsFp = signerFingerprint(theirs)
            if (mine == null || theirs == null || mine.isEmpty() || theirs.isEmpty()) null
            else mine.map { it.toCharsString() }.toSet() == theirs.map { it.toCharsString() }.toSet()
        } catch (e: Exception) { null }
        return ApkCheck(true, same, mineFp, theirsFp)
    }

    /** 6 byte đầu của SHA-256 chứng chỉ ký (dạng AB:CD:...), để so với dòng "Khoá ký" trong ghi chú của release. */
    @Suppress("DEPRECATION")
    fun signerFingerprint(sigs: Array<android.content.pm.Signature>?): String? {
        val s = sigs?.firstOrNull() ?: return null
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.take(6).joinToString(":") { String.format("%02X", it) }
    }

    /** Khoá ký của chính app đang chạy. */
    @Suppress("DEPRECATION")
    fun selfSigner(ctx: Context): String? = try {
        signerFingerprint(ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures)
    } catch (e: Exception) { null }

    /** Đoạn chú thích hai khoá để biết khoá nào đang lệch. */
    fun signerHint(chk: ApkCheck): String =
        if (chk.mineFp != null && chk.theirsFp != null) " [Khoá đang chạy: ${chk.mineFp}, khoá bản mới: ${chk.theirsFp}]" else ""

    /** Android 8+ bắt người dùng cho phép app này "cài ứng dụng không rõ nguồn gốc". */
    fun needsInstallPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(ctx: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))

    fun installIntent(ctx: Context, apk: File): Intent {
        // Android 7+ cấm đưa file:// cho app khác (phải qua FileProvider); Android 5/6 thì trình cài đặt chỉ hiểu file://
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(ctx, ctx.packageName + ".update", apk)
        else Uri.fromFile(apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
