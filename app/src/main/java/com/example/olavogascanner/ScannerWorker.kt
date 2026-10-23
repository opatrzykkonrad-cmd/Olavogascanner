package com.example.olavogascanner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ScannerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("olavoga_prefs", Context.MODE_PRIVATE)
        val query = prefs.getString("search_query", "olavoga") ?: "olavoga"
        val tgToken = prefs.getString("tg_token", "") ?: ""
        val tgChatId = prefs.getString("tg_chat_id", "") ?: ""
        val metaToken = prefs.getString("meta_token", "") ?: ""

        val seenSet = prefs.getStringSet("seen_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        val olxOffers = fetchOlxOffers(query, seenSet)
        val metaAds = if (metaToken.isNotBlank()) fetchMetaAds(query, metaToken, seenSet) else emptyList()

        prefs.edit().putStringSet("seen_ids", seenSet).apply()

        showLocalPushNotification(query, olxOffers.size, metaAds.size)

        if (tgToken.isNotBlank() && tgChatId.isNotBlank()) {
            val report = buildTelegramReport(query, olxOffers, metaAds, metaToken.isNotBlank())
            sendToTelegram(tgToken, tgChatId, report)
        }

        val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        prefs.edit().putString("last_scan", "$now (OLX: ${olxOffers.size}, Meta: ${metaAds.size})").apply()

        Result.success()
    }

    private fun showLocalPushNotification(query: String, olxCount: Int, metaCount: Int) {
        val channelId = "olavoga_radar_channel"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Raporty Olavoga Radar",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Radar ${query.uppercase()}: Nowy raport!"
        val content = if (olxCount > 0 || metaCount > 0) {
            "Znaleziono $olxCount nowych ofert na OLX oraz $metaCount reklam Meta Ads."
        } else {
            "Skan zakończony. Brak nowych ofert od ostatniego sprawdzenia."
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }

    private fun fetchOlxOffers(query: String, seenSet: MutableSet<String>): List<String> {
        val newItems = mutableListOf<String>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.olx.pl/api/v1/offers/?offset=0&limit=40&query=$encodedQuery&sort_by=created_at:desc"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:124.0)")
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val dataArray = JSONObject(body).optJSONArray("data") ?: return emptyList()

                for (i in 0 until dataArray.length()) {
                    val offer = dataArray.getJSONObject(i)
                    val id = "olx_${offer.optString("id")}"
                    val title = offer.optString("title", "Oferta")
                    val offerUrl = offer.optString("url", "")

                    if (!title.lowercase().contains(query.lowercase())) continue

                    if (!seenSet.contains(id)) {
                        var price = "Brak ceny"
                        val params = offer.optJSONArray("params")
                        if (params != null) {
                            for (p in 0 until params.length()) {
                                val param = params.getJSONObject(p)
                                if (param.optString("key") == "price") {
                                    price = param.optJSONObject("value")?.optString("label") ?: "Brak ceny"
                                    break
                                }
                            }
                        }
                        newItems.add("• <a href='$offerUrl'>$title</a> — <b>$price</b>")
                        seenSet.add(id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScannerWorker", "Błąd OLX: ${e.message}")
        }
        return newItems
    }

    private fun fetchMetaAds(query: String, token: String, seenSet: MutableSet<String>): List<String> {
        val newAds = mutableListOf<String>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://graph.facebook.com/v20.0/ads_archive?search_terms=$encodedQuery&ad_reached_countries=['PL']&ad_active_status=ACTIVE&ad_type=ALL&fields=id,page_name,ad_snapshot_url&limit=20&access_token=$token"

        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val dataArray = JSONObject(body).optJSONArray("data") ?: return emptyList()

                for (i in 0 until dataArray.length()) {
                    val ad = dataArray.getJSONObject(i)
                    val rawId = ad.optString("id")
                    val id = "meta_$rawId"
                    val pageName = ad.optString("page_name", "Reklamodawca")
                    val adUrl = ad.optString("ad_snapshot_url", "https://www.facebook.com/ads/library/?id=$rawId")

                    if (!seenSet.contains(id)) {
                        newAds.add("• <b>$pageName</b> (<a href='$adUrl'>Podgląd reklamy</a>)")
                        seenSet.add(id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScannerWorker", "Błąd Meta Ads: ${e.message}")
        }
        return newAds
    }

    private fun buildTelegramReport(query: String, olx: List<String>, meta: List<String>, hasMetaToken: Boolean): String {
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("📊 <b>Raport dzienny: ${query.uppercase()} ($date)</b>\n\n")

        sb.append("🛍 <b>Nowe oferty OLX (${olx.size}):</b>\n")
        if (olx.isEmpty()) {
            sb.append("<i>Brak nowych ofert od ostatniego skanu.</i>\n")
        } else {
            olx.take(20).forEach { sb.append("$it\n") }
            if (olx.size > 20) sb.append("<i>...oraz ${olx.size - 20} więcej.</i>\n")
        }

        if (hasMetaToken) {
            sb.append("\n📣 <b>Nowe reklamy Meta Ads (${meta.size}):</b>\n")
            if (meta.isEmpty()) {
                sb.append("<i>Brak nowych reklam.</i>\n")
            } else {
                meta.take(15).forEach { sb.append("$it\n") }
            }
        } else {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val libraryUrl = "https://www.facebook.com/ads/library/?active_status=active&ad_type=all&country=PL&q=$encoded"
            sb.append("\n📣 <a href='$libraryUrl'>Kliknij, aby otworzyć aktywne reklamy Meta Ads dla '$query'</a>")
        }

        return sb.toString()
    }

    private fun sendToTelegram(token: String, chatId: String, message: String): Boolean {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", message.take(4000))
            put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
        }

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
