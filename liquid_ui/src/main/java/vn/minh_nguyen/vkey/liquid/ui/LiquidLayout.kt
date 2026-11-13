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
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
 *   app:lgv_highlight            — false: tắt hẳn viền sáng lẫn quầng sáng (mặc định true)
 *   app:lgv_glow                 — quầng sáng mềm loang vào trong (tắt được cho item list)
 *   app:lgv_clipContent          — bo góc luôn content bên trong (mặc định true)
 *   app:lgv_mode                 — `tint` (mặc định), `blur` hoặc `lens`, xem [LiquidGlassMode]
 *   app:lgv_blurRadius           — (blur) độ mờ của nền, mặc định 4dp
 *   app:lgv_backdrop             — (blur) `all` (mặc định) làm mờ mọi thứ phía sau, `media` chỉ video / ảnh phủ màn
 *   app:lgv_edgeRefraction       — (blur) bẻ cong nền mờ ở mép, kéo cả vành nền ngoài view vào (0 = tắt, mặc định)
 *   app:lgv_blurSaturation       — (blur) độ đậm màu của nền mờ (1 = giữ nguyên, mặc định)
 *   app:lgv_pressScale           — view bấm được thì lún còn chừng này khi giữ (1 = không lún, mặc định)
 *   app:lgv_bevelWidth           — (lens) bề rộng vành mép vát tròn, phần bị bẻ cong
 *   app:lgv_refraction           — (lens) độ bẻ cong ở vành mép; 0 = không bẻ
 *   app:lgv_lensZoom             — (lens) phóng to nội dung phía sau quanh tâm (1 = không phóng)
 *   app:lgv_lensBlur             — (lens) độ mờ của nội dung nhìn qua kính (0 = nét, mặc định)
 *
 * Chế độ `lens` ([LiquidGlassMode.LENS]) là kính bẻ cong thật: mặt kính phẳng, vành mép vát tròn.
 * Nội dung phía sau (giao diện thường lẫn video vẽ bằng `TextureView`) hiện **nét nguyên** ở giữa,
 * riêng vành mép rộng [bevelWidth] bị khúc xạ theo đúng hình kính — tròn, pill hay chữ nhật bo góc.
 * Nền chụp lại mỗi khung hình mà cửa sổ vẽ lại, nên cuộn nội dung bên dưới hay kéo view đi thì
 * hình trong kính chạy theo. Chế độ này tốn hơn `tint` (vẽ lại phần giao diện nằm sau view): hợp
 * với nút, thanh nổi, kính lúp; đừng dùng cho hàng chục item trong list.
 *
 * Chế độ `blur` ([LiquidGlassMode.BLUR]) là kính mờ kiểu iOS: làm mờ thật mọi thứ phía sau (giao diện
 * thường lẫn video vẽ bằng `TextureView`) rồi phủ lớp màu và vệt sáng lên trên. Nền lấy từ một ảnh mờ
 * dùng chung cho mọi view cùng cửa sổ ([GlassBackdrop]) nên nhẹ, đặt được nhiều view cùng lúc. View
 * nằm trong hộp thoại / bottom sheet thì đặt [backdropRoot] là nội dung của màn phía sau.
 *
 * Lưu ý:
 * - Chế độ `tint`: view **không đụng tới nội dung phía sau**; nó chỉ phủ một lớp đen mờ + vệt sáng
 *   vẽ bằng Canvas, nhờ vậy dùng được ở mọi nơi (kể cả item RecyclerView) mà không cần `Activity`.
 * - `GlassLayout`, `GlassLensLayout`, `LiquidGlassLayout` cũ đã gom vào view này (chế độ `blur` / `lens`).
 * - Vệt sáng **không tự đảo chiều theo RTL**.
 */
