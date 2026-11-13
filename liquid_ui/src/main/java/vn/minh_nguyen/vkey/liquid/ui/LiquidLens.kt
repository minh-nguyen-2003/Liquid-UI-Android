package vn.minh_nguyen.vkey.liquid.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Thấu kính thật dùng chung cho [LiquidLayout] ([LiquidGlassMode.LENS]) và [GlassLayout] cùng các lớp con ([GlassMode.LENS]): mặt kính
 * phẳng, vành mép vát tròn. Nội dung phía sau [view] hiện nét nguyên ở giữa (hoặc mờ đều nếu đặt độ mờ), riêng vành mép bị bẻ cong theo
 * đúng hình kính (tròn, pill hay chữ nhật bo góc).
 *
 * [capture] = ảnh nét của phần giao diện nằm đúng sau view; [lens] = ảnh đó sau khi bẻ cong, là thứ view vẽ ra. Cả hai cỡ view x
 * [lensScale] (view rất lớn thì thu nhỏ để giữ chi phí). Bẻ cong bằng lưới tam giác: đỉnh lưới [meshVerts] trải đều trên [lens], mỗi đỉnh
 * lấy màu ở [meshTexs] trên [capture] — vị trí đã khúc xạ, tính sẵn theo hình kính, chỉ tính lại khi view đổi cỡ hoặc đổi thông số. Nội
 * dung hai ảnh đổi tại chỗ trước mỗi lượt vẽ của cửa sổ nên view không cần vẽ lại.
 *
 * View chủ gọi [update] từ `onPreDraw`, [draw] từ `onDraw`, [release] khi rời cửa sổ / tắt chế độ thấu kính; và tự bỏ qua mình khi gặp
 * [GlassBackdrop.CaptureCanvas] trong `draw` để không lẫn vào hình nhìn qua chính nó.
 */
internal class LiquidLens(private val view: View) {

    private var capture: Bitmap? = null
    private var captureCanvas: GlassBackdrop.CaptureCanvas? = null
    private var lens: Bitmap? = null
    private var lensCanvas: Canvas? = null
    private var lensScale = 1f
    private var ready = false

    private var meshVerts = FloatArray(0)
    private var meshTexs = FloatArray(0)
    private var meshIndices = ShortArray(0)
    private var meshDirty = true

    /** Độ mờ đang dùng (độ lệch chuẩn, px của view); 0 = nét. */
    private var blur = 0f

    /** Thông số của lưới đang dùng: bề rộng vành, độ bẻ cong, độ phóng, bán kính bo. */
    private var bevelWidth = 0f
    private var refraction = 0f
    private var zoom = 1f
    private var cornerRadius = 0f

    private var lastRenderAt = 0L
    private var drawnX = Int.MIN_VALUE
    private var drawnY = Int.MIN_VALUE
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lensPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clipPath = Path()
    private val viewLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    /** Bỏ hai ảnh và lưới (view rời cửa sổ, tắt chế độ thấu kính). */
    fun release() {
        capture = null
        captureCanvas = null
        lens = null
        lensCanvas = null
        ready = false
        meshDirty = true
    }

    /**
     * Gọi trước mỗi lượt vẽ của cửa sổ (có gì đó phía sau vừa đổi, hoặc view vừa hiện / đổi chỗ): chụp lại nền của [root] và bẻ cong lại,
     * tối đa một lần mỗi [MIN_INTERVAL_MS]; view vừa đổi chỗ / đổi cỡ / đổi thông số thì làm ngay. Màn hình đứng yên thì không được gọi nên
     * không tốn gì.
     *
     * [bevelWidth] = bề rộng vành mép bị bẻ cong (px), [refraction] = sát mép hiện phần nền nằm sâu vào trong chừng này lần bề rộng vành,
     * [zoom] = phóng nền quanh tâm (từ 1), [cornerRadius] = bán kính bo thực tế của hình kính (px), [blur] = độ mờ của nền nhìn qua kính
     * (độ lệch chuẩn của mờ Gauss, px; 0 = nét).
     */
    fun update(root: View, bevelWidth: Float, refraction: Float, zoom: Float, cornerRadius: Float, blur: Float = 0f) {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return
        if (blur != this.blur) {
            this.blur = blur.coerceAtLeast(0f)
            // Nội dung phải dựng lại với độ mờ mới (cỡ ảnh cũng có thể đổi theo)
            meshDirty = true
        }
        val fresh = ensureBitmaps()
        if (bevelWidth != this.bevelWidth || refraction != this.refraction || zoom != this.zoom || cornerRadius != this.cornerRadius) {
            this.bevelWidth = bevelWidth
            this.refraction = refraction
            this.zoom = zoom
            this.cornerRadius = cornerRadius
            meshDirty = true
        }
        view.getLocationOnScreen(viewLocation)
        val moved = viewLocation[0] != drawnX || viewLocation[1] != drawnY
        if (fresh || meshDirty || moved || SystemClock.uptimeMillis() - lastRenderAt >= MIN_INTERVAL_MS) render(root)
    }

