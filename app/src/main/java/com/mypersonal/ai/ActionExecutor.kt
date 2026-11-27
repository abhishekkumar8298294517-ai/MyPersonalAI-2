package com.mypersonal.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import java.net.URLEncoder

object ActionExecutor {

    /**
     * Checks if user command is a phone automation action.
     * Returns a confirmation reply if handled, or null to let Gemini chat normally.
     */
    fun tryExecuteCommand(context: Context, rawCommand: String): String? {
        val cmd = rawCommand.trim()
        val lower = cmd.lowercase()

        // 1. WhatsApp Message: "whatsapp par [text] bhejo" or "send whatsapp [text]"
        if (lower.contains("whatsapp")) {
            val cleaned = cmd
                .replace(Regex("(?i)whatsapp|par|pe|ko|message|msg|bhejo|send|karo|on"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            return try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val encoded = URLEncoder.encode(cleaned, "UTF-8")
                    data = Uri.parse("https://api.whatsapp.com/send?text=$encoded")
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                if (cleaned.isNotEmpty()) {
                    "WhatsApp khol diya hai '$cleaned' ke saath. Bas contact select karke send daba do!"
                } else {
                    "WhatsApp open kar diya hai."
                }
            } catch (_: Exception) {
                openAppByName(context, "whatsapp") ?: "Phone mein WhatsApp install nahi mila."
            }
        }

        // 2. YouTube Search/Play: "youtube par [query] chalao/search karo"
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("chalao") || lower.contains("dikhao") || lower.contains("par") || lower.contains("pe"))) {
            val query = cmd
                .replace(Regex("(?i)youtube|par|pe|mein|search|play|chalao|dikhao|karo|on"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (query.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return try {
                    context.startActivity(intent)
                    "YouTube par '$query' search kar raha hoon."
                } catch (_: Exception) {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(webIntent)
                    "Browser mein YouTube par '$query' khol diya hai."
                }
            }
        }

        // 3. Chrome / Google Search: "chrome par [query] search karo" or "google [query]"
        if ((lower.contains("chrome") || lower.contains("google") || lower.startsWith("search ")) &&
            !lower.equals("open chrome") && !lower.equals("chrome kholo")
        ) {
            val query = cmd
                .replace(Regex("(?i)chrome|google|par|pe|mein|search|karo|on"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (query.isNotEmpty()) {
                val url = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "Google par '$query' search kar diya hai."
            }
        }

        // 4. Set Alarm: "alarm lagao 7 30" or "set alarm 6 am"
        if (lower.contains("alarm")) {
            val nums = Regex("\\d+").findAll(lower).map { it.value.toInt() }.toList()
            if (nums.isNotEmpty()) {
                var hour = nums[0].coerceIn(0, 23)
                val minute = if (nums.size > 1) nums[1].coerceIn(0, 59) else 0
                if (lower.contains("pm") && hour < 12) hour += 12
                if (lower.contains("am") && hour == 12) hour = 0

                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return try {
                    context.startActivity(intent)
                    String.format("Alarm set kar diya hai %02d:%02d ke liye.", hour, minute)
                } catch (_: Exception) {
                    "Alarm app open nahi ho paya."
                }
            }
        }

        // 5. Open Any App: "open [app]" or "[app] kholo"
        if (lower.startsWith("open ") || lower.endsWith(" kholo") || lower.endsWith(" open karo") || lower.endsWith(" chalao")) {
            val appName = cmd
                .replace(Regex("(?i)^open\\s+|\\s+kholo$|\\s+open karo$|\\s+chalao$"), "")
                .trim()
            if (appName.isNotEmpty()) {
                return openAppByName(context, appName)
                    ?: "'$appName' naam ki koi app phone mein nahi mili."
            }
        }

        return null
    }

    private fun openAppByName(context: Context, targetName: String): String? {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull {
            it.loadLabel(pm).toString().contains(targetName, ignoreCase = true)
        } ?: return null

        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null

        context.startActivity(launchIntent)
        return "${match.loadLabel(pm)} open kar diya hai."
    }
}
