package com.phantom.recorder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Environment
import android.widget.RemoteViews
import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 2x2 home-screen widget: last 24 h of recording activity, drawn as one bitmap with 28 dp corners. */
class PhantomWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    companion object {
        private const val SLOTS = 48
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val SLOT_MS = DAY_MS / SLOTS

        fun refresh(ctx: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PhantomWidget::class.java))
                ids.forEach { update(ctx, mgr, it) }
            }
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val o = mgr.getAppWidgetOptions(id)
            val d = ctx.resources.displayMetrics.density
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 160
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: wDp
            val px = (min(wDp, hDp) * d).toInt().coerceIn(200, 700)
            val bmp = try { render(ctx, px, d) } catch (e: Throwable) { null }

            val views = RemoteViews(ctx.packageName, R.layout.widget_phantom)
            if (bmp != null) views.setImageViewBitmap(R.id.widget_img, bmp)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            mgr.updateAppWidget(id, views)
        }

        // ---------- data ----------
        private class Stats(
            val slots: FloatArray, val count24: Int, val min24: Float,
            val todayMin: Float, val todayCount: Int, val todayTranscribed: Int
        )

        private fun load(ctx: Context, now: Long): Stats {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            val ws = now - DAY_MS
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val slots = FloatArray(SLOTS)
            var count = 0; var total = 0f; var tMin = 0f; var tCount = 0; var tTx = 0
            dir.listFiles { f -> f.extension == "m4a" && f.lastModified() >= ws }?.forEach { f ->
                val end = f.lastModified()
                val dur = duration(f)
                val start = end - dur
                count++
                val cs = max(start, ws); val ce = min(end, now)
                if (ce > cs) {
                    total += (ce - cs) / 60000f
                    var i = ((cs - ws) / SLOT_MS).toInt()
                    while (i < SLOTS) {
                        val s0 = ws + i * SLOT_MS; val s1 = s0 + SLOT_MS
                        if (s0 >= ce) break
                        val ov = min(ce, s1) - max(cs, s0)
                        if (ov > 0) slots[i] += ov / 60000f
                        i++
                    }
                }
                if (end >= midnight) {
                    tCount++
                    tMin += (end - max(start, midnight)) / 60000f
                    if (File(f.path + ".txt").exists()) tTx++
                }
            }
            return Stats(slots, count, total, tMin, tCount, tTx)
        }

        private fun duration(f: File): Long {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(f.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) { 0L } finally { runCatching { r.release() } }
        }

        // ---------- drawing ----------
        private fun render(ctx: Context, s: Int, density: Float): Bitmap {
            val now = System.currentTimeMillis()
            val st = load(ctx, now)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val w = s.toFloat(); val h = s.toFloat(); val m = s.toFloat()

            // exact 28 dp rounded corners
            val r = 28f * density
            c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) })
            c.drawColor(0xFF000000.toInt())

            val gray = 0xFF666666.toInt()
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = .09f * m; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gray; textSize = .072f * m; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val px = .09f * w
            c.drawText(st.count24.toString(), px, .175f * h, big)
            c.drawText(if (st.count24 == 1) "Recording" else "Recordings", px, .255f * h, small)
            c.drawText("%.1f".format(st.min24), px, .35f * h, big)
            c.drawText("min", px, .43f * h, small)

            // activity rings: minutes today / 60, recordings today / 5, transcribed share
            val cx = .80f * w; val cy = .19f * h
            val progress = floatArrayOf(
                (st.todayMin / 60f).coerceIn(0f, 1f),
                (st.todayCount / 5f).coerceIn(0f, 1f),
                if (st.todayCount > 0) st.todayTranscribed.toFloat() / st.todayCount else 0f
            )
            val radii = floatArrayOf(.095f * m, .07f * m, .045f * m)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .011f * m; color = 0xFF3D3D3D.toInt() }
            val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .016f * m; color = 0xFFFFFFFF.toInt() }
            for (i in 0..2) {
                val rr = radii[i]
                c.drawCircle(cx, cy, rr, track)
                if (progress[i] > 0f) c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), -90f, 360f * progress[i], false, prog)
            }

            // bars: 48 half-hour slots across the last 24 h
            val base = .80f * h; val hMax = .29f * h
            val step = w / SLOTS
            val maxV = max(st.slots.max(), 5f)
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = max(2f, .0055f * m) }
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A4A4A.toInt(); strokeWidth = max(1f, .003f * m) }
            for (i in 0 until SLOTS) {
                val x = (i + .5f) * step
                val v = st.slots[i]
                if (v > 0f) c.drawLine(x, base, x, base - hMax * sqrt(v / maxV).coerceIn(.08f, 1f), bar)
                else c.drawLine(x, base, x, base - .02f * h, tick)
            }
            c.drawLine(0f, base, w, base, Paint().apply { color = 0xFFFFFFFF.toInt(); strokeWidth = max(2f, .006f * m) })

            // time labels at real 6-hour marks
            val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF5A5A5A.toInt(); textSize = .062f * m; textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val ws = now - DAY_MS
            val cal = Calendar.getInstance().apply { timeInMillis = ws; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            while (cal.timeInMillis < ws || cal.get(Calendar.HOUR_OF_DAY) % 6 != 0) cal.add(Calendar.HOUR_OF_DAY, 1)
            while (cal.timeInMillis <= now) {
                val x = (cal.timeInMillis - ws).toFloat() / DAY_MS * w
                val hr = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
                val text = "$hr:00"
                val half = lab.measureText(text) / 2
                c.drawText(text, x.coerceIn(half + .03f * w, w - half - .03f * w), .875f * h + lab.textSize / 3, lab)
                cal.add(Calendar.HOUR_OF_DAY, 6)
            }
            return bmp
        }
    }
}
