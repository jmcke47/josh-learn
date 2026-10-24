package com.josh.learn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget: the user's top topics as tiles (built-in topics if none imported), an "N due" chip that opens
 * Review, and a header that opens the app. Resizable: 2x2 shows 4 tiles, wider/taller shows up to 12.
 */
class TopicsWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { update(c, m, it) } }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle?) = update(c, m, id)

    data class Item(val emoji: String, val title: String, val extraKey: String, val extraValue: String)

    companion object {
        const val ACTION_TOPIC = "com.josh.learn.WIDGET_TOPIC"
        const val EXTRA_TOPIC = "widget_topic"     // imported topic title
        const val EXTRA_CATEGORY = "widget_cat"    // built-in category id
        const val EXTRA_SPECIAL = "widget_special" // "surprise" | "custom"
        const val MAX_COLS = 5; const val MAX_ROWS = 8
        private val TILE_IDS = intArrayOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3, R.id.tile4, R.id.tile5, R.id.tile6, R.id.tile7, R.id.tile8, R.id.tile9, R.id.tile10, R.id.tile11, R.id.tile12, R.id.tile13, R.id.tile14, R.id.tile15, R.id.tile16, R.id.tile17, R.id.tile18, R.id.tile19, R.id.tile20, R.id.tile21, R.id.tile22, R.id.tile23, R.id.tile24, R.id.tile25, R.id.tile26, R.id.tile27, R.id.tile28, R.id.tile29, R.id.tile30, R.id.tile31, R.id.tile32, R.id.tile33, R.id.tile34, R.id.tile35, R.id.tile36, R.id.tile37, R.id.tile38, R.id.tile39)
        private val ROW_IDS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6, R.id.row7)

        /** Columns x rows for a widget of the given size in dp: ~78 dp wide, ~72 dp tall tiles, so they stay roughly square at any size. */
        fun grid(widthDp: Int, heightDp: Int): Pair<Int, Int> {
            val cols = if (widthDp <= 0) 2 else (widthDp / 78).coerceIn(2, MAX_COLS)
            val rows = if (heightDp <= 0) 2 else Math.round((heightDp - 36) / 72f).coerceIn(1, MAX_ROWS)
            return cols to rows
        }

        /** Imported topics by priority, then built-in topics, then Surprise / Custom. */
        fun items(c: Context, n: Int): List<Item> {
            val mine = Topics.suggested(c).map { Item(it.emoji, it.title, EXTRA_TOPIC, it.title) }
            val builtIn = Topics.categories.map { Item(it.emoji, it.name, EXTRA_CATEGORY, it.id) }
            val extras = listOf(Item("🎲", "Surprise me", EXTRA_SPECIAL, "surprise"), Item("➕", "Custom", EXTRA_SPECIAL, "custom"))
            return if (mine.isEmpty()) (builtIn + extras).take(n)
            else (mine + builtIn + extras).take(n)
        }

        fun build(c: Context, cols: Int, rows: Int): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_topics)
            val items = items(c, cols * rows)
            @Suppress("NAME_SHADOWING") val rows = minOf(rows, maxOf(1, (items.size + cols - 1) / cols)) // no empty rows: tiles grow instead
            for (r in 0 until MAX_ROWS) v.setViewVisibility(ROW_IDS[r], if (r < rows) View.VISIBLE else View.GONE)
            for (r in 0 until MAX_ROWS) for (col in 0 until MAX_COLS) {
                val i = r * MAX_COLS + col; val id = TILE_IDS[i]
                val slot = r * cols + col
                val item = items.getOrNull(slot)
                if (col >= cols || r >= rows || item == null) { v.setViewVisibility(id, if (col >= cols) View.GONE else View.INVISIBLE); continue }
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, "${item.emoji}\n${item.title}")
                v.setContentDescription(id, "Learn ${item.title}")
                val intent = Intent(c, MainActivity::class.java).setAction(ACTION_TOPIC)
                    .setData(Uri.parse("joshlearn://widget/$slot")).putExtra(item.extraKey, item.extraValue)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.setOnClickPendingIntent(id, PendingIntent.getActivity(c, 100 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            val due = runCatching { Repo.due().size }.getOrDefault(0)
            v.setTextViewText(R.id.widget_due, if (due > 0) "🃏 $due due" else "🃏 0 due")
            v.setOnClickPendingIntent(R.id.widget_due, PendingIntent.getActivity(c, 90, Intent(c, ReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setOnClickPendingIntent(R.id.widget_header, PendingIntent.getActivity(c, 91, Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }

        fun update(c: Context, m: AppWidgetManager, id: Int) {
            val o = m.getAppWidgetOptions(id)
            val (cols, rows) = grid(o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0, o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0)
            runCatching { m.updateAppWidget(id, build(c, cols, rows)) }
        }

        /** Call after topics or flashcards change. */
        fun refresh(c: Context) = runCatching {
            val m = AppWidgetManager.getInstance(c) ?: return@runCatching
            m.getAppWidgetIds(ComponentName(c, TopicsWidget::class.java))?.forEach { update(c.applicationContext, m, it) }
        }
    }
}
