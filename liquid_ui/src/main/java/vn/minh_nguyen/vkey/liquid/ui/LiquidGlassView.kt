package vn.minh_nguyen.vkey.liquid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/**
 * Nền "Liquid Glass" dùng chung: nền đen mờ (mặc định `#33000000` — đen 20%) + vệt sáng trắng
 * chạy theo mép tạo cảm giác chất lỏng, bán kính bo tuỳ chỉnh.
 *
 * Dùng như FrameLayout thường, content (icon, hoặc icon + text) đặt bên trong:
 *   app:lgv_backgroundColor      — màu nền; mặc định `@color/liquid_glass_background`
 *   app:lgv_cornerRadius         — bán kính bo (bị bỏ qua khi bật `lgv_pill`)
 *   app:lgv_pill                 — true: bán kính = nửa cạnh ngắn ⇒ view vuông ra HÌNH TRÒN,
 *                                  view ngang ra PILL, không cần biết trước kích thước
 *   app:lgv_highlightColor       — màu vệt sáng (mặc định trắng)
 *   app:lgv_highlightWidth       — độ dày vệt sáng nét
 *   app:lgv_highlightAlpha       — độ sáng đỉnh của vệt (0..1)
 *   app:lgv_highlightOrientation — hướng nguồn sáng, xem [LiquidGlassOrientation]
 *   app:lgv_glow                 — quầng sáng mềm loang vào trong (tắt được cho item list)
 *   app:lgv_clipContent          — bo góc luôn content bên trong (mặc định true)
 *
 * Lưu ý:
 * - View **không blur nội dung phía sau**; nó chỉ phủ một lớp đen mờ + vệt sáng vẽ bằng Canvas,
 *   nhờ vậy dùng được ở mọi nơi (kể cả item RecyclerView) mà không cần `Activity`. Cần blur
 *   backdrop thật thì bọc một `BlurView` bên ngoài (xem README), đừng gộp vào đây.
 * - Vệt sáng **không tự đảo chiều theo RTL**.
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val drawRect = RectF()
    private var highlightShader: Shader? = null

    private val glassOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val radius = LiquidGlassGeometry.resolveRadius(
                isPill, cornerRadius, view.width.toFloat(), view.height.toFloat()
            )
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    /** Màu nền kính. Không đặt tên `backgroundColor` vì trùng `View.setBackgroundColor`. */
    var glassColor: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var cornerRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            highlightShader = null // mốc sweep chiếu theo pháp tuyến phụ thuộc bán kính bo
            refreshOutline()
            invalidate()
        }

    /** true ⇒ bán kính = nửa cạnh ngắn (tròn khi vuông, pill khi ngang), bỏ qua [cornerRadius]. */
    var isPill: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            highlightShader = null
            refreshOutline()
            invalidate()
        }

    var highlightColor: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            highlightShader = null
            invalidate()
        }

    var highlightWidth: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Alpha đỉnh của vệt sáng, tự clamp về 0..1. */
    var highlightAlpha: Float = DEFAULT_HIGHLIGHT_ALPHA
        set(value) {
            val clamped = LiquidGlassGeometry.clampAlpha(value)
            if (field == clamped) return
            field = clamped
            highlightShader = null
            invalidate()
        }

    var highlightOrientation: Int = LiquidGlassOrientation.TOP_LEFT_BOTTOM_RIGHT
        set(value) {
            if (field == value) return
            field = value
            highlightShader = null
            invalidate()
        }

    /** Quầng sáng mềm quanh mép — tắt đi nếu view nằm trong list cuộn nhiều item. */
    var isGlowEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var isClipContent: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            refreshOutline()
            invalidate()
        }

    init {
        // ViewGroup mặc định bỏ qua onDraw — phải tắt cờ này thì nền mới được vẽ
        setWillNotDraw(false)

        val ta = context.obtainStyledAttributes(
            attrs, R.styleable.LiquidGlassView, defStyleAttr, 0
        )
        glassColor = ta.getColor(
            R.styleable.LiquidGlassView_lgv_backgroundColor,
            ContextCompat.getColor(context, R.color.liquid_glass_background)
        )
        cornerRadius = ta.getDimension(
            R.styleable.LiquidGlassView_lgv_cornerRadius,
            resources.getDimension(R.dimen.liquid_glass_corner_radius)
        )
        isPill = ta.getBoolean(R.styleable.LiquidGlassView_lgv_pill, false)
        highlightColor = ta.getColor(
            R.styleable.LiquidGlassView_lgv_highlightColor,
            ContextCompat.getColor(context, R.color.liquid_glass_highlight)
        )
        highlightWidth = ta.getDimension(
            R.styleable.LiquidGlassView_lgv_highlightWidth,
            resources.getDimension(R.dimen.liquid_glass_highlight_width)
        )
        highlightAlpha = ta.getFloat(
            R.styleable.LiquidGlassView_lgv_highlightAlpha,
            DEFAULT_HIGHLIGHT_ALPHA
        )
        highlightOrientation = ta.getInt(
            R.styleable.LiquidGlassView_lgv_highlightOrientation,
            LiquidGlassOrientation.TOP_LEFT_BOTTOM_RIGHT
        )
        isGlowEnabled = ta.getBoolean(R.styleable.LiquidGlassView_lgv_glow, true)
        isClipContent = ta.getBoolean(R.styleable.LiquidGlassView_lgv_clipContent, true)
        ta.recycle()

        refreshOutline()
    }

    /**
     * Đổi nhiều thuộc tính trong 1 lần gọi — mỗi tham số bỏ trống thì giữ nguyên giá trị hiện tại.
     * Tiện khi dựng view bằng code (item list, dialog…) thay vì khai trong XML.
     */
    @JvmOverloads
    fun applyGlass(
        glassColor: Int = this.glassColor,
        cornerRadius: Float = this.cornerRadius,
        pill: Boolean = this.isPill,
        highlightColor: Int = this.highlightColor,
        highlightWidth: Float = this.highlightWidth,
        highlightAlpha: Float = this.highlightAlpha,
        highlightOrientation: Int = this.highlightOrientation,
        glow: Boolean = this.isGlowEnabled,
        clipContent: Boolean = this.isClipContent
    ) {
        this.glassColor = glassColor
        this.cornerRadius = cornerRadius
        this.isPill = pill
        this.highlightColor = highlightColor
        this.highlightWidth = highlightWidth
        this.highlightAlpha = highlightAlpha
        this.highlightOrientation = highlightOrientation
        this.isGlowEnabled = glow
        this.isClipContent = clipContent
    }

    private fun refreshOutline() {
        outlineProvider = if (isClipContent) glassOutlineProvider else ViewOutlineProvider.BACKGROUND
        clipToOutline = isClipContent
        invalidateOutline()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // shader phụ thuộc kích thước — dựng lại ở lần vẽ kế tiếp
        highlightShader = null
    }

    /**
     * SweepGradient quanh tâm view với mốc màu CHIẾU THEO PHÁP TUYẾN mép (xem
     * [LiquidGlassGeometry.normalMappedStops]): bevel sáng/tối bám theo hướng của từng đoạn
     * viền nên hình dẹt (search bar) sáng đều cạnh trên và đỉnh sáng nằm ở nắp trái đúng
     * design — mốc đều theo góc-vị-trí dồn đỉnh sáng vào giữa cạnh trên.
     */
    private fun buildHighlightShader(w: Float, h: Float): Shader {
        val lightAngle = LiquidGlassGeometry.lightSourceAngle(highlightOrientation)
        val radius = LiquidGlassGeometry.resolveRadius(isPill, cornerRadius, w, h)
        val (positions, colors) = LiquidGlassGeometry.normalMappedStops(
            lightAngle, highlightAlpha, highlightColor, w, h, radius
        )
        return SweepGradient(w / 2f, h / 2f, colors, positions)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = LiquidGlassGeometry.resolveRadius(isPill, cornerRadius, w, h)

        drawRect.set(0f, 0f, w, h)
        fillPaint.color = glassColor
        canvas.drawRoundRect(drawRect, radius, radius, fillPaint)

        val stroke = LiquidGlassGeometry.clampStrokeWidth(highlightWidth, w, h)
        if (stroke <= 0f || highlightAlpha <= 0f) return

        if (highlightShader == null) highlightShader = buildHighlightShader(w, h)
        strokePaint.shader = highlightShader

        // Quầng sáng mềm trước, vệt sáng nét vẽ đè lên sau
        if (isGlowEnabled) {
            for (i in 0 until LiquidGlassGeometry.GLOW_LAYERS) {
                drawGlassStroke(
                    canvas, w, h, radius,
                    LiquidGlassGeometry.glowStrokeWidth(i, stroke),
                    LiquidGlassGeometry.glowAlphaRatio(i)
                )
            }
        }
        drawGlassStroke(canvas, w, h, radius, stroke, 1f)
    }

    /** Vẽ 1 vòng stroke canh mép ngoài trùng mép view; [alphaRatio] điều biến alpha của shader. */
    private fun drawGlassStroke(
        canvas: Canvas, w: Float, h: Float, radius: Float, strokeWidth: Float, alphaRatio: Float
    ) {
        val inset = LiquidGlassGeometry.strokeInset(strokeWidth)
        // stroke dày hơn cả view → rect thụt vào sẽ âm, bỏ qua vòng này
        if (inset * 2f >= minOf(w, h)) return

        drawRect.set(inset, inset, w - inset, h - inset)
        val insetRadius = LiquidGlassGeometry.insetRadius(radius, inset)
        strokePaint.strokeWidth = strokeWidth
        strokePaint.alpha = LiquidGlassGeometry.toAlphaByte(alphaRatio)
        canvas.drawRoundRect(drawRect, insetRadius, insetRadius, strokePaint)
    }

    companion object {
        private const val DEFAULT_HIGHLIGHT_ALPHA = 0.55f
    }
}