    /**
     * Vẽ hình nhìn qua thấu kính lên khung [width] x [height] của view. [clip] = tự cắt theo hình kính bo [radius] (view đã cắt sẵn bằng
     * `clipToOutline` thì không cần). Chưa chụp được nền thì không vẽ gì.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, radius: Float, clip: Boolean) {
        val bent = lens?.takeIf { ready } ?: return
        bounds.set(0f, 0f, width, height)
        if (!clip) {
            canvas.drawBitmap(bent, null, bounds, lensPaint)
            return
        }
        val save = canvas.save()
        clipPath.rewind()
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawBitmap(bent, null, bounds, lensPaint)
        canvas.restoreToCount(save)
    }

    /** Cấp hai ảnh theo cỡ view hiện tại; trả về `true` nếu vừa cấp mới (đổi cỡ, lần đầu). */
    private fun ensureBitmaps(): Boolean {
        var scale = min(1f, sqrt(MAX_PIXELS / (view.width.toFloat() * view.height)))
        // Nền đã mờ thì không cần độ phân giải thật: chụp nhỏ lại theo độ mờ cho nhẹ (mờ càng nhiều ảnh càng nhỏ, tối đa BLUR_MAX_SHRINK lần)
        if (blur > 0f) scale = min(scale, 1f / (blur / BLUR_SHRINK_STEP_PX).coerceIn(1f, BLUR_MAX_SHRINK))
        val lensWidth = ceil(view.width * scale).toInt().coerceAtLeast(1)
        val lensHeight = ceil(view.height * scale).toInt().coerceAtLeast(1)
        val current = lens
        if (current != null && current.width == lensWidth && current.height == lensHeight) return false
        val raw = Bitmap.createBitmap(lensWidth, lensHeight, Bitmap.Config.ARGB_8888)
        val bent = Bitmap.createBitmap(lensWidth, lensHeight, Bitmap.Config.ARGB_8888)
        capture = raw
        captureCanvas = GlassBackdrop.CaptureCanvas(raw)
        lens = bent
        lensCanvas = Canvas(bent)
        lensScale = scale
        ready = false
        meshDirty = true
        return true
    }

