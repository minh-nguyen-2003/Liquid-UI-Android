package vn.minh_nguyen.vkey.liquid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * **Đã gom vào [LiquidLayout]** — dùng `LiquidLayout` với `app:lgv_mode="blur"` (và `app:lgv_highlight="false"` nếu không cần viền sáng).
 * Lớp này giữ lại để các layout cũ chạy tiếp, dùng chung phần nền với `LiquidLayout` ([GlassBlur], [LiquidLens]).
 *
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
 * Độ mờ chỉnh bằng [blurRadius] (mờ kiểu Gauss, mặc định 4dp).
 *
 * Chế độ [GlassMode.LENS] ([mode]) đổi view thành thấu kính thật, giống hệt `lgv_mode="lens"` của [LiquidLayout]: không làm mờ, nội dung
 * phía sau nét nguyên ở giữa, riêng vành mép rộng [bevelWidth] bị bẻ cong; lớp màu [glassColor] và phần vẽ thêm của lớp con (viền sáng,
 * ánh trượt) vẫn phủ lên trên. Khi đó [blurEnabled], [blurRadius], [backdropMode], [refraction] không dùng tới; muốn nền qua thấu kính
 * mờ đi thì đặt [lensBlur].
 *
 * XML: `app:gl_color`, `app:gl_cornerRadius`, `app:gl_blur`, `app:gl_blurRadius`, `app:gl_backdrop`, `app:gl_mode`, `app:gl_bevelWidth`,
 * `app:gl_lensRefraction`, `app:gl_lensZoom`, `app:gl_lensBlur`.
 *
 * Lớp con: [GlassLensLayout] thêm bẻ cong nền ở mép (khúc xạ); [LiquidGlassLayout] thêm tiếp viền sáng + ánh trượt kiểu liquid.
 */