class LiquidLayout @JvmOverloads constructor(
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

    /**
     * Viền sáng (vệt sáng nét + quầng sáng). Tắt thì view không vẽ viền nào: cùng nền trong suốt và chế độ
     * [LiquidGlassMode.LENS] sẽ ra tấm kính trơn, chỉ còn phần bẻ cong ở vành mép.
     */
    var isHighlightEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
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

    /**
     * Chế độ nền: [LiquidGlassMode.TINT] (mặc định, không đụng tới nội dung phía sau), [LiquidGlassMode.BLUR] (kính mờ) hoặc
     * [LiquidGlassMode.LENS] (thấu kính: nền nét, bẻ cong thật ở vành mép).
     */
    var mode: Int = LiquidGlassMode.TINT
        set(value) {
            if (field == value) return
            val old = field
            field = value
            if (old == LiquidGlassMode.LENS) lens.release()
            if (old == LiquidGlassMode.BLUR && isAttachedToWindow) blur.release(backdropRoot ?: rootView)
            invalidate()
        }

    /** View có lấy nền phía sau (mờ hoặc thấu kính): khi đó nó và content của nó không được chụp vào nền. */
    internal val capturesBackdrop: Boolean get() = mode != LiquidGlassMode.TINT

    /**
     * (blur) Độ mờ của nền (px): độ lệch chuẩn của mờ Gauss, như `blur()` của CSS — càng lớn nền càng nhoè. 0 = không làm mờ thêm (nền
     * vẫn hơi mềm vì chụp thu nhỏ). Mặc định 4dp.
     */
    var blurRadius: Float = resources.displayMetrics.density * DEFAULT_BLUR_DP
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /**
     * (blur) Làm mờ những gì phía sau: [GlassBackdropMode.ALL] (mặc định) = mọi thứ đang hiện, cả giao diện thường lẫn video;
     * [GlassBackdropMode.MEDIA] = chỉ video và ảnh phủ màn (nhẹ hơn).
     */
    var backdropMode: Int = GlassBackdropMode.ALL
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * (blur) Bẻ cong nền mờ ở mép: mép kéo vào phần nền nằm ngoài view tới [edgeRefraction] lần nửa cạnh ngắn, càng gần mép càng dồn
     * nén, giữa view gần như phẳng. 0 (mặc định) = nền mờ phẳng. Kiểu bẻ cong của `GlassLensLayout` / `LiquidGlassLayout` cũ (0.8).
     */
    var edgeRefraction: Float = 0f
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /** (blur) Độ đậm màu của nền đã làm mờ; 1 (mặc định) = giữ nguyên, kính kiểu iOS dùng khoảng 1.4. */
    var blurSaturation: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** View bấm được thì lún còn chừng này khi giữ, thả ra nảy về (không dùng ripple); 1 (mặc định) = không lún. */
    var pressScale: Float = 1f

    /** (lens) Bề rộng vành mép vát tròn (px) — chỉ phần này bị bẻ cong, phía trong là mặt kính phẳng. Không quá nửa cạnh ngắn. */
    var bevelWidth: Float = 0f
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /**
     * (lens) Độ bẻ cong ở vành mép: sát mép, kính hiện phần nền nằm sâu vào trong [refraction] lần [bevelWidth]. 1 (mặc định) = mép
     * hiện lại đúng chỗ vành bắt đầu, nên nội dung trong vành bị ép lại và lật ngược như nhìn qua mép kính dày. 0 = không bẻ.
     */
    var refraction: Float = DEFAULT_REFRACTION
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /** (lens) Phóng to nội dung phía sau quanh tâm view như kính lúp; 1 (mặc định) = không phóng. */
    var lensZoom: Float = 1f
        set(value) {
            val clamped = value.coerceAtLeast(1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /**
     * (lens) Độ mờ của nội dung nhìn qua kính (px): độ lệch chuẩn của mờ Gauss, như `blur()` của CSS — càng lớn càng nhoè. 0 (mặc định) =
     * nét nguyên. Vành mép vẫn bẻ cong như cũ, trên nền đã mờ.
     */
    var lensBlur: Float = 0f
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /**
     * (blur, lens) Gốc của phần nội dung nằm sau kính; `null` (mặc định) = gốc cửa sổ chứa view này. View nằm trong cửa sổ khác với nội
     * dung phía sau (hộp thoại, bottom sheet, popup) thì đặt là view nội dung của màn phía sau, vd
     * `activity.findViewById(android.R.id.content)`.
     */
    var backdropRoot: View? = null
        set(value) {
            if (field !== value && isAttachedToWindow) blur.release(field ?: rootView)
            field = value
            invalidate()
        }

    /** Phần thấu kính của chế độ [LiquidGlassMode.LENS] và phần nền mờ của chế độ [LiquidGlassMode.BLUR]. */
    private val lens = LiquidLens(this)
    private val blur = GlassBlur(this)

    /**
     * Trước mỗi lượt vẽ của cửa sổ: thấu kính chụp lại nền và bẻ cong lại ([LiquidLens.update]); kính mờ cập nhật ảnh nền mờ dùng chung
     * ([GlassBlur.onPreDraw]). Cả hai tự giới hạn tần suất; chế độ tint không làm gì.
     */
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        when (mode) {
            LiquidGlassMode.LENS -> lens.update(
                backdropRoot ?: rootView, bevelWidth, refraction, lensZoom,
                LiquidGlassGeometry.resolveRadius(isPill, cornerRadius, width.toFloat(), height.toFloat()), lensBlur
            )
            LiquidGlassMode.BLUR -> {
                syncBlur()
                blur.onPreDraw(backdropRoot ?: rootView, all = backdropMode == GlassBackdropMode.ALL)
            }
        }
        true
    }

    /** Đẩy thông số hiện tại sang phần nền mờ. */
    private fun syncBlur() {
        blur.enabled = mode == LiquidGlassMode.BLUR
        blur.blurRadius = blurRadius
        blur.refraction = edgeRefraction
        blur.zoom = lensZoom
        blur.saturation = blurSaturation
    }

    init {
        // ViewGroup mặc định bỏ qua onDraw — phải tắt cờ này thì nền mới được vẽ
        setWillNotDraw(false)

        val ta = context.obtainStyledAttributes(
            attrs, R.styleable.LiquidLayout, defStyleAttr, 0
        )
        glassColor = ta.getColor(
            R.styleable.LiquidLayout_lgv_backgroundColor,
            ContextCompat.getColor(context, R.color.liquid_glass_background)
        )
        cornerRadius = ta.getDimension(
            R.styleable.LiquidLayout_lgv_cornerRadius,
            resources.getDimension(R.dimen.liquid_glass_corner_radius)
        )
        isPill = ta.getBoolean(R.styleable.LiquidLayout_lgv_pill, false)
        highlightColor = ta.getColor(
            R.styleable.LiquidLayout_lgv_highlightColor,
            ContextCompat.getColor(context, R.color.liquid_glass_highlight)
        )
        highlightWidth = ta.getDimension(
            R.styleable.LiquidLayout_lgv_highlightWidth,
            resources.getDimension(R.dimen.liquid_glass_highlight_width)
        )
        highlightAlpha = ta.getFloat(
            R.styleable.LiquidLayout_lgv_highlightAlpha,
            DEFAULT_HIGHLIGHT_ALPHA
        )
        highlightOrientation = ta.getInt(
            R.styleable.LiquidLayout_lgv_highlightOrientation,
            LiquidGlassOrientation.TOP_LEFT_BOTTOM_RIGHT
        )
        isHighlightEnabled = ta.getBoolean(R.styleable.LiquidLayout_lgv_highlight, true)
        isGlowEnabled = ta.getBoolean(R.styleable.LiquidLayout_lgv_glow, true)
        isClipContent = ta.getBoolean(R.styleable.LiquidLayout_lgv_clipContent, true)
        mode = ta.getInt(R.styleable.LiquidLayout_lgv_mode, LiquidGlassMode.TINT)
        bevelWidth = ta.getDimension(
            R.styleable.LiquidLayout_lgv_bevelWidth,
            resources.getDimension(R.dimen.liquid_glass_bevel_width)
        )
        refraction = ta.getFloat(R.styleable.LiquidLayout_lgv_refraction, DEFAULT_REFRACTION)
        lensZoom = ta.getFloat(R.styleable.LiquidLayout_lgv_lensZoom, 1f)
        lensBlur = ta.getDimension(R.styleable.LiquidLayout_lgv_lensBlur, 0f)
        blurRadius = ta.getDimension(R.styleable.LiquidLayout_lgv_blurRadius, blurRadius)
        backdropMode = ta.getInt(R.styleable.LiquidLayout_lgv_backdrop, backdropMode)
        edgeRefraction = ta.getFloat(R.styleable.LiquidLayout_lgv_edgeRefraction, 0f)
        blurSaturation = ta.getFloat(R.styleable.LiquidLayout_lgv_blurSaturation, 1f)
        pressScale = ta.getFloat(R.styleable.LiquidLayout_lgv_pressScale, 1f)
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
        clipContent: Boolean = this.isClipContent,
        mode: Int = this.mode,
        bevelWidth: Float = this.bevelWidth,
        refraction: Float = this.refraction,
        lensZoom: Float = this.lensZoom,
        highlight: Boolean = this.isHighlightEnabled,
        lensBlur: Float = this.lensBlur
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
        this.mode = mode
        this.bevelWidth = bevelWidth
        this.refraction = refraction
        this.lensZoom = lensZoom
        this.isHighlightEnabled = highlight
        this.lensBlur = lensBlur
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
     * Đang chụp nền cho view kính ([GlassBackdrop.CaptureCanvas]) mà view này cũng là kính (mờ hoặc thấu kính): không vẽ gì, để nó và
     * content của nó không lẫn vào nền của chính nó (hay của view kính khác).
     */
    override fun draw(canvas: Canvas) {
        if (capturesBackdrop && canvas is GlassBackdrop.CaptureCanvas) return
        super.draw(canvas)
    }

    /** Giữ: lún nhẹ còn [pressScale]; thả: nảy về đúng cỡ. Chỉ khi view bấm được và [pressScale] < 1. */
    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (!changed || !isClickable || pressScale >= 1f) return
        animate().cancel()
        if (pressed) {
            animate().scaleX(pressScale).scaleY(pressScale).setDuration(PRESS_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            animate().scaleX(1f).scaleY(1f).setDuration(RELEASE_MS).setInterpolator(OvershootInterpolator(2.5f)).start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        lens.release()
        blur.release(backdropRoot ?: rootView)
        super.onDetachedFromWindow()
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

        when (mode) {
            LiquidGlassMode.LENS -> lens.draw(canvas, w, h, radius, clip = !isClipContent)
            LiquidGlassMode.BLUR -> {
                syncBlur()
                blur.draw(canvas, backdropRoot ?: rootView, w, h, radius, clip = !isClipContent)
            }
        }

        drawRect.set(0f, 0f, w, h)
        fillPaint.color = glassColor
        canvas.drawRoundRect(drawRect, radius, radius, fillPaint)

        val stroke = LiquidGlassGeometry.clampStrokeWidth(highlightWidth, w, h)
        if (!isHighlightEnabled || stroke <= 0f || highlightAlpha <= 0f) return

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
        private const val DEFAULT_REFRACTION = 1f

        /** Độ mờ mặc định của chế độ blur: mờ Gauss độ lệch chuẩn 4dp. */
        private const val DEFAULT_BLUR_DP = 4f

        private const val PRESS_MS = 110L
        private const val RELEASE_MS = 320L
    }
}
