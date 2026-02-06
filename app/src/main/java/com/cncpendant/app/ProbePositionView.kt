package com.cncpendant.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ProbePositionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Selection {
        CORNER, X_SIDE, Y_SIDE, CENTER
    }

    interface OnProbePositionSelectedListener {
        fun onPositionSelected(selection: Selection, corner: String?, side: String?)
    }

    var selectedCorner: String? = null
        set(value) { field = value; invalidate() }

    var selectedSide: String? = null
        set(value) { field = value; invalidate() }

    var selectedCenter: Boolean = false
        set(value) { field = value; invalidate() }

    var probeType: String = "3d-probe"
        set(value) { field = value; invalidate() }

    var listener: OnProbePositionSelectedListener? = null

    // Colors matching ncSender
    private val bgColor = Color.parseColor("#1a1a2e")
    private val blockTopColor = Color.parseColor("#deb887")       // burlywood
    private val blockFrontColor = Color.parseColor("#b8944a")     // darker front
    private val blockRightColor = Color.parseColor("#c8a458")     // medium right
    private val blockEdgeColor = Color.parseColor("#8a6e30")      // edge lines
    private val faceDefaultColor = Color.parseColor("#555555")    // unselected corner/side faces
    private val faceDefaultDarkColor = Color.parseColor("#444444")
    private val highlightColor = Color.parseColor("#1abc9c")      // teal accent
    private val highlightDarkColor = Color.parseColor("#17a689")  // teal darker for side faces

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor; style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blockEdgeColor; style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#607080"); textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Isometric projection
    private val cos30 = Math.cos(Math.toRadians(30.0)).toFloat()
    private val sin30 = 0.5f

    private var originX = 0f
    private var originY = 0f
    private var isoScale = 1f

    // Block world dimensions
    private val bw = 1f    // X
    private val bd = 1f    // Y (depth)
    private val bh = 0.25f // Z (height) - flatter like ncSender

    // Hit regions (invisible touch areas on top face)
    private val cornerHitPts = mutableMapOf<String, FloatArray>()
    private val sideHitPts = mutableMapOf<String, FloatArray>()
    private var centerHitPts = FloatArray(0)

    private fun toScreen(wx: Float, wy: Float, wz: Float): FloatArray {
        val sx = originX + (wx - wy) * cos30 * isoScale
        val sy = originY - wz * isoScale + (wx + wy) * sin30 * isoScale
        return floatArrayOf(sx, sy)
    }

    private fun makePath(vararg worldPts: FloatArray): Pair<Path, FloatArray> {
        val path = Path()
        val pts = FloatArray(worldPts.size * 2)
        for (i in worldPts.indices) {
            val sp = toScreen(worldPts[i][0], worldPts[i][1], worldPts[i][2])
            pts[i * 2] = sp[0]; pts[i * 2 + 1] = sp[1]
            if (i == 0) path.moveTo(sp[0], sp[1]) else path.lineTo(sp[0], sp[1])
        }
        path.close()
        return Pair(path, pts)
    }

    private fun fillPath(canvas: Canvas, path: Path, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawPath(path, p)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bgPaint)

        // Scale to fit with room for tool above
        val isoW = (bw + bd) * cos30
        val isoH = (bw + bd) * sin30 + bh
        val pad = 55f
        val toolSpace = 90f
        isoScale = minOf((w - pad * 2) / isoW, (h - pad - toolSpace - pad) / isoH)

        val topY = -bh * isoScale
        val botY = (bw + bd) * sin30 * isoScale
        val leftX = -bd * cos30 * isoScale
        val rightX = bw * cos30 * isoScale
        originX = w / 2f - (leftX + rightX) / 2f
        originY = (h + toolSpace) / 2f - (topY + botY) / 2f

        // === DRAW SOLID BLOCK ===
        // The block is one solid piece. We draw 3 visible faces, then overlay
        // teal highlights on the corner/side portions of the front & right faces.

        val s = bw; val d = bd; val hz = bh
        val sel = selectedCorner ?: selectedSide

        // --- FRONT FACE (Y=0) ---
        // Split into 3 vertical sections: left corner, middle, right corner
        val cornerFrac = 0.35f

        // Front-left section
        val flColor = when {
            sel == "BottomLeft" -> highlightDarkColor
            sel == "Front" -> highlightDarkColor
            else -> blockFrontColor
        }
        val (flP, _) = makePath(f(0f,0f,0f), f(s*cornerFrac,0f,0f), f(s*cornerFrac,0f,hz), f(0f,0f,hz))
        fillPath(canvas, flP, flColor)

        // Front-center section
        val fcColor = if (sel == "Front") highlightDarkColor else blockFrontColor
        val (fcP, _) = makePath(f(s*cornerFrac,0f,0f), f(s*(1-cornerFrac),0f,0f), f(s*(1-cornerFrac),0f,hz), f(s*cornerFrac,0f,hz))
        fillPath(canvas, fcP, fcColor)

        // Front-right section
        val frColor = when {
            sel == "BottomRight" -> highlightDarkColor
            sel == "Front" -> highlightDarkColor
            else -> blockFrontColor
        }
        val (frP, _) = makePath(f(s*(1-cornerFrac),0f,0f), f(s,0f,0f), f(s,0f,hz), f(s*(1-cornerFrac),0f,hz))
        fillPath(canvas, frP, frColor)

        // Front face outline
        val (frontOutline, _) = makePath(f(0f,0f,0f), f(s,0f,0f), f(s,0f,hz), f(0f,0f,hz))
        canvas.drawPath(frontOutline, edgePaint)

        // --- RIGHT FACE (X=s) ---
        // Split into 3 vertical sections: front corner, middle, back corner
        val rfColor = when {
            sel == "BottomRight" -> highlightColor
            sel == "Right" -> highlightColor
            else -> blockRightColor
        }
        val (rfP, _) = makePath(f(s,0f,0f), f(s,d*cornerFrac,0f), f(s,d*cornerFrac,hz), f(s,0f,hz))
        fillPath(canvas, rfP, rfColor)

        val rcColor = if (sel == "Right") highlightColor else blockRightColor
        val (rcP, _) = makePath(f(s,d*cornerFrac,0f), f(s,d*(1-cornerFrac),0f), f(s,d*(1-cornerFrac),hz), f(s,d*cornerFrac,hz))
        fillPath(canvas, rcP, rcColor)

        val rbColor = when {
            sel == "TopRight" -> highlightColor
            sel == "Right" -> highlightColor
            else -> blockRightColor
        }
        val (rbP, _) = makePath(f(s,d*(1-cornerFrac),0f), f(s,d,0f), f(s,d,hz), f(s,d*(1-cornerFrac),hz))
        fillPath(canvas, rbP, rbColor)

        // Right face outline
        val (rightOutline, _) = makePath(f(s,0f,0f), f(s,d,0f), f(s,d,hz), f(s,0f,hz))
        canvas.drawPath(rightOutline, edgePaint)

        // --- TOP FACE (Z=hz) ---
        // Draw as one solid piece in burlywood
        val (topPath, _) = makePath(f(0f,0f,hz), f(s,0f,hz), f(s,d,hz), f(0f,d,hz))
        fillPath(canvas, topPath, blockTopColor)

        // Draw teal highlights on top face edges for left/back selections (hidden faces)
        if (sel == "Left" || sel == "BottomLeft" || sel == "TopLeft") {
            val strip = s * 0.06f
            val (lp, _) = makePath(f(0f,0f,hz), f(strip,0f,hz), f(strip,d,hz), f(0f,d,hz))
            fillPath(canvas, lp, highlightColor)
        }
        if (sel == "Back" || sel == "TopLeft" || sel == "TopRight") {
            val strip = d * 0.06f
            val (bp, _) = makePath(f(0f,d-strip,hz), f(s,d-strip,hz), f(s,d,hz), f(0f,d,hz))
            fillPath(canvas, bp, highlightColor)
        }
        if (selectedCenter) {
            val m = 0.25f
            val (cp, _) = makePath(f(m,m,hz), f(s-m,m,hz), f(s-m,d-m,hz), f(m,d-m,hz))
            fillPath(canvas, cp, highlightColor)
        }

        // Top face outline
        canvas.drawPath(topPath, edgePaint)

        // === BUILD HIT REGIONS (invisible, for touch only) ===
        val cs = 0.3f  // corner region fraction
        val mid = s / 2f; val midD = d / 2f

        cornerHitPts.clear(); sideHitPts.clear()
        storePts(cornerHitPts, "TopLeft", f(0f,d,hz), f(cs*s,d,hz), f(cs*s,d-cs*d,hz), f(0f,d-cs*d,hz))
        storePts(cornerHitPts, "TopRight", f(s-cs*s,d,hz), f(s,d,hz), f(s,d-cs*d,hz), f(s-cs*s,d-cs*d,hz))
        storePts(cornerHitPts, "BottomLeft", f(0f,cs*d,hz), f(cs*s,cs*d,hz), f(cs*s,0f,hz), f(0f,0f,hz))
        storePts(cornerHitPts, "BottomRight", f(s-cs*s,cs*d,hz), f(s,cs*d,hz), f(s,0f,hz), f(s-cs*s,0f,hz))

        val sl = 0.4f; val st = 0.18f
        val hsl = sl * s / 2f; val hsld = sl * d / 2f
        storePts(sideHitPts, "Back", f(mid-hsl,d,hz), f(mid+hsl,d,hz), f(mid+hsl,d-st*d,hz), f(mid-hsl,d-st*d,hz))
        storePts(sideHitPts, "Front", f(mid-hsl,st*d,hz), f(mid+hsl,st*d,hz), f(mid+hsl,0f,hz), f(mid-hsl,0f,hz))
        storePts(sideHitPts, "Left", f(0f,midD+hsld,hz), f(st*s,midD+hsld,hz), f(st*s,midD-hsld,hz), f(0f,midD-hsld,hz))
        storePts(sideHitPts, "Right", f(s-st*s,midD+hsld,hz), f(s,midD+hsld,hz), f(s,midD-hsld,hz), f(s-st*s,midD-hsld,hz))

        val czs = 0.25f
        val (_, cpts) = makePath(f(mid-czs,midD+czs,hz), f(mid+czs,midD+czs,hz), f(mid+czs,midD-czs,hz), f(mid-czs,midD-czs,hz))
        centerHitPts = cpts

        // === DRAW PROBE TOOL ===
        drawProbeToolAtSelection(canvas, s, d, hz)

        // === AXIS LABELS ===
        val ypp = toScreen(mid, d + 0.12f, hz)
        canvas.drawText("Y+", ypp[0], ypp[1], labelPaint)
        val ymp = toScreen(mid, -0.12f, hz)
        canvas.drawText("Y-", ymp[0], ymp[1] + labelPaint.textSize * 0.4f, labelPaint)
        val xmp = toScreen(-0.12f, midD, hz)
        val xmlp = Paint(labelPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("X-", xmp[0], xmp[1] + labelPaint.textSize * 0.3f, xmlp)
        val xpp = toScreen(s + 0.12f, midD, hz)
        val xplp = Paint(labelPaint).apply { textAlign = Paint.Align.LEFT }
        canvas.drawText("X+", xpp[0], xpp[1] + labelPaint.textSize * 0.3f, xplp)
    }

    // Helper to create float array for world point
    private fun f(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun storePts(map: MutableMap<String, FloatArray>, name: String, vararg worldPts: FloatArray) {
        val (_, pts) = makePath(*worldPts)
        map[name] = pts
    }

    // === PROBE TOOL DRAWING ===

    private fun getToolWorldPos(): FloatArray? {
        val s = bw; val d = bd; val hz = bh
        return when {
            selectedCorner == "BottomLeft" -> floatArrayOf(0f, 0f, hz)
            selectedCorner == "BottomRight" -> floatArrayOf(s, 0f, hz)
            selectedCorner == "TopLeft" -> floatArrayOf(0f, d, hz)
            selectedCorner == "TopRight" -> floatArrayOf(s, d, hz)
            selectedSide == "Front" -> floatArrayOf(s / 2f, 0f, hz)
            selectedSide == "Back" -> floatArrayOf(s / 2f, d, hz)
            selectedSide == "Left" -> floatArrayOf(0f, d / 2f, hz)
            selectedSide == "Right" -> floatArrayOf(s, d / 2f, hz)
            selectedCenter -> floatArrayOf(s / 2f, d / 2f, hz)
            else -> null
        }
    }

    private fun drawProbeToolAtSelection(canvas: Canvas, s: Float, d: Float, hz: Float) {
        val wp = getToolWorldPos() ?: return
        val screenBase = toScreen(wp[0], wp[1], wp[2])

        when (probeType) {
            "3d-probe" -> draw3DProbe(canvas, screenBase[0], screenBase[1])
            "standard-block" -> drawStdBlock(canvas, wp[0], wp[1], hz)
            "autozero-touch" -> drawAutoZero(canvas, wp[0], wp[1], hz)
        }
    }

    private fun draw3DProbe(canvas: Canvas, sx: Float, sy: Float) {
        val u = isoScale * 0.038f

        // Ball tip
        val ballR = u * 2.5f
        val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#c0c0c0"); style = Paint.Style.FILL }
        val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#404040"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        canvas.drawCircle(sx, sy, ballR, ballPaint)
        canvas.drawCircle(sx, sy, ballR, outPaint)

        // Stem
        val stemW = u * 1.2f; val stemH = u * 5f
        val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); style = Paint.Style.FILL }
        canvas.drawRect(sx - stemW/2, sy - stemH, sx + stemW/2, sy - ballR*0.4f, stemPaint)

        // Probe body
        val bodyW = u * 7f; val bodyH = u * 10f
        val bodyTop = sy - stemH - bodyH
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#606060"); style = Paint.Style.FILL }
        val bodyRect = RectF(sx - bodyW/2, bodyTop, sx + bodyW/2, sy - stemH)
        val bp = Path(); bp.addRoundRect(bodyRect, bodyW/3, bodyW/3, Path.Direction.CW)
        canvas.drawPath(bp, bodyPaint)
        // Highlight strip
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#505050"); style = Paint.Style.FILL }
        canvas.drawRect(sx + bodyW*0.05f, bodyTop + bodyW/3, sx + bodyW/4, sy - stemH - bodyW/3, hlPaint)
        canvas.drawPath(bp, outPaint)

        // LED
        val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5cb85c"); style = Paint.Style.FILL }
        canvas.drawCircle(sx, bodyTop + bodyH * 0.22f, u * 1.5f, ledPaint)

        // Collar
        val collarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#707070"); style = Paint.Style.FILL }
        val colH = u * 2f; val colW = bodyW * 1.1f
        canvas.drawRect(sx - colW/2, bodyTop - colH, sx + colW/2, bodyTop, collarPaint)
        canvas.drawRect(sx - colW/2, bodyTop - colH, sx + colW/2, bodyTop, outPaint)

        // Spindle
        drawSpindle(canvas, sx, bodyTop - colH, u)
    }

    private fun drawStdBlock(canvas: Canvas, wx: Float, wy: Float, hz: Float) {
        val u = isoScale * 0.038f

        // Standard block sits on the workpiece corner, flush with edges
        // Block dimensions in world space
        val bkW = 0.28f; val bkD = 0.28f; val bkH = 0.07f

        // Position block so it's flush with the selected corner/side edge
        var x0 = wx - bkW / 2f; var y0 = wy - bkD / 2f

        when {
            selectedCorner == "BottomLeft" -> { x0 = wx; y0 = wy }
            selectedCorner == "BottomRight" -> { x0 = wx - bkW; y0 = wy }
            selectedCorner == "TopLeft" -> { x0 = wx; y0 = wy - bkD }
            selectedCorner == "TopRight" -> { x0 = wx - bkW; y0 = wy - bkD }
            selectedSide == "Front" -> { y0 = wy }
            selectedSide == "Back" -> { y0 = wy - bkD }
            selectedSide == "Left" -> { x0 = wx }
            selectedSide == "Right" -> { x0 = wx - bkW }
        }

        // Draw block as isometric box
        val bkColor = Color.parseColor("#e8e8e8")
        val bkFront = Color.parseColor("#c8c8c8")
        val bkRight = Color.parseColor("#d8d8d8")
        val bkOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); style = Paint.Style.STROKE; strokeWidth = 1f; strokeJoin = Paint.Join.ROUND }

        val (tp, _) = makePath(f(x0,y0,hz+bkH), f(x0+bkW,y0,hz+bkH), f(x0+bkW,y0+bkD,hz+bkH), f(x0,y0+bkD,hz+bkH))
        val (fp, _) = makePath(f(x0,y0,hz), f(x0+bkW,y0,hz), f(x0+bkW,y0,hz+bkH), f(x0,y0,hz+bkH))
        val (rp, _) = makePath(f(x0+bkW,y0,hz), f(x0+bkW,y0+bkD,hz), f(x0+bkW,y0+bkD,hz+bkH), f(x0+bkW,y0,hz+bkH))

        fillPath(canvas, tp, bkColor)
        fillPath(canvas, fp, bkFront)
        fillPath(canvas, rp, bkRight)
        canvas.drawPath(tp, bkOut); canvas.drawPath(fp, bkOut); canvas.drawPath(rp, bkOut)

        // Bit above center of block
        val bitPos = toScreen(x0 + bkW/2f, y0 + bkD/2f, hz + bkH)
        drawBitAndSpindle(canvas, bitPos[0], bitPos[1], u)
    }

    private fun drawAutoZero(canvas: Canvas, wx: Float, wy: Float, hz: Float) {
        val u = isoScale * 0.038f

        // Puck position - flush with corner/side
        var px = wx; var py = wy
        val puckR = 0.14f; val puckH = 0.04f

        when {
            selectedCorner == "BottomLeft" -> { px = wx + puckR * 0.7f; py = wy + puckR * 0.7f }
            selectedCorner == "BottomRight" -> { px = wx - puckR * 0.7f; py = wy + puckR * 0.7f }
            selectedCorner == "TopLeft" -> { px = wx + puckR * 0.7f; py = wy - puckR * 0.7f }
            selectedCorner == "TopRight" -> { px = wx - puckR * 0.7f; py = wy - puckR * 0.7f }
            selectedSide == "Front" -> { py = wy + puckR * 0.7f }
            selectedSide == "Back" -> { py = wy - puckR * 0.7f }
            selectedSide == "Left" -> { px = wx + puckR * 0.7f }
            selectedSide == "Right" -> { px = wx - puckR * 0.7f }
        }

        val topC = toScreen(px, py, hz + puckH)
        val botC = toScreen(px, py, hz)

        val rx = puckR * cos30 * isoScale
        val ry = puckR * sin30 * isoScale

        // Puck side
        val puckSide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444444"); style = Paint.Style.FILL }
        val puckTop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#d3d3d3"); style = Paint.Style.FILL }
        val puckOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 1.5f }

        // Draw cylinder side band
        val sideBand = Path()
        sideBand.addArc(RectF(topC[0]-rx, topC[1]-ry, topC[0]+rx, topC[1]+ry), 0f, 180f)
        sideBand.lineTo(botC[0]-rx, botC[1]+ry)
        sideBand.addArc(RectF(botC[0]-rx, botC[1]-ry, botC[0]+rx, botC[1]+ry), 180f, -180f)
        sideBand.close()
        canvas.drawPath(sideBand, puckSide)
        canvas.drawPath(sideBand, puckOut)

        // Puck top ellipse
        canvas.drawOval(RectF(topC[0]-rx, topC[1]-ry, topC[0]+rx, topC[1]+ry), puckTop)
        canvas.drawOval(RectF(topC[0]-rx, topC[1]-ry, topC[0]+rx, topC[1]+ry), puckOut)

        // Bit above puck
        drawBitAndSpindle(canvas, topC[0], topC[1], u)
    }

    private fun drawBitAndSpindle(canvas: Canvas, sx: Float, sy: Float, u: Float) {
        val bitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#909090"); style = Paint.Style.FILL }
        val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#555555"); style = Paint.Style.STROKE; strokeWidth = 1f }
        val colPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#707070"); style = Paint.Style.FILL }

        // Bit
        val bitW = u * 3f; val bitH = u * 7f
        canvas.drawRect(sx-bitW/2, sy-bitH, sx+bitW/2, sy, bitPaint)
        canvas.drawRect(sx-bitW/2, sy-bitH, sx+bitW/2, sy, outPaint)

        // Collet
        val colW = u * 5f; val colH = u * 2.5f
        val colTop = sy - bitH - colH
        canvas.drawRect(sx-colW/2, colTop, sx+colW/2, sy-bitH, colPaint)
        canvas.drawRect(sx-colW/2, colTop, sx+colW/2, sy-bitH, outPaint)

        drawSpindle(canvas, sx, colTop, u)
    }

    private fun drawSpindle(canvas: Canvas, cx: Float, bottomY: Float, u: Float) {
        val spW = u * 10f; val spH = u * 12f
        val spTop = bottomY - spH
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#505050"); style = Paint.Style.FILL }
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#606060"); style = Paint.Style.FILL }
        val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#383838"); style = Paint.Style.STROKE; strokeWidth = 1.5f }

        val rect = RectF(cx-spW/2, spTop, cx+spW/2, bottomY)
        val p = Path(); p.addRoundRect(rect, spW/4, spW/4, Path.Direction.CW)
        canvas.drawPath(p, bodyPaint)
        canvas.drawRect(cx-spW/6, spTop+spW/4, cx+spW/8, bottomY-spW/4, hlPaint)
        canvas.drawPath(p, outPaint)
    }

    // === TOUCH HANDLING ===

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val x = event.x; val y = event.y

        if (pointInPolygon(x, y, centerHitPts)) {
            clearSelections(); selectedCenter = true
            listener?.onPositionSelected(Selection.CENTER, null, null); return true
        }
        for ((name, pts) in cornerHitPts) {
            if (pointInPolygon(x, y, pts)) {
                clearSelections(); selectedCorner = name
                listener?.onPositionSelected(Selection.CORNER, name, null); return true
            }
        }
        for ((name, pts) in sideHitPts) {
            if (pointInPolygon(x, y, pts)) {
                clearSelections(); selectedSide = name
                val t = if (name == "Left" || name == "Right") Selection.X_SIDE else Selection.Y_SIDE
                listener?.onPositionSelected(t, null, name); return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun pointInPolygon(px: Float, py: Float, pts: FloatArray): Boolean {
        val n = pts.size / 2; if (n < 3) return false
        var inside = false; var j = n - 1
        for (i in 0 until n) {
            val xi = pts[i*2]; val yi = pts[i*2+1]; val xj = pts[j*2]; val yj = pts[j*2+1]
            if (((yi > py) != (yj > py)) && (px < (xj-xi)*(py-yi)/(yj-yi)+xi)) inside = !inside
            j = i
        }
        return inside
    }

    private fun clearSelections() { selectedCorner = null; selectedSide = null; selectedCenter = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (width * 0.82).toInt()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(width, height)
    }
}