    /**
     * Chụp nét phần giao diện nằm sau view vào [capture] rồi bẻ cong sang [lens]. Vị trí tính theo màn hình để gốc nền ở cửa sổ khác (màn
     * phía sau hộp thoại) vẫn khớp.
     */
    private fun render(root: View) {
        val raw = capture ?: return
        val rawCanvas = captureCanvas ?: return
        val bent = lens ?: return
        val bentCanvas = lensCanvas ?: return
        view.getLocationOnScreen(viewLocation)
        root.getLocationOnScreen(rootLocation)
        drawnX = viewLocation[0]
        drawnY = viewLocation[1]
        lastRenderAt = SystemClock.uptimeMillis()

        val save = rawCanvas.save()
        rawCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        rawCanvas.scale(lensScale, lensScale)
        rawCanvas.translate(-(drawnX - rootLocation[0]).toFloat(), -(drawnY - rootLocation[1]).toFloat())
        val backdrop = GlassBackdrop.of(root)
        backdrop.drawSharp(root, rawCanvas)
        rawCanvas.restoreToCount(save)
        if (blur > 0f) backdrop.blur(raw, blur * lensScale)

        if (meshDirty) buildMesh(bent.width, bent.height)
        // Shader dựng lại mỗi lượt để chắc chắn đọc nội dung mới của ảnh chụp
        meshPaint.shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bentCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        bentCanvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, meshVerts.size, meshVerts, 0, meshTexs, 0, null, 0,
            meshIndices, 0, meshIndices.size, meshPaint
        )
        ready = true
    }

    /**
     * Dựng lưới bẻ cong cho ảnh thấu kính cỡ [lensWidth] x [lensHeight]. Mặt kính phẳng với vành mép vát cong rộng b = [bevelWidth]
     * (không quá nửa cạnh ngắn): với mỗi đỉnh lưới p trên view,
     * - e = độ sâu của p tính từ mép hình kính (chữ nhật bo góc; 0 ở mép), n = hướng vuông góc với mép gần nhất, chỉ ra ngoài;
     * - trong vành (e < b): t = 1 − e / b, p hiện phần nền ở `p − n · refraction · b · t²` — vừa vào vành thì gần như không lệch, càng ra
     *   mép càng lệch vào trong, tới mép thì nội dung lật ngược như qua mép kính dày. Độ lệch tăng theo t² (không dốc đứng ở mép như
     *   cung tròn) để hình sát mép không bị nén quá mức thành lấm chấm;
     * - ngoài vành (e ≥ b): giữ nguyên.
     * Sau đó thu về quanh tâm theo [zoom]. Trường khoảng cách dùng bo góc không nhỏ hơn b để hướng n đổi mượt quanh góc.
     */
    private fun buildMesh(lensWidth: Int, lensHeight: Int) {
        meshDirty = false
        var cell = max(MESH_MIN_CELL_PX, view.resources.displayMetrics.density * MESH_CELL_DP * lensScale)
        var columns = ceil(lensWidth / cell).toInt().coerceAtLeast(1)
        var rows = ceil(lensHeight / cell).toInt().coerceAtLeast(1)
        while ((columns + 1) * (rows + 1) > MESH_MAX_VERTICES) {
            cell *= 1.5f
            columns = ceil(lensWidth / cell).toInt().coerceAtLeast(1)
            rows = ceil(lensHeight / cell).toInt().coerceAtLeast(1)
        }
        val vertexCount = (columns + 1) * (rows + 1)
        if (meshVerts.size != vertexCount * 2) {
            meshVerts = FloatArray(vertexCount * 2)
            meshTexs = FloatArray(vertexCount * 2)
        }
        if (meshIndices.size != columns * rows * 6) meshIndices = ShortArray(columns * rows * 6)

        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val inradius = min(centerX, centerY)
        val bevel = min(bevelWidth, inradius).coerceAtLeast(0f)
        val fieldRadius = max(cornerRadius, bevel).coerceAtMost(inradius)
        val coreX = centerX - fieldRadius
        val coreY = centerY - fieldRadius
        val strength = refraction.coerceAtLeast(0f) * bevel
        val scale = zoom.coerceAtLeast(1f)
        var index = 0
        for (row in 0..rows) {
            val y = h * row / rows
            val offsetY = y - centerY
            for (column in 0..columns) {
                val x = w * column / columns
                val offsetX = x - centerX
                // Khoảng cách có dấu tới mép hình chữ nhật bo góc (âm = bên trong) và hướng ra mép gần nhất
                val edgeX = abs(offsetX) - coreX
                val edgeY = abs(offsetY) - coreY
                val distance: Float
                var normalX = 0f
                var normalY = 0f
                if (edgeX > 0f && edgeY > 0f) {
                    val length = hypot(edgeX, edgeY)
                    distance = length - fieldRadius
                    normalX = edgeX / length * sign(offsetX)
                    normalY = edgeY / length * sign(offsetY)
                } else if (edgeX > edgeY) {
                    distance = edgeX - fieldRadius
                    normalX = sign(offsetX)
                } else {
                    distance = edgeY - fieldRadius
                    normalY = sign(offsetY)
                }
                var sourceX = x
                var sourceY = y
                val depth = -distance
                if (bevel > 0f && depth < bevel) {
                    val t = (1f - depth / bevel).coerceIn(0f, 1f)
                    val shift = strength * t * t
                    sourceX -= normalX * shift
                    sourceY -= normalY * shift
                }
                sourceX = (centerX + (sourceX - centerX) / scale).coerceIn(0f, w)
                sourceY = (centerY + (sourceY - centerY) / scale).coerceIn(0f, h)
                meshVerts[index] = x * lensScale
                meshVerts[index + 1] = y * lensScale
                meshTexs[index] = sourceX * lensScale
                meshTexs[index + 1] = sourceY * lensScale
                index += 2
            }
        }
        index = 0
        val stride = columns + 1
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val topLeft = row * stride + column
                val bottomLeft = topLeft + stride
                // Chỉ số là số 16 bit không dấu (lưới tối đa MESH_MAX_VERTICES đỉnh)
                meshIndices[index++] = topLeft.toShort()
                meshIndices[index++] = (topLeft + 1).toShort()
                meshIndices[index++] = bottomLeft.toShort()
                meshIndices[index++] = (topLeft + 1).toShort()
                meshIndices[index++] = (bottomLeft + 1).toShort()
                meshIndices[index++] = bottomLeft.toShort()
            }
        }
    }

    private companion object {
        /** Chụp lại nền nhanh nhất mỗi 12 ms: theo từng khung hình ở 60 Hz, cách khung ở 120 Hz. */
        const val MIN_INTERVAL_MS = 12L

        /** Ảnh thấu kính tối đa chừng này điểm ảnh; view lớn hơn thì ảnh thu nhỏ lại (hình hơi mềm đi) để giữ chi phí. */
        const val MAX_PIXELS = 600_000f

        /** Có độ mờ: cứ mỗi chừng này px độ mờ thì ảnh chụp nhỏ đi thêm một lần mỗi chiều, không quá [BLUR_MAX_SHRINK] lần. */
        const val BLUR_SHRINK_STEP_PX = 4f
        const val BLUR_MAX_SHRINK = 4f

        /** Ô lưới bẻ cong: 1,5dp, không nhỏ hơn 2 px; lưới không quá chừng này đỉnh (chỉ số 16 bit). */
        const val MESH_CELL_DP = 1.5f
        const val MESH_MIN_CELL_PX = 2f
        const val MESH_MAX_VERTICES = 60_000
    }
}
