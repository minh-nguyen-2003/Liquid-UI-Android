package vn.minh_nguyen.vkey.liquid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * Khung kính chỉ làm mờ ("glass", không có viền sáng / ánh trượt kiểu liquid): làm mờ thật mọi thứ phía sau như kính iOS — giao diện
 * thường (danh sách, chữ, ảnh) lẫn video — rồi phủ một lớp màu [glassColor]. Dùng như `FrameLayout`, content đặt bên trong và được cắt
 * theo hình kính.
 *
 * Nền phía sau lấy qua [GlassBackdrop], theo [backdropMode]: mặc định mọi thứ đang hiện trong cửa sổ; [GlassBackdropMode.MEDIA] thì chỉ
 * video và ảnh phủ màn (nhẹ hơn). Video phải vẽ bằng `TextureView` (Media3: `app:surface_type="texture_view"`). View kính nằm trong hộp
 * thoại / popup thì đặt [backdropRoot] là nội dung của màn phía sau. Ảnh là bitmap phần cứng không vẽ lại được vào nền (Glide: tải với
 * `disallowHardwareConfig()`).
 *
 * Bo góc theo [cornerRadius]; âm (mặc định) = bo tròn hai đầu theo nửa cạnh ngắn (hình tròn khi vuông).
 *
 * XML: `app:gl_color`, `app:gl_cornerRadius`, `app:gl_blur`, `app:gl_backdrop`.
 *
 * Lớp con: [GlassLensLayout] thêm bẻ cong nền ở mép (khúc xạ); [LiquidGlassLayout] thêm tiếp viền sáng + ánh trượt kiểu liquid.
 */
