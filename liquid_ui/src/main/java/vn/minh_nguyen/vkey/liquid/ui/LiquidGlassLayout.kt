package vn.minh_nguyen.vkey.liquid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
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
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Nút / khung "liquid glass" có blur nền thật và khúc xạ ở mép (kiểu iOS), tự vẽ bằng Canvas. Khác [LiquidGlassView] (không blur, dùng
 * được mọi nơi): view này làm mờ video / ảnh phủ màn nằm sau nó nên hợp với nút nổi trên trình phát video. Lớp kính gồm, từ
 * dưới lên:
 * 0. nội dung phía sau làm mờ kiểu iOS ([blurEnabled]): video (TextureView) và ảnh bìa lấy qua [GlassBackdrop], đậm màu hơn và phóng nhẹ;
 * 1. nền màu [glassColor] (mặc định #171717 30 %);
 * 2. ánh sáng trượt trên mặt kính: sáng nhẹ ở phía nguồn sáng, tắt dần về giữa, hắt lại rất nhẹ ở mép đối diện;
 * 3. viền kính dày [rimWidth]: sáng nhất ở phía nguồn sáng ([lightAngle], mặc định trên trái), sáng vừa ở mép đối diện (ánh khúc xạ), gần
 *    như tắt ở hai bên (rim light).
 * Bo góc theo [cornerRadius]; âm (mặc định) = bo tròn hai đầu theo nửa cạnh ngắn (hình tròn khi vuông). Con được cắt theo hình kính.
 * Bấm được thì lún nhẹ còn [pressScale] khi giữ, thả ra nảy về (không dùng ripple).
 *
 * XML: `app:lgl_color`, `app:lgl_cornerRadius`, `app:lgl_rimWidth`, `app:lgl_rimColor`, `app:lgl_rimAlpha`, `app:lgl_lightAngle`,
 * `app:lgl_sheenAlpha`, `app:lgl_pressScale`, `app:lgl_blur`, `app:lgl_refraction`.
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Màu nền kính (kèm độ mờ). */
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

    /** Độ dày viền kính (px). */
    var rimWidth: Float = resources.displayMetrics.density * DEFAULT_RIM_DP
        set(value) {
            field = value
            rimPaint.strokeWidth = value
            invalidate()
        }

    /** Màu viền sáng và độ sáng lớn nhất của viền (0..1) ở phía nguồn sáng. */
    @ColorInt
    var rimColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidateShaders()
        }
    var rimAlpha: Float = DEFAULT_RIM_ALPHA
        set(value) {
            field = value
            invalidateShaders()
        }

    /** Hướng nguồn sáng tính từ tâm, độ, 0 = bên phải, tăng theo chiều kim đồng hồ (225 = trên trái). */
    var lightAngle: Float = DEFAULT_LIGHT_ANGLE
        set(value) {
            field = value
            invalidateShaders()
        }

    /** Độ sáng lớn nhất của ánh trượt trên mặt kính (0..1); 0 = tắt. */
    var sheenAlpha: Float = DEFAULT_SHEEN_ALPHA
        set(value) {
            field = value
            invalidateShaders()
        }

    /** Tỉ lệ thu nhỏ khi đang giữ (1 = không lún). */
    var pressScale: Float = DEFAULT_PRESS_SCALE

    /** Làm mờ nội dung phía sau (video, ảnh bìa) qua [GlassBackdrop]; tắt thì chỉ còn nền màu. */
    var blurEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Độ bẻ cong nền ở mép kính (khúc xạ như thấu kính lỏng): mép nút kéo vào phần nền nằm ngoài nút tới [refraction] lần bán kính, càng
     * gần mép càng dồn nén, giữa nút gần như phẳng. 0 = tắt (nền mờ phẳng).
     */
    var refraction: Float = DEFAULT_REFRACTION
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /**
     * Mảnh nền riêng của nút cho chế độ bẻ cong: phần ảnh nền mờ quanh nút (rộng hơn nút một vành [refraction]), chép lại từ ảnh nền dùng
     * chung trước mỗi lượt vẽ rồi vẽ qua lưới [MESH] x [MESH] có các đỉnh dồn về phía tâm.
     */
    private var lens: Bitmap? = null
    private var lensCanvas: Canvas? = null
    private var lensCropX = 0
    private var lensCropY = 0
    private val lensVertices = FloatArray((MESH + 1) * (MESH + 1) * 2)

    /** Ảnh nền mờ vẽ đậm màu hơn (như kính iOS) và phóng to nhẹ quanh tâm nút cho cảm giác thấu kính. */
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(BACKDROP_SATURATION) })
    }
    private val windowLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var drawnX = Int.MIN_VALUE
    private var drawnY = Int.MIN_VALUE

    /** Ảnh nền đã dùng ở lượt vẽ gần nhất (`null` = lượt đó chưa có ảnh nền). */
    private var drawnBackdrop: Bitmap? = null

    /**
     * Trước mỗi lượt vẽ của cửa sổ: cập nhật ảnh nền mờ dùng chung (tự giới hạn tần suất). Ảnh nền đổi nội dung tại chỗ nên không cần vẽ
     * lại nút; chỉ vẽ lại khi nút đổi chỗ trong cửa sổ (vuốt trang) hoặc ảnh nền vừa có / vừa mất / đổi cỡ.
     */
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (blurEnabled && isShown) {
            val backdrop = GlassBackdrop.of(rootView)
            backdrop.update(rootView)
            getLocationInWindow(windowLocation)
            val current = backdrop.bitmap?.takeIf { backdrop.hasContent }
            if (windowLocation[0] != drawnX || windowLocation[1] != drawnY || current !== drawnBackdrop) {
                invalidate()
            } else if (current != null && refraction > 0f) {
                // Nút đứng yên: chỉ cần chép khung hình nền mới vào mảnh nền của nút
                copyLens(current)
            }
        }
        true
    }

    private fun copyLens(backdrop: Bitmap) {
        val canvas = lensCanvas ?: return
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(backdrop, -lensCropX.toFloat(), -lensCropY.toFloat(), null)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var shadersDirty = true

    init {
        if (attrs != null) {
            val values = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassLayout)
            glassColor = values.getColor(R.styleable.LiquidGlassLayout_lgl_color, glassColor)
            cornerRadius = values.getDimension(R.styleable.LiquidGlassLayout_lgl_cornerRadius, cornerRadius)
            rimWidth = values.getDimension(R.styleable.LiquidGlassLayout_lgl_rimWidth, rimWidth)
            rimColor = values.getColor(R.styleable.LiquidGlassLayout_lgl_rimColor, rimColor)
            rimAlpha = values.getFloat(R.styleable.LiquidGlassLayout_lgl_rimAlpha, rimAlpha)
            lightAngle = values.getFloat(R.styleable.LiquidGlassLayout_lgl_lightAngle, lightAngle)
            sheenAlpha = values.getFloat(R.styleable.LiquidGlassLayout_lgl_sheenAlpha, sheenAlpha)
            pressScale = values.getFloat(R.styleable.LiquidGlassLayout_lgl_pressScale, pressScale)
            blurEnabled = values.getBoolean(R.styleable.LiquidGlassLayout_lgl_blur, blurEnabled)
            refraction = values.getFloat(R.styleable.LiquidGlassLayout_lgl_refraction, refraction)
            values.recycle()
        }
        fillPaint.color = glassColor
        rimPaint.strokeWidth = rimWidth
        setWillNotDraw(false)
        // Con (icon, chữ) cắt theo hình kính
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, resolvedRadius(view.width.toFloat(), view.height.toFloat()))
            }
        }
        clipToOutline = true
    }

    private fun resolvedRadius(width: Float, height: Float): Float {
        val pill = min(width, height) / 2f
        return if (cornerRadius < 0f) pill else min(cornerRadius, pill)
    }

    private fun invalidateShaders() {
        shadersDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadersDirty = true
    }

    /** Dựng lại gradient ánh trượt và viền theo kích thước, hướng sáng hiện tại. */
    private fun buildShaders(width: Float, height: Float) {
        shadersDirty = false
        val centerX = width / 2f
        val centerY = height / 2f
        val radians = Math.toRadians(lightAngle.toDouble())
        val dirX = cos(radians).toFloat()
        val dirY = sin(radians).toFloat()

        // Ánh trượt: từ mép phía nguồn sáng tới mép đối diện
        sheenPaint.shader = if (sheenAlpha <= 0f) null else LinearGradient(
            centerX + dirX * width / 2f, centerY + dirY * height / 2f,
            centerX - dirX * width / 2f, centerY - dirY * height / 2f,
            intArrayOf(
                withAlpha(rimColor, sheenAlpha),
                withAlpha(rimColor, 0f),
                withAlpha(rimColor, 0f),
                withAlpha(rimColor, sheenAlpha * SHEEN_BACK_RATIO)
            ),
            floatArrayOf(0f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        // Viền: sáng nhất hướng nguồn sáng, sáng vừa hướng đối diện, mờ ở hai bên. SweepGradient chạy từ 0° (bên phải) theo chiều kim đồng hồ
        val colors = IntArray(RIM_STOPS + 1)
        val positions = FloatArray(RIM_STOPS + 1)
        for (i in 0..RIM_STOPS) {
            val angle = 360f * i / RIM_STOPS
            val delta = Math.toRadians((angle - lightAngle).toDouble())
            val facing = cos(delta).toFloat()
            val lit = if (facing > 0f) facing.pow(RIM_FOCUS) else 0f
            val back = if (facing < 0f) (-facing).pow(RIM_FOCUS) * RIM_BACK_RATIO else 0f
            val strength = RIM_SIDE_RATIO + (1f - RIM_SIDE_RATIO) * (lit + back)
            colors[i] = withAlpha(rimColor, rimAlpha * strength.coerceIn(0f, 1f))
            positions[i] = i.toFloat() / RIM_STOPS
        }
        rimPaint.shader = SweepGradient(centerX, centerY, colors, positions)
    }

    private fun withAlpha(@ColorInt color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        if (shadersDirty) buildShaders(width, height)
        val radius = resolvedRadius(width, height)
        drawBackdrop(canvas, width, height)
        rect.set(0f, 0f, width, height)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        if (sheenPaint.shader != null) canvas.drawRoundRect(rect, radius, radius, sheenPaint)
        if (rimWidth > 0f && rimAlpha > 0f) {
            // Viền nằm trong mép kính
            val inset = rimWidth / 2f
            rect.set(inset, inset, width - inset, height - inset)
            val rimRadius = (radius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, rimRadius, rimRadius, rimPaint)
        }
        super.onDraw(canvas)
    }

    /**
     * Vẽ phần ảnh nền mờ nằm đúng sau nút (ảnh nhỏ hơn cửa sổ [GlassBackdrop.SCALE] lần nên phóng lại bằng lọc song tuyến tính → mờ mịn),
     * phóng thêm [BACKDROP_ZOOM] quanh tâm nút. View đã cắt theo hình kính (clipToOutline). Chưa có ảnh nền thì bỏ qua.
     */
    private fun drawBackdrop(canvas: Canvas, width: Float, height: Float) {
        getLocationInWindow(windowLocation)
        drawnX = windowLocation[0]
        drawnY = windowLocation[1]
        drawnBackdrop = null
        if (!blurEnabled || !isAttachedToWindow) return
        val root = rootView
        val backdrop = GlassBackdrop.of(root)
        val bitmap = backdrop.bitmap?.takeIf { backdrop.hasContent } ?: return
        drawnBackdrop = bitmap
        root.getLocationInWindow(rootLocation)
        if (refraction > 0f) {
            drawRefracted(canvas, bitmap, width, height)
            return
        }
        val scale = GlassBackdrop.SCALE.toFloat()
        val save = canvas.save()
        canvas.scale(BACKDROP_ZOOM, BACKDROP_ZOOM, width / 2f, height / 2f)
        canvas.translate(-(drawnX - rootLocation[0]).toFloat(), -(drawnY - rootLocation[1]).toFloat())
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, backdropPaint)
        canvas.restoreToCount(save)
    }

    /**
     * Nền mờ bị bẻ cong ở mép. Điểm trên nút cách tâm d (0 = tâm, 1 = mép, theo nửa rộng / nửa cao) hiện phần nền cách tâm
     * `d · (1 + refraction · d^REFRACTION_POWER) / BACKDROP_ZOOM`: giữa nút chỉ phóng nhẹ, sát mép thì lấy cả vành nền bên ngoài nút dồn
     * vào. Vẽ bằng `drawBitmapMesh`: mỗi đỉnh lưới của mảnh nền (vị trí gốc s) được đặt về vị trí d tương ứng (giải ngược hàm trên).
     */
    private fun drawRefracted(canvas: Canvas, backdrop: Bitmap, width: Float, height: Float) {
        val scale = GlassBackdrop.SCALE
        val x = drawnX - rootLocation[0]
        val y = drawnY - rootLocation[1]
        // Vành nền ngoài nút mà mép kính với tới
        val reach = ((1f + refraction) / BACKDROP_ZOOM - 1f).coerceAtLeast(0f) * max(width, height) / 2f
        val margin = ceil(reach / scale).toInt() + 2
        val lensWidth = ceil(width / scale).toInt() + margin * 2 + 1
        val lensHeight = ceil(height / scale).toInt() + margin * 2 + 1
        var lens = lens
        if (lens == null || lens.width != lensWidth || lens.height != lensHeight) {
            lens = Bitmap.createBitmap(lensWidth, lensHeight, Bitmap.Config.ARGB_8888)
            this.lens = lens
            lensCanvas = Canvas(lens)
        }
        lensCropX = x.floorDiv(scale) - margin
        lensCropY = y.floorDiv(scale) - margin
        copyLens(backdrop)

        val centerX = width / 2f
        val centerY = height / 2f
        var index = 0
        for (row in 0..MESH) {
            // Vị trí gốc của đỉnh lưới, tính trong toạ độ của nút
            val sourceY = (lensCropY + lensHeight * row / MESH.toFloat()) * scale - y
            for (column in 0..MESH) {
                val sourceX = (lensCropX + lensWidth * column / MESH.toFloat()) * scale - x
                val offsetX = sourceX - centerX
                val offsetY = sourceY - centerY
                val source = hypot(offsetX / centerX, offsetY / centerY)
                val ratio = if (source > 1e-4f) lensDistance(source) / source else BACKDROP_ZOOM
                lensVertices[index++] = centerX + offsetX * ratio
                lensVertices[index++] = centerY + offsetY * ratio
            }
        }
        canvas.drawBitmapMesh(lens, MESH, MESH, lensVertices, 0, null, 0, backdropPaint)
    }

    /** Khoảng cách d trên nút hiện phần nền ở khoảng cách [source]: giải `d · (1 + refraction · d^n) / zoom = source` bằng chia đôi. */
    private fun lensDistance(source: Float): Float {
        var low = 0f
        var high = LENS_MAX_DISTANCE
        repeat(LENS_SOLVE_STEPS) {
            val middle = (low + high) / 2f
            val value = middle * (1f + refraction * middle.pow(REFRACTION_POWER)) / BACKDROP_ZOOM
            if (value < source) low = middle else high = middle
        }
        return (low + high) / 2f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        super.onDetachedFromWindow()
    }

    /** Giữ: lún nhẹ; thả: nảy về đúng cỡ. Chỉ khi view bấm được. */
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

    private companion object {
        /** #171717 30 %. */
        val DEFAULT_GLASS_COLOR = Color.argb(77, 23, 23, 23)
        const val DEFAULT_RIM_DP = 1f
        const val DEFAULT_RIM_ALPHA = 0.55f
        const val DEFAULT_LIGHT_ANGLE = 225f
        const val DEFAULT_SHEEN_ALPHA = 0.16f
        const val DEFAULT_PRESS_SCALE = 0.92f

        /** Viền: số mốc màu quanh chu vi, độ tụ của vùng sáng, độ sáng mép đối diện và hai bên so với phía nguồn sáng. */
        const val RIM_STOPS = 24
        const val RIM_FOCUS = 2.2f
        const val RIM_BACK_RATIO = 0.55f
        const val RIM_SIDE_RATIO = 0.14f

        /** Ánh hắt lại ở mép đối diện so với ánh trượt chính. */
        const val SHEEN_BACK_RATIO = 0.3f

        /** Ảnh nền mờ: đậm màu hơn 40 %, phóng to 12 % quanh tâm nút. */
        const val BACKDROP_SATURATION = 1.4f
        const val BACKDROP_ZOOM = 1.12f

        /** Bẻ cong nền: mép kính với tới vành nền ngoài nút bằng 80 % bán kính; độ cong dồn về mép theo luỹ thừa 3. */
        const val DEFAULT_REFRACTION = 0.8f
        const val REFRACTION_POWER = 3f

        /** Lưới vẽ mảnh nền bẻ cong 20 x 20 ô; giải ngược hàm thấu kính trong khoảng 0..3 lần bán kính bằng 24 bước chia đôi. */
        const val MESH = 20
        const val LENS_MAX_DISTANCE = 3f
        const val LENS_SOLVE_STEPS = 24

        const val PRESS_MS = 110L
        const val RELEASE_MS = 320L
    }
}