@Deprecated("Đã gom vào LiquidLayout: dùng LiquidLayout với app:lgv_mode=\"blur\" (thêm app:lgv_highlight=\"false\" nếu không cần viền sáng).")
open class GlassLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Phần nền mờ (dùng chung với [LiquidLayout]) và phần thấu kính của chế độ [GlassMode.LENS]. */
    private val blur = GlassBlur(this)
    private val lensGlass = LiquidLens(this)

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
            syncBlur()
        }

    /**
     * Độ mờ của nền (px): độ lệch chuẩn của mờ Gauss, như `blur()` của CSS — càng lớn nền càng nhoè. 0 = không làm mờ thêm (nền vẫn hơi
     * mềm vì chụp thu nhỏ). Mặc định 4dp.
     */
    var blurRadius: Float = resources.displayMetrics.density * DEFAULT_BLUR_DP
        set(value) {
            field = value.coerceAtLeast(0f)
            syncBlur()
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
            if (field !== value && isAttachedToWindow) blur.release(field ?: rootView)
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
            syncBlur()
        }

    /** Chế độ nền: [GlassMode.BLUR] (mặc định) hoặc [GlassMode.LENS] (thấu kính thật, không mờ, bẻ cong ở vành mép). */
    var mode: Int = GlassMode.BLUR
        set(value) {
            if (field == value) return
            field = value
            if (value == GlassMode.LENS) {
                // Không còn dùng ảnh nền mờ dùng chung
                if (isAttachedToWindow) blur.release(backdropRoot ?: rootView)
            } else {
                lensGlass.release()
            }
            syncBlur()
        }

    /** (lens) Bề rộng vành mép vát tròn (px) — chỉ phần này bị bẻ cong, phía trong là mặt kính phẳng. Không quá nửa cạnh ngắn. */
    var bevelWidth: Float = resources.getDimension(R.dimen.liquid_glass_bevel_width)
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /** (lens) Độ bẻ cong ở vành mép: sát mép hiện phần nền nằm sâu vào trong chừng này lần [bevelWidth]; 0 = không bẻ; mặc định 1. */
    var lensRefraction: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /** (lens) Phóng to nội dung phía sau quanh tâm view như kính lúp; 1 (mặc định) = không phóng. */
    var lensZoom: Float = 1f
        set(value) {
            field = value.coerceAtLeast(1f)
            invalidate()
        }

    /** (lens) Độ mờ của nội dung nhìn qua kính (px, độ lệch chuẩn của mờ Gauss); 0 (mặc định) = nét nguyên. */
    var lensBlur: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /** Phóng to nền quanh tâm view (1 = không phóng); lớp con có thấu kính đặt lớn hơn 1. */
    protected var backdropZoom: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0.01f)
            syncBlur()
        }

    /** Độ đậm màu của nền đã làm mờ (1 = giữ nguyên); kính kiểu iOS dùng khoảng 1.4. */
    protected var backdropSaturation: Float = 1f
        set(value) {
            field = value
            syncBlur()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillRect = RectF()

    /**
     * Trước mỗi lượt vẽ của cửa sổ: thấu kính tự chụp nét nền của mình và bẻ cong lại; không thì cập nhật ảnh nền mờ dùng chung (cả hai
     * tự giới hạn tần suất).
     */
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        val root = backdropRoot ?: rootView
        if (mode == GlassMode.LENS) {
            lensGlass.update(root, bevelWidth, lensRefraction, lensZoom, resolvedRadius(width.toFloat(), height.toFloat()), lensBlur)
        } else {
            blur.onPreDraw(root, all = backdropMode == GlassBackdropMode.ALL)
        }
        true
    }

    init {
        if (attrs != null) {
            val values = context.obtainStyledAttributes(attrs, R.styleable.GlassLayout)
            glassColor = values.getColor(R.styleable.GlassLayout_gl_color, glassColor)
            cornerRadius = values.getDimension(R.styleable.GlassLayout_gl_cornerRadius, cornerRadius)
            blurEnabled = values.getBoolean(R.styleable.GlassLayout_gl_blur, blurEnabled)
            blurRadius = values.getDimension(R.styleable.GlassLayout_gl_blurRadius, blurRadius)
            backdropMode = values.getInt(R.styleable.GlassLayout_gl_backdrop, backdropMode)
            mode = values.getInt(R.styleable.GlassLayout_gl_mode, mode)
            bevelWidth = values.getDimension(R.styleable.GlassLayout_gl_bevelWidth, bevelWidth)
            lensRefraction = values.getFloat(R.styleable.GlassLayout_gl_lensRefraction, lensRefraction)
            lensZoom = values.getFloat(R.styleable.GlassLayout_gl_lensZoom, lensZoom)
            lensBlur = values.getDimension(R.styleable.GlassLayout_gl_lensBlur, lensBlur)
            values.recycle()
        }
        fillPaint.color = glassColor
        syncBlur()
        setWillNotDraw(false)
        // Con (icon, chữ) cắt theo hình kính
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, resolvedRadius(view.width.toFloat(), view.height.toFloat()))
            }
        }
        clipToOutline = true
    }

    /** Đẩy thông số hiện tại sang phần nền mờ (tắt hẳn khi đang ở chế độ thấu kính) rồi vẽ lại. */
    private fun syncBlur() {
        blur.enabled = blurEnabled && mode != GlassMode.LENS
        blur.blurRadius = blurRadius
        blur.refraction = refraction
        blur.zoom = backdropZoom
        blur.saturation = backdropSaturation
        invalidate()
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
        // Thấu kính: hình nét đã bẻ cong ở vành mép; không thì nền mờ. View đã cắt theo hình kính (clipToOutline)
        if (mode == GlassMode.LENS) {
            lensGlass.draw(canvas, width, height, radius, clip = false)
        } else {
            blur.draw(canvas, backdropRoot ?: rootView, width, height, radius, clip = false)
        }
        fillRect.set(0f, 0f, width, height)
        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        onDrawGlassOverlay(canvas, width, height, radius)
        super.onDraw(canvas)
    }

    /** Lớp con vẽ thêm lên mặt kính (viền sáng, ánh trượt…), sau nền mờ và lớp màu, trước content. */
    protected open fun onDrawGlassOverlay(canvas: Canvas, width: Float, height: Float, radius: Float) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        blur.release(backdropRoot ?: rootView)
        lensGlass.release()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** #171717 30 %. */
        val DEFAULT_GLASS_COLOR = Color.argb(77, 23, 23, 23)

        /** Độ mờ mặc định: mờ Gauss độ lệch chuẩn 4dp. */
        const val DEFAULT_BLUR_DP = 4f
    }
}
