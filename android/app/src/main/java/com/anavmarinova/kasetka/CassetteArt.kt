package com.anavmarinova.kasetka

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.min
import kotlin.math.sqrt

data class Skin(val body: Int, val body2: Int, val label: Int, val s2: Int, val ink: Int, val window: Int, val hub: Int)

val SKINS = linkedMapOf(
    "classic" to Skin(0xFF2F5D8A.toInt(), 0xFF24486B.toInt(), 0xFFF3EAD2.toInt(), 0xFFF2A33A.toInt(), 0xFF1D2B44.toInt(), 0xD90C0F18.toInt(), 0xFFF4F4F4.toInt()),
    "sport" to Skin(0xFFF2C230.toInt(), 0xFFC99A12.toInt(), 0xFF1B1B1B.toInt(), 0xFFFFFFFF.toInt(), 0xFFF2C230.toInt(), 0xD90A0A0A.toInt(), 0xFFFFFFFF.toInt()),
    "cyber" to Skin(0xFF17172E.toInt(), 0xFF0B0B19.toInt(), 0xFF24244A.toInt(), 0xFF19F0FF.toInt(), 0xFF19F0FF.toInt(), 0xD9000000.toInt(), 0xFF19F0FF.toInt()),
    "clear" to Skin(0xB4CDDEF0.toInt(), 0xC896A8BE.toInt(), 0xFFFFFDF6.toInt(), 0xFF9A9A9A.toInt(), 0xFF222222.toInt(), 0x8C1E232D.toInt(), 0xFFFFFFFF.toInt()),
)

fun skinFor(s: Settings): Skin {
    val k = SKINS[s.skin] ?: SKINS.getValue("classic")
    return if (s.whimsy) k.copy(s2 = 0xFF8AF3FF.toInt()) else k
}

/** Draws the cassette (same artwork for the app and the home-screen widget). Coordinates are a 300×190 design. */
object CassetteArt {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("cursive", Typeface.BOLD)
        textSize = 17f
    }
    private val clip = Path()
    private val trap = Path()

    private fun fill(c: Int) = p.apply { style = Paint.Style.FILL; color = c }

    fun draw(c: Canvas, w: Float, h: Float, skin: Skin, name: String, tapeColor: Int,
             art: Bitmap?, progress: Float, angle: Float) {
        val s = min(w / 300f, h / 190f)
        c.save()
        c.translate((w - 300f * s) / 2f, (h - 190f * s) / 2f)
        c.scale(s, s)

        // shell
        c.drawRoundRect(RectF(4f, 4f, 296f, 186f), 12f, 12f, fill(skin.body))
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x4D000000
        c.drawRoundRect(RectF(4f, 4f, 296f, 186f), 12f, 12f, p)

        // label
        val label = RectF(20f, 14f, 280f, 128f)
        c.save()
        clip.reset(); clip.addRoundRect(label, 6f, 6f, Path.Direction.CW)
        c.clipPath(clip)
        c.drawRect(label, fill(skin.label))
        if (art != null) {
            val bw = art.width.toFloat(); val bh = art.height.toFloat()
            val target = label.width() / label.height()
            val src = if (bw / bh > target) {
                val cw = bh * target; Rect(((bw - cw) / 2).toInt(), 0, ((bw + cw) / 2).toInt(), bh.toInt())
            } else {
                val ch = bw / target; Rect(0, ((bh - ch) / 2).toInt(), bw.toInt(), ((bh + ch) / 2).toInt())
            }
            p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); p.isFilterBitmap = true
            c.drawBitmap(art, src, label, p)
            c.drawRect(RectF(20f, 14f, 280f, 46f), fill((skin.label and 0x00FFFFFF) or 0xEB000000.toInt()))
        } else {
            c.drawRect(RectF(20f, 112f, 280f, 119f), fill(tapeColor))
            c.drawRect(RectF(20f, 121f, 280f, 128f), fill(skin.s2))
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = (skin.ink and 0x00FFFFFF) or 0x40000000
            c.drawLine(30f, 40f, 270f, 40f, p)
        }
        tp.color = skin.ink; tp.textAlign = Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(name, tp, 215f, TextUtils.TruncateAt.END).toString(), 30f, 36f, tp)
        tp.color = tapeColor; tp.textAlign = Paint.Align.RIGHT
        c.drawText("A", 270f, 36f, tp)
        c.restore()

        // window, tape packs, reels
        c.drawRoundRect(RectF(76f, 50f, 224f, 110f), 30f, 30f, fill(skin.window))
        val pr = progress.coerceIn(0f, 1f)
        val rMin = 12f; val rMax = 26f
        val rl = sqrt(rMin * rMin + (rMax * rMax - rMin * rMin) * (1 - pr))
        val rr = sqrt(rMin * rMin + (rMax * rMax - rMin * rMin) * pr)
        c.drawCircle(112f, 80f, rl, fill(0xFF3B2A20.toInt()))
        c.drawCircle(188f, 80f, rr, fill(0xFF3B2A20.toInt()))
        for (cx in floatArrayOf(112f, 188f)) {
            c.save()
            c.rotate(angle, cx, 80f)
            c.drawCircle(cx, 80f, 11f, fill(skin.hub))
            c.drawCircle(cx, 80f, 4.5f, fill(0xFF333333.toInt()))
            for (k in 0 until 6) {
                c.save(); c.rotate(k * 60f, cx, 80f)
                c.drawRoundRect(RectF(cx - 1.5f, 69f, cx + 1.5f, 74f), 1f, 1f, fill(0xFF555555.toInt()))
                c.restore()
            }
            c.restore()
        }
        c.drawRoundRect(RectF(80f, 52f, 220f, 70f), 9f, 9f, fill(0x14FFFFFF))

        // bottom guide, holes, screws
        trap.reset(); trap.moveTo(66f, 186f); trap.lineTo(82f, 146f); trap.lineTo(218f, 146f); trap.lineTo(234f, 186f); trap.close()
        c.drawPath(trap, fill(skin.body2))
        c.drawCircle(100f, 172f, 5f, fill(0x80000000.toInt()))
        c.drawCircle(200f, 172f, 5f, fill(0x80000000.toInt()))
        c.drawRect(RectF(127f, 160f, 136f, 169f), fill(0x73000000))
        c.drawRect(RectF(164f, 160f, 173f, 169f), fill(0x73000000))
        for ((x, y) in listOf(14f to 14f, 286f to 14f, 14f to 176f, 286f to 176f, 150f to 156f))
            c.drawCircle(x, y, 3.5f, fill(0x4D000000))
        c.restore()
    }

    fun bitmap(w: Int, h: Int, skin: Skin, name: String, tapeColor: Int, art: Bitmap?, progress: Float): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), w.toFloat(), h.toFloat(), skin, name, tapeColor, art, progress, 0f)
        return b
    }
}
