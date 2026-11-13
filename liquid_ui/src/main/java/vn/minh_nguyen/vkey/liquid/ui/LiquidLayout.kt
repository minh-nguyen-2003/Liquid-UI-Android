package vn.minh_nguyen.vkey.liquid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

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
 *   app:lgv_mode                 — `tint` (mặc định) hoặc `lens`, xem [LiquidGlassMode]
 *   app:lgv_bevelWidth           — (lens) bề rộng vành mép vát tròn, phần bị bẻ cong
 *   app:lgv_refraction           — (lens) độ bẻ cong ở vành mép; 0 = không bẻ
 *   app:lgv_lensZoom             — (lens) phóng to nội dung phía sau quanh tâm (1 = không phóng)
 *
 * Chế độ `lens` ([LiquidGlassMode.LENS]) là kính bẻ cong thật: mặt kính phẳng, vành mép vát tròn.
 * Nội dung phía sau (giao diện thường lẫn video vẽ bằng `TextureView`) hiện **nét nguyên** ở giữa,
 * riêng vành mép rộng [bevelWidth] bị khúc xạ theo đúng hình kính — tròn, pill hay chữ nhật bo góc.
 * Nền chụp lại mỗi khung hình mà cửa sổ vẽ lại, nên cuộn nội dung bên dưới hay kéo view đi thì
 * hình trong kính chạy theo. Chế độ này tốn hơn `tint` (vẽ lại phần giao diện nằm sau view): hợp
 * với nút, thanh nổi, kính lúp; đừng dùng cho hàng chục item trong list.
 *
 * Lưu ý:
 * - Chế độ `tint`: view **không đụng tới nội dung phía sau**; nó chỉ phủ một lớp đen mờ + vệt sáng
 *   vẽ bằng Canvas, nhờ vậy dùng được ở mọi nơi (kể cả item RecyclerView) mà không cần `Activity`.
 * - View **không blur**. Cần kính mờ thì dùng [GlassLayout] / [LiquidGlassLayout].
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

    /** Chế độ nền: [LiquidGlassMode.TINT] (mặc định) hoặc [LiquidGlassMode.LENS] (bẻ cong nền thật ở vành mép). */
    var mode: Int = LiquidGlassMode.TINT
        set(value) {
            if (field == value) return
            field = value
            if (value != LiquidGlassMode.LENS) releaseLens()
            invalidate()
        }

    /** (lens) Bề rộng vành mép vát tròn (px) — chỉ phần này bị bẻ cong, phía trong là mặt kính phẳng. Không quá nửa cạnh ngắn. */
    var bevelWidth: Float = 0f
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field == clamped) return
            field = clamped
            meshDirty = true
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
            meshDirty = true
            invalidate()
        }

    /** (lens) Phóng to nội dung phía sau quanh tâm view như kính lúp; 1 (mặc định) = không phóng. */
    var lensZoom: Float = 1f
        set(value) {
            val clamped = value.coerceAtLeast(1f)
            if (field == clamped) return
            field = clamped
            meshDirty = true
            invalidate()
        }

    /**
     * (lens) Gốc của phần nội dung nhìn qua kính; `null` (mặc định) = gốc cửa sổ chứa view này. View nằm trong cửa sổ khác với nội dung
     * phía sau (hộp thoại, bottom sheet, popup) thì đặt là view nội dung của màn phía sau.
     */
    var backdropRoot: View? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Phần thấu kính. [capture] = ảnh nét của phần giao diện nằm đúng sau view; [lens] = ảnh đó sau khi bẻ cong, là thứ view vẽ ra. Cả hai
     * cỡ view x [lensScale] (view rất lớn thì thu nhỏ để giữ chi phí). Bẻ cong bằng lưới tam giác: đỉnh lưới [meshVerts] trải đều trên
     * [lens], mỗi đỉnh lấy màu ở [meshTexs] trên [capture] — vị trí đã khúc xạ, tính sẵn theo hình kính, chỉ tính lại khi view đổi cỡ
     * hoặc đổi thông số. Nội dung hai ảnh đổi tại chỗ trước mỗi lượt vẽ của cửa sổ nên không cần vẽ lại view.
     */
    private var capture: Bitmap? = null
    private var captureCanvas: GlassBackdrop.CaptureCanvas? = null
    private var lens: Bitmap? = null
    private var lensCanvas: Canvas? = null
    private var lensScale = 1f
    private var lensReady = false
    private var meshVerts = FloatArray(0)
    private var meshTexs = FloatArray(0)
    private var meshIndices = ShortArray(0)
    private var meshDirty = true
    private var lastLensAt = 0L
    private var lensX = Int.MIN_VALUE
    private var lensY = Int.MIN_VALUE
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lensPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val lensRect = RectF()
    private val lensClip = Path()
    private val selfLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    /**
     * Trước mỗi lượt vẽ của cửa sổ (có gì đó phía sau vừa đổi, hoặc view vừa hiện / đổi chỗ): chụp lại nền và bẻ cong lại, tối đa một
     * lần mỗi [LENS_MIN_INTERVAL_MS]; view vừa đổi chỗ / đổi cỡ / đổi thông số thì làm ngay. Màn hình đứng yên thì không làm gì.
     */
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (mode == LiquidGlassMode.LENS && isShown && width > 0 && height > 0) {
            val fresh = ensureLens()
            getLocationOnScreen(selfLocation)
            val moved = selfLocation[0] != lensX || selfLocation[1] != lensY
            if (fresh || meshDirty || moved || SystemClock.uptimeMillis() - lastLensAt >= LENS_MIN_INTERVAL_MS) renderLens()
        }
        true
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
        isGlowEnabled = ta.getBoolean(R.styleable.LiquidLayout_lgv_glow, true)
        isClipContent = ta.getBoolean(R.styleable.LiquidLayout_lgv_clipContent, true)
        mode = ta.getInt(R.styleable.LiquidLayout_lgv_mode, LiquidGlassMode.TINT)
        bevelWidth = ta.getDimension(
            R.styleable.LiquidLayout_lgv_bevelWidth,
            resources.getDimension(R.dimen.liquid_glass_bevel_width)
        )
        refraction = ta.getFloat(R.styleable.LiquidLayout_lgv_refraction, DEFAULT_REFRACTION)
        lensZoom = ta.getFloat(R.styleable.LiquidLayout_lgv_lensZoom, 1f)
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
        lensZoom: Float = this.lensZoom
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
     * Đang chụp nền cho view kính ([GlassBackdrop.CaptureCanvas]) mà view này là thấu kính: không vẽ gì, để nó và content của nó không
     * lẫn vào hình nhìn qua chính nó (hay qua thấu kính khác).
     */
    override fun draw(canvas: Canvas) {
        if (mode == LiquidGlassMode.LENS && canvas is GlassBackdrop.CaptureCanvas) return
        super.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        releaseLens()
        super.onDetachedFromWindow()
    }

    private fun releaseLens() {
        capture = null
        captureCanvas = null
        lens = null
        lensCanvas = null
        lensReady = false
        meshDirty = true
    }

    /** Cấp hai ảnh của thấu kính theo cỡ view hiện tại; trả về `true` nếu vừa cấp mới (đổi cỡ, lần đầu). */
    private fun ensureLens(): Boolean {
        val scale = min(1f, sqrt(LENS_MAX_PIXELS / (width.toFloat() * height)))
        val lensWidth = ceil(width * scale).toInt().coerceAtLeast(1)
        val lensHeight = ceil(height * scale).toInt().coerceAtLeast(1)
        val current = lens
        if (current != null && current.width == lensWidth && current.height == lensHeight) return false
        val raw = Bitmap.createBitmap(lensWidth, lensHeight, Bitmap.Config.ARGB_8888)
        val bent = Bitmap.createBitmap(lensWidth, lensHeight, Bitmap.Config.ARGB_8888)
        capture = raw
        captureCanvas = GlassBackdrop.CaptureCanvas(raw)
        lens = bent
        lensCanvas = Canvas(bent)
        lensScale = scale
        lensReady = false
        meshDirty = true
        return true
    }

    /**
     * Chụp nét phần giao diện nằm sau view vào [capture] rồi bẻ cong sang [lens]. Vị trí tính theo màn hình để gốc nền ở cửa sổ khác
     * (màn phía sau hộp thoại) vẫn khớp.
     */
    private fun renderLens() {
        val raw = capture ?: return
        val rawCanvas = captureCanvas ?: return
        val bent = lens ?: return
        val bentCanvas = lensCanvas ?: return
        val root = backdropRoot ?: rootView
        getLocationOnScreen(selfLocation)
        root.getLocationOnScreen(rootLocation)
        lensX = selfLocation[0]
        lensY = selfLocation[1]
        lastLensAt = SystemClock.uptimeMillis()

        val save = rawCanvas.save()
        rawCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        rawCanvas.scale(lensScale, lensScale)
        rawCanvas.translate(-(lensX - rootLocation[0]).toFloat(), -(lensY - rootLocation[1]).toFloat())
        GlassBackdrop.of(root).drawSharp(root, rawCanvas)
        rawCanvas.restoreToCount(save)

        if (meshDirty) buildMesh(bent.width, bent.height)
        // Shader dựng lại mỗi lượt để chắc chắn đọc nội dung mới của ảnh chụp
        meshPaint.shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bentCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        bentCanvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, meshVerts.size, meshVerts, 0, meshTexs, 0, null, 0,
            meshIndices, 0, meshIndices.size, meshPaint
        )
        lensReady = true
    }

    /**
     * Dựng lưới bẻ cong cho ảnh thấu kính cỡ [lensWidth] x [lensHeight]. Mặt kính phẳng với vành mép vát theo cung tròn rộng b =
     * [bevelWidth]: với mỗi đỉnh lưới p trên view,
     * - e = độ sâu của p tính từ mép hình kính (chữ nhật bo góc; 0 ở mép), n = hướng vuông góc với mép gần nhất, chỉ ra ngoài;
     * - trong vành (e < b): t = 1 − e / b, độ dốc mặt kính theo cung tròn nên p hiện phần nền ở `p − n · refraction · b · (1 − √(1 − t²))`
     *   — vừa vào vành thì gần như không lệch, sát mép thì lệch mạnh vào trong (nội dung bị ép và lật như qua mép kính dày);
     * - ngoài vành (e ≥ b): giữ nguyên.
     * Sau đó thu về quanh tâm theo [lensZoom]. Trường khoảng cách dùng bo góc không nhỏ hơn b để hướng n đổi mượt quanh góc.
     */
    private fun buildMesh(lensWidth: Int, lensHeight: Int) {
        meshDirty = false
        var cell = max(MESH_MIN_CELL_PX, resources.displayMetrics.density * MESH_CELL_DP * lensScale)
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

        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val inradius = min(centerX, centerY)
        val bevel = min(bevelWidth, inradius)
        val fieldRadius = max(LiquidGlassGeometry.resolveRadius(isPill, cornerRadius, w, h), bevel).coerceAtMost(inradius)
        val coreX = centerX - fieldRadius
        val coreY = centerY - fieldRadius
        val strength = refraction * bevel
        val zoom = lensZoom
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
                    val shift = strength * (1f - sqrt(1f - t * t))
                    sourceX -= normalX * shift
                    sourceY -= normalY * shift
                }
                sourceX = (centerX + (sourceX - centerX) / zoom).coerceIn(0f, w)
                sourceY = (centerY + (sourceY - centerY) / zoom).coerceIn(0f, h)
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

    /** Vẽ hình nhìn qua thấu kính, cắt theo hình kính (view đã cắt sẵn khi [isClipContent] bật). Chưa chụp được nền thì bỏ qua. */
    private fun drawLens(canvas: Canvas, w: Float, h: Float, radius: Float) {
        val bent = lens?.takeIf { lensReady } ?: return
        lensRect.set(0f, 0f, w, h)
        if (isClipContent) {
            canvas.drawBitmap(bent, null, lensRect, lensPaint)
            return
        }
        val save = canvas.save()
        lensClip.rewind()
        lensClip.addRoundRect(lensRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(lensClip)
        canvas.drawBitmap(bent, null, lensRect, lensPaint)
        canvas.restoreToCount(save)
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

        if (mode == LiquidGlassMode.LENS) drawLens(canvas, w, h, radius)

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
        private const val DEFAULT_REFRACTION = 1f

        /** Thấu kính chụp lại nền nhanh nhất mỗi 12 ms: theo từng khung hình ở 60 Hz, cách khung ở 120 Hz. */
        private const val LENS_MIN_INTERVAL_MS = 12L

        /** Ảnh thấu kính tối đa chừng này điểm ảnh; view lớn hơn thì ảnh thu nhỏ lại (hình hơi mềm đi) để giữ chi phí. */
        private const val LENS_MAX_PIXELS = 600_000f

        /** Ô lưới bẻ cong: 1,5dp, không nhỏ hơn 2 px; lưới không quá chừng này đỉnh (chỉ số 16 bit). */
        private const val MESH_CELL_DP = 1.5f
        private const val MESH_MIN_CELL_PX = 2f
        private const val MESH_MAX_VERTICES = 60_000
    }
}