open class GlassLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Màu phủ trên nền đã làm mờ (kèm độ mờ). */
    @ColorInt
    var glassColor: Int = DEFAULT_GLASS_COLOR
        set(value) {
            field = value
            fillPaint.color = value
            invalidate()
        }

    /** Bán kính bo góc (px); âm = bo tròn hai đầu. */
    var cornerRadius: Float = -1f
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    /** Làm mờ nội dung phía sau qua [GlassBackdrop]; tắt thì chỉ còn lớp màu. */
    var blurEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Làm mờ những gì phía sau: [GlassBackdropMode.ALL] (mặc định) = mọi thứ đang hiện, cả giao diện thường lẫn video;
     * [GlassBackdropMode.MEDIA] = chỉ video và ảnh phủ màn (nhẹ hơn).
     */
    var backdropMode: Int = GlassBackdropMode.ALL
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Gốc của phần nội dung cần làm mờ; `null` (mặc định) = gốc cửa sổ chứa view này. View kính nằm trong cửa sổ khác với nội dung phía
     * sau (hộp thoại, bottom sheet, popup) thì đặt gốc là view nội dung của màn phía sau, vd
     * `activity.findViewById(android.R.id.content)`.
     */
    var backdropRoot: View? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Độ bẻ cong nền ở mép kính (khúc xạ như thấu kính): mép kéo vào phần nền nằm ngoài view tới [refraction] lần nửa cạnh ngắn, càng gần
     * mép càng dồn nén, giữa view gần như phẳng. 0 = tắt (nền mờ phẳng) — mặc định của [GlassLayout]; [GlassLensLayout] bật sẵn.
     */
    var refraction: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /** Phóng to nền quanh tâm view (1 = không phóng); lớp con có thấu kính đặt lớn hơn 1. */
    protected var backdropZoom: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0.01f)
            invalidate()
        }

    /** Độ đậm màu của nền đã làm mờ (1 = giữ nguyên); kính kiểu iOS dùng khoảng 1.4. */
    protected var backdropSaturation: Float = 1f
        set(value) {
            field = value
            backdropPaint.colorFilter =
                if (value == 1f) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(value) })
            invalidate()
        }

    /**
     * Mảnh nền riêng của view cho chế độ bẻ cong: ảnh nhỏ 1/[LENS_SCALE] cỡ view, mỗi điểm ảnh lấy màu từ ảnh nền dùng chung tại vị trí đã
     * khúc xạ ([lensSourceX] / [lensSourceY], tính sẵn theo hình kính — tròn, pill hay chữ nhật bo góc) rồi phóng lại lên view. Bảng vị
     * trí chỉ tính lại khi view đổi cỡ / đổi chỗ; mỗi khung hình nền mới chỉ lấy mẫu lại màu.
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
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillRect = RectF()
    private val windowLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var drawnX = Int.MIN_VALUE
    private var drawnY = Int.MIN_VALUE

    /** Ảnh nền đã dùng ở lượt vẽ gần nhất (`null` = lượt đó chưa có ảnh nền). */
    private var drawnBackdrop: Bitmap? = null

    /**
     * Trước mỗi lượt vẽ của cửa sổ: cập nhật ảnh nền mờ dùng chung (tự giới hạn tần suất). Ảnh nền đổi nội dung tại chỗ nên không cần vẽ
     * lại view; chỉ vẽ lại khi view đổi chỗ trong cửa sổ (vuốt trang) hoặc ảnh nền vừa có / vừa mất / đổi cỡ.
     */
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (blurEnabled && isShown) {
            val root = backdropRoot ?: rootView
            val backdrop = GlassBackdrop.of(root)
            backdrop.update(root, all = backdropMode == GlassBackdropMode.ALL)
            getLocationOnScreen(windowLocation)
            val current = backdrop.bitmap?.takeIf { backdrop.hasContent }
            if (windowLocation[0] != drawnX || windowLocation[1] != drawnY || current !== drawnBackdrop) {
                invalidate()
            } else if (current != null && refraction > 0f) {
                // View đứng yên: chỉ cần lấy mẫu lại màu từ khung hình nền mới
                renderLens(current)
            }
        }
        true
    }

    init {
        if (attrs != null) {
            val values = context.obtainStyledAttributes(attrs, R.styleable.GlassLayout)
            glassColor = values.getColor(R.styleable.GlassLayout_gl_color, glassColor)
            cornerRadius = values.getDimension(R.styleable.GlassLayout_gl_cornerRadius, cornerRadius)
            blurEnabled = values.getBoolean(R.styleable.GlassLayout_gl_blur, blurEnabled)
            backdropMode = values.getInt(R.styleable.GlassLayout_gl_backdrop, backdropMode)
            values.recycle()
        }
        fillPaint.color = glassColor
        setWillNotDraw(false)
        // Con (icon, chữ) cắt theo hình kính
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, resolvedRadius(view.width.toFloat(), view.height.toFloat()))
            }
        }
        clipToOutline = true
    }

    /** Bán kính bo thực tế cho view cỡ [width] x [height]: [cornerRadius], không quá nửa cạnh ngắn; âm = nửa cạnh ngắn. */
    protected fun resolvedRadius(width: Float, height: Float): Float {
        val pill = min(width, height) / 2f
        return if (cornerRadius < 0f) pill else min(cornerRadius, pill)
    }

    /** Đang chụp ảnh nền ([GlassBackdrop.CaptureCanvas]): không vẽ gì, để view kính và content của nó không lẫn vào nền của chính nó. */
    override fun draw(canvas: Canvas) {
        if (canvas is GlassBackdrop.CaptureCanvas) return
        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val radius = resolvedRadius(width, height)
        drawBackdrop(canvas, width, height)
        fillRect.set(0f, 0f, width, height)
        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        onDrawGlassOverlay(canvas, width, height, radius)
        super.onDraw(canvas)
    }

    /** Lớp con vẽ thêm lên mặt kính (viền sáng, ánh trượt…), sau nền mờ và lớp màu, trước content. */
    protected open fun onDrawGlassOverlay(canvas: Canvas, width: Float, height: Float, radius: Float) = Unit

    /**
     * Vẽ phần ảnh nền mờ nằm đúng sau view (ảnh nhỏ hơn cửa sổ [GlassBackdrop.SCALE] lần nên phóng lại bằng lọc song tuyến tính → mờ mịn).
     * View đã cắt theo hình kính (clipToOutline). Chưa có ảnh nền thì bỏ qua.
     */
    private fun drawBackdrop(canvas: Canvas, width: Float, height: Float) {
        // Vị trí tính theo màn hình để gốc nền ở cửa sổ khác (màn phía sau hộp thoại) vẫn khớp
        getLocationOnScreen(windowLocation)
        drawnX = windowLocation[0]
        drawnY = windowLocation[1]
        drawnBackdrop = null
        if (!blurEnabled || !isAttachedToWindow) return
        val root = backdropRoot ?: rootView
        val backdrop = GlassBackdrop.of(root)
        val bitmap = backdrop.bitmap?.takeIf { backdrop.hasContent } ?: return
        drawnBackdrop = bitmap
        root.getLocationOnScreen(rootLocation)
        if (refraction > 0f) {
            drawRefracted(canvas, bitmap, width, height)
            return
        }
        val scale = GlassBackdrop.SCALE.toFloat()
        val save = canvas.save()
        canvas.scale(backdropZoom, backdropZoom, width / 2f, height / 2f)
        canvas.translate(-(drawnX - rootLocation[0]).toFloat(), -(drawnY - rootLocation[1]).toFloat())
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, backdropPaint)
        canvas.restoreToCount(save)
    }

    /**
     * Nền mờ bị bẻ cong ở mép, đúng theo hình kính (tròn, pill hay chữ nhật bo góc). Với mỗi điểm p trên view:
     * - d = độ gần mép: 0 ở sâu trong view nhất, 1 ở mép, đo bằng khoảng cách tới mép của hình chữ nhật bo góc;
     * - n = hướng vuông góc với mép gần nhất, chỉ ra ngoài;
     * - phần nền hiện tại p nằm ở `tâm + (p − tâm) / backdropZoom + n · R · refraction · d^(REFRACTION_POWER + 1) / backdropZoom`
     *   (R = nửa cạnh ngắn): giữa view chỉ phóng nhẹ, sát mép thì lấy cả vành nền bên ngoài view dồn vào.
     * Trường khúc xạ dùng bo góc không nhỏ hơn [LENS_MIN_ROUND] · R để hướng n đổi mượt quanh góc (view bo góc nhỏ không bị gãy nếp ở góc).
     */
    private fun drawRefracted(canvas: Canvas, backdrop: Bitmap, width: Float, height: Float) {
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
        val zoom = backdropZoom
        val originX = (drawnX - rootLocation[0]).toFloat()
        val originY = (drawnY - rootLocation[1]).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val inradius = min(centerX, centerY)
        val lensRadius = max(resolvedRadius(width, height), inradius * LENS_MIN_ROUND)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** #171717 30 %. */
        val DEFAULT_GLASS_COLOR = Color.argb(77, 23, 23, 23)

        /** Độ cong dồn về mép theo luỹ thừa 3. */
        const val REFRACTION_POWER = 3f

        /** Mảnh nền bẻ cong nhỏ hơn view 3 lần mỗi chiều (view 40dp ≈ 40 x 40 điểm ảnh, lấy mẫu lại mỗi khung hình nền). */
        const val LENS_SCALE = 3f

        /** Trường khúc xạ bo góc ít nhất 60 % nửa cạnh ngắn, để hướng bẻ cong đổi mượt quanh góc của view bo góc nhỏ. */
        const val LENS_MIN_ROUND = 0.6f
    }
}
