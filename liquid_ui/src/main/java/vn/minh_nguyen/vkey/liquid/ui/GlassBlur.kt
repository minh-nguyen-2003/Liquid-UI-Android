package vn.minh_nguyen.vkey.liquid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * Nền kính mờ dùng chung cho [LiquidLayout] ([LiquidGlassMode.BLUR]) và [GlassLayout] cùng các lớp con: vẽ phần ảnh nền mờ của
 * [GlassBackdrop] nằm đúng sau [view], có thể bẻ cong ở mép ([refraction]), phóng nhẹ ([zoom]) và tăng đậm màu ([saturation]).
 *
 * View chủ gọi [onPreDraw] từ `onPreDraw` (cập nhật ảnh nền dùng chung, tự giới hạn tần suất), [draw] từ `onDraw`, [release] khi rời cửa
 * sổ / tắt chế độ mờ; và tự bỏ qua mình khi gặp [GlassBackdrop.CaptureCanvas] trong `draw` để không lẫn vào nền của chính nó.
 */
internal class GlassBlur(val view: View) {

    /** Tắt thì view không xin nền và [draw] không vẽ gì. */
    var enabled = true

    /** Độ mờ của nền (px): độ lệch chuẩn của mờ Gauss. */
    var blurRadius = 0f

    /**
     * Độ bẻ cong nền ở mép kính: mép kéo vào phần nền nằm ngoài view tới [refraction] lần nửa cạnh ngắn, càng gần mép càng dồn nén, giữa
     * view gần như phẳng. 0 = tắt (nền mờ phẳng).
     */
    var refraction = 0f

    /** Phóng to nền quanh tâm view (1 = không phóng). */
    var zoom = 1f

