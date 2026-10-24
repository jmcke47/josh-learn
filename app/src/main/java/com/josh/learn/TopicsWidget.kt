package com.josh.learn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * Home-screen widget: the user's topics as big tiles in a scrollable grid (built-in topics if none imported), an "N due" chip
 * that opens Review, and a header that opens the app. Tiles stay ~115+ dp: a wider widget gets up to 3 columns, a taller one
 * shows more rows, and anything that doesn't fit scrolls instead of shrinking the tiles.
 */
class TopicsWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { update(c, m, it) } }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle?) = update(c, m, id)

    data class Item(val emoji: String, val title: String, val extraKey: String, val extraValue: String)

    /** Tile geometry for one widget instance, in dp. */
    data class Grid(val cols: Int, val rows: Int, val tileWidthDp: Int, val tileHeightDp: Int)

    companion object {
        const val ACTION_TOPIC = "com.josh.learn.WIDGET_TOPIC"
        const val EXTRA_TOPIC = "widget_topic"     // imported topic title
        const val EXTRA_CATEGORY = "widget_cat"    // built-in category id
        const val EXTRA_SPECIAL = "widget_special" // "surprise" | "custom"
        const val MIN_TILE_DP = 115; const val MAX_COLS = 3
        private const val CHROME_W = 16; private const val CHROME_H = 16 + 30 // widget padding, header row

        /** Columns and visible rows for a widget of the given size in dp. 0 (unknown) -> a normal 2-column phone widget. */
        fun grid(widthDp: Int, heightDp: Int): Grid {
            val w = if (widthDp <= 0) 300 else widthDp
            val h = if (heightDp <= 0) 300 else heightDp
            val cols = ((w - CHROME_W) / MIN_TILE_DP).coerceIn(1, MAX_COLS)
            val usableH = (h - CHROME_H).coerceAtLeast(MIN_TILE_DP)
            val rows = (usableH / MIN_TILE_DP).coerceAtLeast(1)
            return Grid(cols, rows, (w - CHROME_W) / cols, (usableH / rows).coerceIn(MIN_TILE_DP, 170))
        }

        /** Title size in sp so the longest word fits on one line and the title fits in 2 lines; shrinks before it would break a word. */
        fun titleSp(title: String, tileWidthDp: Int): Float {
            val avail = (tileWidthDp - 8 - 16 - 4).coerceAtLeast(40).toFloat()
            val em = 0.60f // average bold glyph width in em
            val longest = title.split(' ', '-', '/').maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
            val byWord = avail / (em * longest)
            val byLines = 2 * avail / (em * title.length * 1.12f)
            return minOf(16f, byWord, byLines).coerceAtLeast(11f).let { Math.floor(it.toDouble()).toFloat() }
        }

        /** Imported topics by priority, then built-in topics, then Surprise / Custom. */
        fun items(c: Context, n: Int = Int.MAX_VALUE): List<Item> {
            val mine = Topics.suggested(c).map { Item(it.emoji, it.title, EXTRA_TOPIC, it.title) }
            val builtIn = Topics.categories.map { Item(it.emoji, it.name, EXTRA_CATEGORY, it.id) }
            val extras = listOf(Item("🎲", "Surprise me", EXTRA_SPECIAL, "surprise"), Item("➕", "Custom", EXTRA_SPECIAL, "custom"))
            return (mine + builtIn + extras).take(n)
        }

        fun tile(c: Context, item: Item, slot: Int, g: Grid): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_topic_tile)
            v.setTextViewText(R.id.tile_emoji, item.emoji)
            v.setTextViewText(R.id.tile_title, item.title)
            v.setTextViewTextSize(R.id.tile_title, TypedValue.COMPLEX_UNIT_SP, titleSp(item.title, g.tileWidthDp))
            v.setTextViewTextSize(R.id.tile_emoji, TypedValue.COMPLEX_UNIT_SP, if (g.tileHeightDp >= 135) 40f else 34f)
            if (Build.VERSION.SDK_INT >= 31) v.setViewLayoutHeight(R.id.tile_frame, g.tileHeightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            v.setContentDescription(R.id.tile_root, "Learn ${item.title}")
            v.setOnClickFillInIntent(R.id.tile_root, Intent().setData(Uri.parse("joshlearn://widget/$slot")).putExtra(item.extraKey, item.extraValue))
            return v
        }

        fun build(c: Context, g: Grid, widgetId: Int = 0): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_topics)
            v.setInt(R.id.widget_grid, "setNumColumns", g.cols)
            if (Build.VERSION.SDK_INT >= 31) {
                val b = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(true)
                items(c).forEachIndexed { i, it -> b.addItem(i.toLong(), tile(c, it, i, g)) }
                v.setRemoteAdapter(R.id.widget_grid, b.build())
            } else {
                val svc = Intent(c, TopicsWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra("cols", g.cols).putExtra("tw", g.tileWidthDp).putExtra("th", g.tileHeightDp)
                svc.data = Uri.parse("joshlearn://widget-svc/$widgetId/${g.cols}/${g.tileWidthDp}/${g.tileHeightDp}")
                @Suppress("DEPRECATION") v.setRemoteAdapter(R.id.widget_grid, svc)
                v.setEmptyView(R.id.widget_grid, R.id.widget_empty)
            }
            val template = Intent(c, MainActivity::class.java).setAction(ACTION_TOPIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            v.setPendingIntentTemplate(R.id.widget_grid, PendingIntent.getActivity(c, 100, template,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)))
            val due = runCatching { Repo.due().size }.getOrDefault(0)
            v.setTextViewText(R.id.widget_due, "🃏 $due due")
            v.setOnClickPendingIntent(R.id.widget_due, PendingIntent.getActivity(c, 90, Intent(c, ReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setOnClickPendingIntent(R.id.widget_header, PendingIntent.getActivity(c, 91, Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }

        fun update(c: Context, m: AppWidgetManager, id: Int) {
            val o = m.getAppWidgetOptions(id)
            val g = grid(o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0, o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0)
            runCatching {
                m.updateAppWidget(id, build(c, g, id))
                @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT < 31) m.notifyAppWidgetViewDataChanged(id, R.id.widget_grid)
            }
        }

        /** Call after topics or flashcards change. */
        fun refresh(c: Context) = runCatching {
            val m = AppWidgetManager.getInstance(c) ?: return@runCatching
            m.getAppWidgetIds(ComponentName(c, TopicsWidget::class.java))?.forEach { update(c.applicationContext, m, it) }
        }
    }
}

/** Collection adapter for Android 8-11 (12+ passes the tiles inline via RemoteCollectionItems). */
class TopicsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        val g = TopicsWidget.Grid(intent.getIntExtra("cols", 2), 0, intent.getIntExtra("tw", 150), intent.getIntExtra("th", 120))
        var list = emptyList<TopicsWidget.Item>()
        override fun onCreate() {}
        override fun onDataSetChanged() { list = TopicsWidget.items(applicationContext) }
        override fun onDestroy() {}
        override fun getCount() = list.size
        override fun getViewAt(position: Int) = TopicsWidget.tile(applicationContext, list[position], position, g)
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
    }
}
