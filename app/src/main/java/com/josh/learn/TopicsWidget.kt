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
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(c, m, it) }
        // New day: write the fun fact with a cloud provider in the background (the offline model is only loaded inside the app)
        runCatching { val p = ProviderStore(c); if (p.active != Provider.LOCAL) TopicOfDay.ensureFact(c, p) }
    }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle?) = update(c, m, id)

    data class Item(val emoji: String, val title: String, val extraKey: String, val extraValue: String)

    /** Tile geometry for one widget instance, in dp. */
    data class Grid(val cols: Int, val rows: Int, val tileWidthDp: Int, val tileHeightDp: Int, val showTopicOfDay: Boolean = false)

    companion object {
        const val ACTION_TOPIC = "com.josh.learn.WIDGET_TOPIC"
        const val EXTRA_TOPIC = "widget_topic"     // imported topic title
        const val EXTRA_CATEGORY = "widget_cat"    // built-in category id
        const val EXTRA_SPECIAL = "widget_special" // "surprise" | "custom"
        const val MIN_TILE_DP = 115; const val MAX_COLS = 3
        private const val CHROME_W = 16; private const val CHROME_H = 16 + 30 // widget padding, header row
        const val TOD_H = 86; const val TOD_MIN_HEIGHT = 250 // topic-of-the-day banner, shown when the widget is at least this tall

        /** Columns and visible rows for a widget of the given size in dp. 0 (unknown) -> a normal 2-column phone widget. */
        fun grid(widthDp: Int, heightDp: Int): Grid {
            val w = if (widthDp <= 0) 300 else widthDp
            val h = if (heightDp <= 0) 300 else heightDp
            val cols = ((w - CHROME_W) / MIN_TILE_DP).coerceIn(1, MAX_COLS)
            val tod = h >= TOD_MIN_HEIGHT
            val usableH = (h - CHROME_H - (if (tod) TOD_H else 0)).coerceAtLeast(MIN_TILE_DP)
            val rows = (usableH / MIN_TILE_DP).coerceAtLeast(1)
            return Grid(cols, rows, (w - CHROME_W) / cols, (usableH / rows).coerceIn(MIN_TILE_DP, 170), tod)
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
            if (Display.theme(c) == "osrs") return osTile(c, item, slot, g)
            val theme = Display.theme(c)
            val v = RemoteViews(c.packageName, R.layout.widget_topic_tile)
            if (theme == "amoled") { v.setInt(R.id.tile_root, "setBackgroundResource", R.drawable.widget_tile_bg_amoled); v.setTextColor(R.id.tile_title, 0xFFE8E8E8.toInt()) }
            v.setTextViewText(R.id.tile_emoji, item.emoji)
            v.setTextViewText(R.id.tile_title, item.title)
            v.setTextViewTextSize(R.id.tile_title, TypedValue.COMPLEX_UNIT_SP, titleSp(item.title, g.tileWidthDp))
            v.setTextViewTextSize(R.id.tile_emoji, TypedValue.COMPLEX_UNIT_SP, if (g.tileHeightDp >= 135) 40f else 34f)
            if (Build.VERSION.SDK_INT >= 31) v.setViewLayoutHeight(R.id.tile_frame, g.tileHeightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            v.setContentDescription(R.id.tile_root, "Learn ${item.title}")
            v.setOnClickFillInIntent(R.id.tile_root, Intent().setData(Uri.parse("joshlearn://widget/$slot")).putExtra(item.extraKey, item.extraValue))
            return v
        }

        /** OSRS tile: the kit's stretchable RemoteViews inventory slot with a 2-line pixel-font label and the topic's level. */
        fun osTile(c: Context, item: Item, slot: Int, g: Grid): RemoteViews {
            val v = RemoteViews(c.packageName, com.josh.oldschool.R.layout.os_rv_inventory_slot_wide)
            val wPx = ((g.tileWidthDp - 4) * c.resources.displayMetrics.density).toInt()
            val level = if (item.extraKey == EXTRA_SPECIAL) 0 else LearnXp.level(c, if (item.extraKey == EXTRA_CATEGORY) item.extraValue
                else Topics.suggested(c).firstOrNull { it.title == item.extraValue }?.asCategory?.id ?: item.extraValue)
            com.josh.oldschool.OsRemote.slot(c, v, item.emoji, item.title, level, wPx, labelMaxLines = 2, labelSp = 13f)
            v.setTextViewTextSize(com.josh.oldschool.R.id.os_slot_emoji, TypedValue.COMPLEX_UNIT_SP, if (g.tileHeightDp >= 135) 44f else 38f)
            if (Build.VERSION.SDK_INT >= 31) v.setViewLayoutHeight(com.josh.oldschool.R.id.os_slot, g.tileHeightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            v.setContentDescription(com.josh.oldschool.R.id.os_slot, "Learn ${item.title}")
            v.setOnClickFillInIntent(com.josh.oldschool.R.id.os_slot, Intent().setData(Uri.parse("joshlearn://widget/$slot")).putExtra(item.extraKey, item.extraValue))
            return v
        }

        /**
         * Single frame for every theme: one layout, restyled in place. Every themed property is set explicitly on each
         * update so a launcher never keeps a stale piece of the previous theme (same approach as the Josh Notes widget).
         */
        private fun styleFrame(c: Context, v: RemoteViews, theme: String, g: Grid, due: Int, tod: Item?) {
            val osrs = theme == "osrs"; val amoled = theme == "amoled"
            val gone = android.view.View.GONE; val shown = android.view.View.VISIBLE
            val dm = c.resources.displayMetrics
            v.setInt(R.id.widget_root, "setBackgroundResource", when { osrs -> R.drawable.os_widget_bg; amoled -> R.drawable.widget_bg_amoled; else -> R.drawable.widget_bg })
            val pad = (8 * dm.density).toInt(); v.setViewPadding(R.id.widget_root, pad, pad, pad, pad)
            v.setInt(R.id.widget_tod, "setBackgroundResource", when { osrs -> R.drawable.os_chatbox; amoled -> R.drawable.widget_tod_bg_amoled; else -> R.drawable.widget_tod_bg })
            v.setInt(R.id.widget_due, "setBackgroundResource", if (osrs) R.drawable.os_side_tab else R.drawable.widget_chip_bg)
            v.setInt(R.id.widget_due_img, "setBackgroundResource", if (osrs) R.drawable.os_side_tab else 0)
            v.setTextColor(R.id.widget_header, if (amoled) 0xFFE8E8E8.toInt() else c.getColor(R.color.widget_text))
            v.setTextColor(R.id.tod_title, if (amoled) 0xFFB9F2D2.toInt() else c.getColor(R.color.widget_on_tile))
            v.setTextColor(R.id.tod_fact, if (amoled) 0xFFE8E8E8.toInt() else c.getColor(R.color.widget_on_tile))
            v.setTextColor(R.id.widget_empty, if (osrs) c.getColor(R.color.os_yellow) else if (amoled) 0xFFE8E8E8.toInt() else c.getColor(R.color.widget_text))
            // Text vs pixel-font bitmaps
            listOf(R.id.widget_header, R.id.widget_due, R.id.tod_title, R.id.tod_fact).forEach { v.setViewVisibility(it, if (osrs) gone else shown) }
            listOf(R.id.widget_header_img, R.id.widget_due_img, R.id.tod_title_img, R.id.tod_fact_img).forEach { v.setViewVisibility(it, gone) }
            v.setViewVisibility(R.id.widget_header_space, if (osrs) shown else gone)
            if (!osrs) return
            val wPx = ((g.tileWidthDp * g.cols) * dm.density).toInt()
            val title = "Josh Learn"
            val hs = com.josh.oldschool.OsPixelText.fitSize(c, title, 18f, 12f, wPx - (90 * dm.density).toInt(), bold = true)
            com.josh.oldschool.OsPixelText.render(c, title, hs, c.getColor(R.color.os_header), wPx, 1, true, false, true)?.let {
                v.setImageViewBitmap(R.id.widget_header_img, it); v.setViewVisibility(R.id.widget_header_img, shown); v.setContentDescription(R.id.widget_header_img, title) }
            com.josh.oldschool.OsRemote.pixelText(c, v, R.id.widget_due_img, "$due due", 13f, c.getColor(R.color.os_orange), wPx / 2)
            if (tod != null) {
                val tw = wPx - (28 * dm.density).toInt()
                com.josh.oldschool.OsRemote.pixelText(c, v, R.id.tod_title_img, "Topic of the day: ${tod.title}", 14f, c.getColor(R.color.os_chat_blue_dark), tw, 1, shadow = false)
                com.josh.oldschool.OsRemote.pixelText(c, v, R.id.tod_fact_img, TopicOfDay.line(c) ?: TopicOfDay.fallback(tod), 13f, c.getColor(R.color.os_ink), tw, 3, shadow = false)
            }
        }

        fun build(c: Context, g: Grid, widgetId: Int = 0): RemoteViews {
            val theme = Display.theme(c)
            val v = RemoteViews(c.packageName, R.layout.widget_topics)
            val due = runCatching { Repo.due().size }.getOrDefault(0)
            // Topic of the day (one per local date), with the cached fun fact or a fallback line
            val tod = if (g.showTopicOfDay) TopicOfDay.pick(c) else null
            styleFrame(c, v, theme, g, due, tod)
            v.setViewVisibility(R.id.widget_tod, if (tod != null) android.view.View.VISIBLE else android.view.View.GONE)
            if (tod != null) {
                v.setTextViewText(R.id.tod_title, "⭐ Topic of the day · ${tod.emoji} ${tod.title}")
                v.setTextViewText(R.id.tod_fact, TopicOfDay.line(c) ?: TopicOfDay.fallback(tod))
                v.setContentDescription(R.id.widget_tod, "Topic of the day: ${tod.title}")
                v.setOnClickPendingIntent(R.id.widget_tod, PendingIntent.getActivity(c, 92, Intent(c, MainActivity::class.java).setAction(ACTION_TOPIC)
                    .setData(Uri.parse("joshlearn://widget/tod")).putExtra(tod.extraKey, tod.extraValue)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            v.setInt(R.id.widget_grid, "setNumColumns", g.cols)
            if (Build.VERSION.SDK_INT >= 31) {
                val b = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2) /* Material tile + kit inventory slot */.setHasStableIds(true)
                items(c).forEachIndexed { i, it -> b.addItem(i.toLong(), tile(c, it, i, g)) }
                v.setRemoteAdapter(R.id.widget_grid, b.build())
            } else {
                val svc = Intent(c, TopicsWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra("cols", g.cols).putExtra("tw", g.tileWidthDp).putExtra("th", g.tileHeightDp)
                svc.data = Uri.parse("joshlearn://widget-svc/$widgetId/${g.cols}/${g.tileWidthDp}/${g.tileHeightDp}/$theme")
                @Suppress("DEPRECATION") v.setRemoteAdapter(R.id.widget_grid, svc)
                v.setEmptyView(R.id.widget_grid, R.id.widget_empty)
            }
            val template = Intent(c, MainActivity::class.java).setAction(ACTION_TOPIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            v.setPendingIntentTemplate(R.id.widget_grid, PendingIntent.getActivity(c, 100, template,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)))
            v.setTextViewText(R.id.widget_due, "🃏 $due due")
            v.setOnClickPendingIntent(R.id.widget_due, PendingIntent.getActivity(c, 90, Intent(c, ReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setOnClickPendingIntent(R.id.widget_due_img, PendingIntent.getActivity(c, 90, Intent(c, ReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setOnClickPendingIntent(R.id.widget_header_img, PendingIntent.getActivity(c, 91, Intent(c, MainActivity::class.java)
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
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
    }
}