    /** Độ đậm màu của nền đã làm mờ (1 = giữ nguyên); kính kiểu iOS dùng khoảng 1.4. */
    var saturation = 1f
        set(value) {
            if (field == value) return
            field = value
            backdropPaint.colorFilter =
                if (value == 1f) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(value) })
        }

    /**
     * Mảnh nền riêng của view cho chế độ bẻ cong: ảnh nhỏ 1/[LENS_SCALE] cỡ view, mỗi điểm ảnh lấy màu từ ảnh nền dùng chung tại vị trí đã
     * khúc xạ ([lensSourceX] / [lensSourceY], tính sẵn theo hình kính — tròn, pill hay chữ nhật bo góc) rồi phóng lại lên view. Bảng vị
     * trí chỉ tính lại khi view vẽ lại (đổi cỡ / đổi chỗ); mỗi khung hình nền mới chỉ lấy mẫu lại màu.
     */
    private var lens: Bitmap? = null
    private var lensPixels = IntArray(0)
    private var lensSourceX = FloatArray(0)
    private var lensSourceY = FloatArray(0)

    /** Vùng của ảnh nền mà mảnh nền cần đọc (đã cắt trong ảnh nền) và bộ đệm điểm ảnh của vùng đó. */
    private var regionLeft = 0
    private var regionTop = 0
    private var regionWidth = 0
    private var regionHeight = 0
    private var regionPixels = IntArray(0)
    private val lensBounds = RectF()

    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val windowLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var drawnX = Int.MIN_VALUE
    private var drawnY = Int.MIN_VALUE

    /** Ảnh nền đã dùng ở lượt vẽ gần nhất (`null` = lượt đó chưa có ảnh nền). */
    private var drawnBackdrop: Bitmap? = null

    /** Lượt chụp ([GlassBackdrop.generation]) mà mảnh nền bẻ cong đang hiện. */
    private var lensGeneration = -1

    /**
     * Trước mỗi lượt vẽ của cửa sổ: cập nhật ảnh nền mờ dùng chung của [root] (tự giới hạn tần suất); [all] = chụp toàn bộ giao diện chứ
     * không chỉ video / ảnh. Ảnh nền đổi nội dung tại chỗ nên không cần vẽ lại view; chỉ vẽ lại khi view đổi chỗ trong cửa sổ (vuốt trang)
     * hoặc ảnh nền vừa có / vừa mất / đổi cỡ.
     */
    fun onPreDraw(root: View, all: Boolean) {
        if (!enabled || !view.isShown) return
        val backdrop = GlassBackdrop.of(root)
        backdrop.update(root, this, all)
        view.getLocationOnScreen(windowLocation)
        val current = backdrop.bitmap?.takeIf { backdrop.hasContent }
        if (windowLocation[0] != drawnX || windowLocation[1] != drawnY || current !== drawnBackdrop) {
            view.invalidate()
        } else if (current != null && refraction > 0f && backdrop.generation != lensGeneration) {
            // View đứng yên, nền vừa chụp lại: chỉ cần lấy mẫu lại màu từ khung hình nền mới
            lensGeneration = backdrop.generation
            renderLens(current)
        }
    }

    /**
     * Phần nền view này cần, điểm ảnh trong toạ độ của [root], ghi vào [out]: khung của view, nới ra tới chỗ xa nhất mà phần bẻ cong / thu
     * nhỏ nền lấy tới. Trả về `false` khi view không cần nền (tắt, đang ẩn, chưa có kích thước).
     */
    fun backdropRegion(root: View, out: RectF): Boolean {
        val width = view.width
        val height = view.height
        if (!enabled || !view.isAttachedToWindow || !view.isShown || width <= 0 || height <= 0) return false
        view.getLocationOnScreen(windowLocation)
        root.getLocationOnScreen(rootLocation)
        val reach = min(width, height) / 2f * refraction / zoom + GlassBackdrop.SCALE
        val halfWidth = width / 2f * max(1f, 1f / zoom) + reach
        val halfHeight = height / 2f * max(1f, 1f / zoom) + reach
        val centerX = windowLocation[0] - rootLocation[0] + width / 2f
        val centerY = windowLocation[1] - rootLocation[1] + height / 2f
        out.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
        return true
    }

    /** View không dùng nền của [root] nữa (rời cửa sổ, đổi gốc nền, tắt chế độ mờ). */
    fun release(root: View) {
        GlassBackdrop.of(root).release(this)
    }

    /**
     * Vẽ phần ảnh nền mờ của [root] nằm đúng sau view lên khung [width] x [height] (ảnh nhỏ hơn gốc nền [GlassBackdrop.SCALE] lần nên
     * phóng lại bằng lọc song tuyến tính → mờ mịn). [radius] = bán kính bo thực tế của hình kính; [clip] = tự cắt theo hình kính (view đã
     * cắt sẵn bằng `clipToOutline` thì không cần). Chưa có ảnh nền thì không vẽ gì.
     */
    fun draw(canvas: Canvas, root: View, width: Float, height: Float, radius: Float, clip: Boolean) {
        // Vị trí tính theo màn hình để gốc nền ở cửa sổ khác (màn phía sau hộp thoại) vẫn khớp
        view.getLocationOnScreen(windowLocation)
        drawnX = windowLocation[0]
        drawnY = windowLocation[1]
        drawnBackdrop = null
        if (!enabled || !view.isAttachedToWindow) return
        val backdrop = GlassBackdrop.of(root)
        val bitmap = backdrop.bitmap?.takeIf { backdrop.hasContent } ?: return
        drawnBackdrop = bitmap
        lensGeneration = backdrop.generation
        root.getLocationOnScreen(rootLocation)
        val save = canvas.save()
        if (clip) {
            lensBounds.set(0f, 0f, width, height)
            clipPath.rewind()
            clipPath.addRoundRect(lensBounds, radius, radius, Path.Direction.CW)
            canvas.clipPath(clipPath)
        }
        if (refraction > 0f) {
            drawRefracted(canvas, bitmap, width, height, radius)
        } else {
            val scale = GlassBackdrop.SCALE.toFloat()
            canvas.scale(zoom, zoom, width / 2f, height / 2f)
            canvas.translate(-(drawnX - rootLocation[0]).toFloat(), -(drawnY - rootLocation[1]).toFloat())
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, 0f, 0f, backdropPaint)
        }
        canvas.restoreToCount(save)
    }

    /**
     * Nền mờ bị bẻ cong ở mép, đúng theo hình kính (tròn, pill hay chữ nhật bo góc [radius]). Với mỗi điểm p trên view:
     * - d = độ gần mép: 0 ở sâu trong view nhất, 1 ở mép, đo bằng khoảng cách tới mép của hình chữ nhật bo góc;
     * - n = hướng vuông góc với mép gần nhất, chỉ ra ngoài;
     * - phần nền hiện tại p nằm ở `tâm + (p − tâm) / zoom + n · R · refraction · d^(REFRACTION_POWER + 1) / zoom` (R = nửa cạnh ngắn):
     *   giữa view chỉ phóng nhẹ, sát mép thì lấy cả vành nền bên ngoài view dồn vào.
     * Trường khúc xạ dùng bo góc không nhỏ hơn [LENS_MIN_ROUND] · R để hướng n đổi mượt quanh góc (view bo góc nhỏ không bị gãy nếp ở góc).
     */
    private fun drawRefracted(canvas: Canvas, backdrop: Bitmap, width: Float, height: Float, radius: Float) {
        val lensWidth = ceil(width / LENS_SCALE).toInt().coerceAtLeast(1)
        val lensHeight = ceil(height / LENS_SCALE).toInt().coerceAtLeast(1)
        var lens = lens
        if (lens == null || lens.width != lensWidth || lens.height != lensHeight) {
            lens = Bitmap.createBitmap(lensWidth, lensHeight, Bitmap.Config.ARGB_8888)
            this.lens = lens
            lensPixels = IntArray(lensWidth * lensHeight)
            lensSourceX = FloatArray(lensWidth * lensHeight)
            lensSourceY = FloatArray(lensWidth * lensHeight)
        }
        val scale = GlassBackdrop.SCALE.toFloat()
        val zoom = zoom
        val originX = (drawnX - rootLocation[0]).toFloat()
        val originY = (drawnY - rootLocation[1]).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val inradius = min(centerX, centerY)
        val lensRadius = max(radius, inradius * LENS_MIN_ROUND)
        val coreX = centerX - lensRadius
        val coreY = centerY - lensRadius
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var index = 0
        for (row in 0 until lensHeight) {
            val offsetY = (row + 0.5f) * height / lensHeight - centerY
            for (column in 0 until lensWidth) {
                val offsetX = (column + 0.5f) * width / lensWidth - centerX
                // Khoảng cách có dấu tới mép hình chữ nhật bo góc (âm = bên trong) và hướng ra mép gần nhất
                val edgeX = abs(offsetX) - coreX
                val edgeY = abs(offsetY) - coreY
                val distance: Float
                var normalX = 0f
                var normalY = 0f
                if (edgeX > 0f && edgeY > 0f) {
                    val length = hypot(edgeX, edgeY)
                    distance = length - lensRadius
                    normalX = edgeX / length * sign(offsetX)
                    normalY = edgeY / length * sign(offsetY)
                } else if (edgeX > edgeY) {
                    distance = edgeX - lensRadius
                    normalX = sign(offsetX)
                } else {
                    distance = edgeY - lensRadius
                    normalY = sign(offsetY)
                }
                val depth = (1f + distance / inradius).coerceAtLeast(0f)
                val shift = inradius * refraction * depth.pow(REFRACTION_POWER + 1f) / zoom
                val sourceX = centerX + offsetX / zoom + normalX * shift
                val sourceY = centerY + offsetY / zoom + normalY * shift
                // Sang toạ độ điểm ảnh của ảnh nền (tâm điểm ảnh lệch nửa ô)
                val backdropX = (originX + sourceX) / scale - 0.5f
                val backdropY = (originY + sourceY) / scale - 0.5f
                lensSourceX[index] = backdropX
                lensSourceY[index] = backdropY
                index++
                if (backdropX < minX) minX = backdropX
                if (backdropX > maxX) maxX = backdropX
                if (backdropY < minY) minY = backdropY
                if (backdropY > maxY) maxY = backdropY
            }
        }
        // Vùng ảnh nền cần đọc, cắt trong ảnh nền (view nằm sát mép màn thì phần ngoài lấy điểm biên)
        val left = floor(minX).toInt().coerceIn(0, backdrop.width - 1)
        val top = floor(minY).toInt().coerceIn(0, backdrop.height - 1)
        val right = (floor(maxX).toInt() + 1).coerceIn(left, backdrop.width - 1)
        val bottom = (floor(maxY).toInt() + 1).coerceIn(top, backdrop.height - 1)
        regionLeft = left
        regionTop = top
        regionWidth = right - left + 1
        regionHeight = bottom - top + 1
        if (regionPixels.size < regionWidth * regionHeight) regionPixels = IntArray(regionWidth * regionHeight)
        renderLens(backdrop)
        lensBounds.set(0f, 0f, width, height)
        canvas.drawBitmap(lens, null, lensBounds, backdropPaint)
    }

    /**
     * Tô lại mảnh nền từ [backdrop]: mỗi điểm ảnh lấy màu nội suy song tuyến tính tại vị trí đã khúc xạ. Vị trí rơi ngoài ảnh nền thì lấy
     * điểm biên gần nhất.
     */
    private fun renderLens(backdrop: Bitmap) {
        val lens = lens ?: return
        val width = regionWidth
        val height = regionHeight
        if (width <= 0 || height <= 0 || regionLeft + width > backdrop.width || regionTop + height > backdrop.height) return
        backdrop.getPixels(regionPixels, 0, width, regionLeft, regionTop, width, height)
        val maxX = (width - 1).toFloat()
        val maxY = (height - 1).toFloat()
        for (i in lensPixels.indices) {
            val x = (lensSourceX[i] - regionLeft).coerceIn(0f, maxX)
            val y = (lensSourceY[i] - regionTop).coerceIn(0f, maxY)
            val x0 = x.toInt()
            val y0 = y.toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val tx = x - x0
            val ty = y - y0
            val c00 = regionPixels[y0 * width + x0]
            val c10 = regionPixels[y0 * width + x1]
            val c01 = regionPixels[y1 * width + x0]
            val c11 = regionPixels[y1 * width + x1]
            val w00 = (1f - tx) * (1f - ty)
            val w10 = tx * (1f - ty)
            val w01 = (1f - tx) * ty
            val w11 = tx * ty
            val r = ((c00 shr 16) and 0xFF) * w00 + ((c10 shr 16) and 0xFF) * w10 + ((c01 shr 16) and 0xFF) * w01 + ((c11 shr 16) and 0xFF) * w11
            val g = ((c00 shr 8) and 0xFF) * w00 + ((c10 shr 8) and 0xFF) * w10 + ((c01 shr 8) and 0xFF) * w01 + ((c11 shr 8) and 0xFF) * w11
            val b = (c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 + (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11
            lensPixels[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        lens.setPixels(lensPixels, 0, lens.width, 0, 0, lens.width, lens.height)
    }

    private companion object {
        /** Độ cong dồn về mép theo luỹ thừa 3. */
        const val REFRACTION_POWER = 3f

        /** Mảnh nền bẻ cong nhỏ hơn view 3 lần mỗi chiều (view 40dp ≈ 40 x 40 điểm ảnh, lấy mẫu lại mỗi khung hình nền). */
        const val LENS_SCALE = 3f

        /** Trường khúc xạ bo góc ít nhất 60 % nửa cạnh ngắn, để hướng bẻ cong đổi mượt quanh góc của view bo góc nhỏ. */
        const val LENS_MIN_ROUND = 0.6f
    }
}
