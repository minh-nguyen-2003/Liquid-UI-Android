package vn.minh_nguyen.vkey.liquid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Ảnh nền mờ dùng chung cho mọi view kính mờ ([LiquidLayout] chế độ blur, [GlassLayout] và lớp con) có cùng gốc nền: bản chụp thu nhỏ 1/[SCALE] của những thứ nằm sau các view
 * kính, rồi làm mờ. Mỗi view kính vẽ lại đúng phần ảnh nằm sau nó, phóng to lên nên ra lớp kính mờ kiểu iOS. Hai chế độ chụp:
 * - **toàn bộ** ([GlassBackdropMode.ALL]): mọi thứ đang hiện trong cây view của gốc nền — giao diện thường (danh sách, chữ, ảnh…) vẽ lại
 *   vào Canvas thường, khung hình video (TextureView không tự vẽ vào Canvas thường nên đọc bằng `getBitmap`) ghép đúng vị trí và đúng thứ
 *   tự trên / dưới; các view kính bị bỏ qua;
 * - **chỉ video / ảnh** ([GlassBackdropMode.MEDIA], nhẹ hơn): TextureView có hình và ImageView phủ ít nhất nửa cửa sổ.
 * Có một view kính xin chế độ toàn bộ là cả gốc nền chụp toàn bộ.
 *
 * Chỉ làm đúng phần cần: mỗi lượt chụp chỉ vẽ lại và làm mờ các vùng nằm sau những view kính đang hiện (cộng một vành đệm), nên vài nút
 * nhỏ trên màn hình tốn rất ít. Làm mờ bằng [BLUR_PASSES] lượt mờ hộp chồng nhau — gần với mờ Gauss, mịn, không lộ ô vuông — với độ mờ
 * riêng của từng view ([GlassBlur.blurRadius]).
 *
 * Cập nhật theo lượt vẽ: gọi từ `onPreDraw` của các view kính, tối đa một lần mỗi [MIN_INTERVAL_MS] (theo kịp từng khung hình ở 60 Hz nên
 * nền mờ không trễ sau nội dung đang cuộn); view kính vừa hiện / vừa đổi chỗ thì chụp ngay. Màn hình đứng yên thì không chụp. View kính ở
 * cửa sổ khác với gốc nền (hộp thoại) chỉ chụp lại khi màn phía sau thật sự vẽ lại, không chụp theo hiệu ứng của hộp thoại.
 */
class GlassBackdrop private constructor() {

    /** Ảnh nền đã làm mờ, cỡ = gốc nền / [SCALE]; chỉ các vùng sau view kính có nội dung. `null` khi chưa chụp được. */
    var bitmap: Bitmap? = null
        private set

    /** Lượt chụp gần nhất có thấy video / ảnh nền nào không (không thì view kính chỉ vẽ lớp màu). */
    var hasContent = false
        private set

    /** Tăng sau mỗi lượt chụp xong: view kính biết ảnh nền vừa đổi nội dung để lấy mẫu lại. */
    var generation = 0
        private set

    /** Vùng nền của một view kính ở lượt chụp gần nhất. */
    private class Region {
        /** Vùng view cần, điểm ảnh của gốc nền, và độ mờ (độ lệch chuẩn, điểm ảnh màn hình). */
        val bounds = RectF()
        var sigma = 0f

        /** View nằm ở cửa sổ khác với gốc nền (hộp thoại). */
        var otherWindow = false

        /** Trong ảnh nền: vùng dùng thật, vùng chụp (có thêm vành đệm làm mờ) và bán kính mờ hộp. */
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        var outerLeft = 0
        var outerTop = 0
        var outerRight = 0
        var outerBottom = 0
        var radius = 0
        var active = false
    }

    private val regions = WeakHashMap<GlassBlur, Region>()
    private val probe = RectF()

    /** Ảnh chụp chưa làm mờ (đích của [canvas]); [bitmap] nhận phần đã làm mờ. */
    private var capture: Bitmap? = null
    private var canvas: CaptureCanvas? = null
    private val clipPath = Path()

    /** Gốc nền đã vẽ lại từ lượt chụp trước chưa — dùng cho view kính ở cửa sổ khác (đặt bởi [rootListener]). */
    private var rootDirty = true
    private var observed: ViewTreeObserver? = null
    private val rootListener = ViewTreeObserver.OnPreDrawListener {
        rootDirty = true
        // Màn phía sau vừa đổi: cho view kính ở cửa sổ khác một lượt vẽ để nó chụp lại nền
        for ((client, region) in regions) if (region.otherWindow) client.view.invalidate()
        true
    }

    /** Đã có view kính xin chụp toàn bộ giao diện ([GlassBackdropMode.ALL]). */
    private var captureAll = false

    /** Các view (ViewGroup và chính TextureView) có chứa TextureView đang có hình: phải tự duyệt con để ghép khung hình video đúng thứ tự. */
    private val videoBranches = HashSet<View>()
    private var textureFrame: Bitmap? = null
    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    private var lastCaptureAt = 0L
    private val sourceMatrix = Matrix()
    private val sourceBounds = RectF()
    private val frameBounds = RectF()
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Gọi từ `onPreDraw` của [view]: chụp lại nền của [root] nếu cần — [view] vừa hiện / đổi chỗ / đổi độ mờ thì chụp ngay, không thì tối
     * đa một lần mỗi [MIN_INTERVAL_MS]. Mỗi lượt chụp làm mới vùng của mọi view kính đang hiện, nên nhiều view cùng đổi chỗ trong một
     * khung hình (hàng nút đang cuộn) vẫn chỉ chụp một lần. [all] = [view] cần chụp toàn bộ giao diện.
     */
    internal fun update(root: View, client: GlassBlur, all: Boolean = false) {
        var force = false
        if (all && !captureAll) {
            captureAll = true
            force = true
        }
        if (!client.backdropRegion(root, probe)) {
            regions.remove(client)
            return
        }
        val sameWindow = client.view.rootView === root.rootView
        if (!sameWindow) observe(root)
        var region = regions[client]
        if (region == null) {
            region = Region()
            regions[client] = region
            force = true
        } else if (region.bounds != probe || region.sigma != client.blurRadius) {
            force = true
        }
        region.otherWindow = !sameWindow
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (!sameWindow && !rootDirty) return
            if (now - lastCaptureAt < MIN_INTERVAL_MS) return
        }
        val width = root.width / SCALE
        val height = root.height / SCALE
        if (width <= 0 || height <= 0) return
        lastCaptureAt = now
        rootDirty = false

        var raw = capture
        var target = bitmap
        if (raw == null || target == null || raw.width != width || raw.height != height) {
            raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            target.eraseColor(Color.BLACK)
            capture = raw
            bitmap = target
            canvas = CaptureCanvas(raw).apply {
                // Vẽ thu nhỏ 1/SCALE: lọc song tuyến tính mọi ảnh và khử răng cưa mọi nét để nền không lấm tấm, không nhấp nháy khi trượt
                drawFilter = PaintFlagsDrawFilter(0, Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            }
        }
        val canvas = canvas ?: return

        // Vùng hiện tại của mọi view kính đang hiện → phần ảnh nền phải chụp
        clipPath.reset()
        var any = false
        val iterator = regions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val glass = entry.key
            val item = entry.value
            if (glass == null || !glass.backdropRegion(root, probe)) {
                iterator.remove()
                continue
            }
            item.bounds.set(probe)
            item.sigma = glass.blurRadius
            item.radius = boxRadius(item.sigma / SCALE)
            // Vành đệm: đủ cho các lượt mờ lấy tới, thêm 1 điểm ảnh cho lọc song tuyến tính khi phóng ảnh lên
            val margin = item.radius * BLUR_PASSES + 2
            item.left = (floor(probe.left / SCALE).toInt() - 1).coerceIn(0, width)
            item.top = (floor(probe.top / SCALE).toInt() - 1).coerceIn(0, height)
            item.right = (ceil(probe.right / SCALE).toInt() + 1).coerceIn(0, width)
            item.bottom = (ceil(probe.bottom / SCALE).toInt() + 1).coerceIn(0, height)
            item.active = item.right > item.left && item.bottom > item.top
            if (!item.active) continue
            item.outerLeft = max(0, item.left - margin)
            item.outerTop = max(0, item.top - margin)
            item.outerRight = min(width, item.right + margin)
            item.outerBottom = min(height, item.bottom + margin)
            clipPath.addRect(
                item.outerLeft.toFloat(), item.outerTop.toFloat(), item.outerRight.toFloat(), item.outerBottom.toFloat(), Path.Direction.CW
            )
            any = true
        }
        if (!any) return

        // Chỉ vẽ lại phần nằm trong các vùng đó
        val save = canvas.save()
        canvas.clipPath(clipPath)
        // Phần không có nội dung (viền đen quanh video) là nền đen
        canvas.drawColor(Color.BLACK)
        hasContent = false
        if (captureAll) {
            videoBranches.clear()
            collectVideoBranches(root)
            canvas.scale(1f / SCALE, 1f / SCALE)
            drawTree(root, null, canvas)
            videoBranches.clear()
            hasContent = true
        } else {
            drawSources(root, root, canvas)
        }
        canvas.restoreToCount(save)
        if (!hasContent) return

        // Làm mờ từng vùng từ ảnh chụp gốc (vùng chồng nhau vẫn ra cùng kết quả), chỉ ghi phần dùng thật vào ảnh nền
        for (item in regions.values) {
            if (!item.active) continue
            val outerWidth = item.outerRight - item.outerLeft
            val outerHeight = item.outerBottom - item.outerTop
            val size = outerWidth * outerHeight
            if (pixels.size < size) {
                pixels = IntArray(size)
                scratch = IntArray(size)
            }
            raw.getPixels(pixels, 0, outerWidth, item.outerLeft, item.outerTop, outerWidth, outerHeight)
            if (item.radius > 0) {
                repeat(BLUR_PASSES) {
                    boxBlurHorizontal(pixels, scratch, outerWidth, outerHeight, item.radius)
                    boxBlurVertical(scratch, pixels, outerWidth, outerHeight, item.radius)
                }
            }
            val offset = (item.top - item.outerTop) * outerWidth + (item.left - item.outerLeft)
            target.setPixels(pixels, offset, outerWidth, item.left, item.top, item.right - item.left, item.bottom - item.top)
        }
        generation++
    }

    /** [client] không dùng nền này nữa (rời cửa sổ). */
    internal fun release(client: GlassBlur) {
        regions.remove(client)
    }

    /**
     * Vẽ nét (không thu nhỏ, không làm mờ) mọi thứ đang hiện trong cây view của [root] vào [canvas] — canvas đã được dịch / thu phóng sẵn
     * về toạ độ của [root] và chỉ phần nằm trong ảnh đích mới được vẽ. Dùng cho [LiquidLayout] chế độ thấu kính ([LiquidGlassMode.LENS]):
     * giao diện thường vẽ lại như lúc chụp nền mờ, khung hình video ghép đúng vị trí; các view kính tự bỏ qua mình.
     */
    internal fun drawSharp(root: View, canvas: CaptureCanvas) {
        videoBranches.clear()
        collectVideoBranches(root)
        drawTree(root, null, canvas)
        videoBranches.clear()
    }

    /**
     * Làm mờ cả [bitmap] tại chỗ, mờ kiểu Gauss độ lệch chuẩn [sigma] (điểm ảnh của bitmap) bằng [BLUR_PASSES] lượt mờ hộp như ảnh nền
     * dùng chung. Dùng cho thấu kính ([LiquidLens]) có đặt độ mờ. [sigma] ≤ 0 thì không làm gì.
     */
    internal fun blur(bitmap: Bitmap, sigma: Float) {
        val radius = boxRadius(sigma)
        if (radius <= 0) return
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height
        if (pixels.size < size) {
            pixels = IntArray(size)
            scratch = IntArray(size)
        }
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(BLUR_PASSES) {
            boxBlurHorizontal(pixels, scratch, width, height, radius)
            boxBlurVertical(scratch, pixels, width, height, radius)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** Theo dõi lượt vẽ của [root] (ở cửa sổ khác với view kính) để biết khi nào màn phía sau đổi. */
    private fun observe(root: View) {
        val observer = root.viewTreeObserver
        if (observer === observed && observer.isAlive) return
        observed?.takeIf { it.isAlive }?.removeOnPreDrawListener(rootListener)
        observer.addOnPreDrawListener(rootListener)
        observed = observer
        rootDirty = true
    }

    /**
     * Bán kính mờ hộp (điểm ảnh của ảnh nền) để [BLUR_PASSES] lượt chồng nhau cho ra độ lệch chuẩn gần [sigma]: phương sai một lượt hộp
     * bán kính r là ((2r + 1)² − 1) / 12, các lượt cộng dồn. 0 = không làm mờ thêm.
     */
    private fun boxRadius(sigma: Float): Int {
        if (sigma <= 0f) return 0
        val side = sqrt(12f * sigma * sigma / BLUR_PASSES + 1f)
        return ((side - 1f) / 2f).roundToInt().coerceIn(1, MAX_BOX_RADIUS)
    }

    /** [view] là view kính có lấy nền (mờ hoặc thấu kính): không chụp nó và content của nó vào nền. */
    @Suppress("DEPRECATION")
    private fun isGlass(view: View) = view is GlassLayout || (view is LiquidLayout && view.capturesBackdrop)

    /** [view] có đang hiện không (ẩn, trong suốt hẳn hoặc chưa có kích thước thì bỏ qua cả nhánh). */
    private fun isDrawable(view: View) =
        view.visibility == View.VISIBLE && view.alpha > 0f && view.width > 0 && view.height > 0

    /** Ghi vào [videoBranches] mọi view trên đường từ [view] xuống các TextureView đang có hình; trả về nhánh này có video không. */
    private fun collectVideoBranches(view: View): Boolean {
        if (!isDrawable(view) || isGlass(view)) return false
        var hasVideo = false
        if (view is TextureView) {
            hasVideo = view.isAvailable
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) if (collectVideoBranches(view.getChildAt(i))) hasVideo = true
        }
        if (hasVideo) videoBranches.add(view)
        return hasVideo
    }

    /**
     * Vẽ [view] (con của [parent], `null` = gốc nền) vào [canvas] đang ở toạ độ của [parent]: dịch tới chỗ của view, áp phép biến đổi và độ
     * mờ của nó, rồi:
     * - TextureView: trải khung hình video lên khung của view;
     * - nhóm có chứa video: vẽ nền của nhóm rồi lần lượt từng con (dưới trước, trên sau) để video nằm đúng giữa các lớp;
     * - còn lại: để view tự vẽ cả nhánh (view kính tự bỏ qua khi gặp [CaptureCanvas]).
     * View nằm ngoài các vùng cần chụp thì bỏ qua.
     */
    private fun drawTree(view: View, parent: View?, canvas: Canvas) {
        if (!isDrawable(view) || isGlass(view)) return
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val save = canvas.save()
        if (parent != null) canvas.translate((view.left - parent.scrollX).toFloat(), (view.top - parent.scrollY).toFloat())
        canvas.concat(view.matrix)
        @Suppress("DEPRECATION")
        if (canvas.quickReject(0f, 0f, width, height, Canvas.EdgeType.BW)) {
            // Nằm ngoài vùng cần chụp (trang bên cạnh của ViewPager, mục đã cuộn khuất, chỗ không có view kính)
            canvas.restoreToCount(save)
            return
        }
        if (view.alpha < 1f) canvas.saveLayerAlpha(0f, 0f, width, height, (view.alpha * 255).toInt())
        when {
            view is TextureView -> drawFrame(view, canvas)
            view is ViewGroup && view in videoBranches -> {
                view.background?.draw(canvas)
                for (i in 0 until view.childCount) drawTree(view.getChildAt(i), view, canvas)
            }
            else -> try {
                view.draw(canvas)
            } catch (_: RuntimeException) {
                // Có ảnh là bitmap phần cứng: Canvas thường không vẽ được → bỏ qua nhánh này
            }
        }
        canvas.restoreToCount(save)
    }

    /** Đọc khung hình hiện tại của [view] (cỡ 1/[SCALE]) rồi trải lên khung của view trong [canvas] đang ở toạ độ của view. */
    private fun drawFrame(view: TextureView, canvas: Canvas) {
        if (!view.isAvailable) return
        val frame = readFrame(view) ?: return
        frameBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        canvas.drawBitmap(frame, null, frameBounds, framePaint)
    }

    private fun readFrame(view: TextureView): Bitmap? {
        val width = (view.width / SCALE).coerceAtLeast(1)
        val height = (view.height / SCALE).coerceAtLeast(1)
        var frame = textureFrame
        if (frame == null || frame.width != width || frame.height != height) {
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            textureFrame = frame
        }
        return try {
            view.getBitmap(frame)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Vẽ theo thứ tự cây view (dưới trước, trên sau) các nguồn nền đang hiện: TextureView có hình và ImageView phủ ít nhất nửa cửa sổ. */
    private fun drawSources(view: View, root: View, canvas: Canvas) {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return
        when {
            isGlass(view) -> return
            view is TextureView -> drawTexture(view, root, canvas)
            view is ImageView ->
                if (view.width.toLong() * view.height >= root.width.toLong() * root.height / 2) drawImage(view, root, canvas)
            view is ViewGroup -> for (i in 0 until view.childCount) drawSources(view.getChildAt(i), root, canvas)
        }
    }

    /**
     * Đặt [sourceMatrix] = phép biến đổi từ toạ độ của [view] sang toạ độ của [root], gồm cả scale / xoay / dịch chuyển của chính nó và của
     * mọi view cha (video đang bị thu nhỏ, trang đang vuốt…), và [sourceBounds] = khung của view sau biến đổi. Trả về view có phần nào nằm
     * trong cửa sổ không (trang bên cạnh của ViewPager nằm ngoài màn thì bỏ qua).
     */
    private fun locate(view: View, root: View): Boolean {
        sourceMatrix.reset()
        var current: View = view
        while (current !== root) {
            sourceMatrix.postConcat(current.matrix)
            val parent = current.parent as? View ?: break
            sourceMatrix.postTranslate((current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat())
            current = parent
        }
        sourceBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        sourceMatrix.mapRect(sourceBounds)
        return sourceBounds.left < root.width && sourceBounds.top < root.height && sourceBounds.right > 0f && sourceBounds.bottom > 0f
    }

    private fun drawTexture(view: TextureView, root: View, canvas: Canvas) {
        if (!view.isAvailable || !locate(view, root)) return
        val frame = readFrame(view) ?: return
        // Khung hình (đã chụp nhỏ) trải lại đúng khung của view rồi theo phép biến đổi của view xuống ảnh nền
        val save = canvas.save()
        canvas.scale(1f / SCALE, 1f / SCALE)
        canvas.concat(sourceMatrix)
        frameBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        canvas.drawBitmap(frame, null, frameBounds, framePaint)
        canvas.restoreToCount(save)
        hasContent = true
    }

    private fun drawImage(view: ImageView, root: View, canvas: Canvas) {
        if (view.drawable == null || !locate(view, root)) return
        val save = canvas.save()
        canvas.scale(1f / SCALE, 1f / SCALE)
        canvas.concat(sourceMatrix)
        try {
            view.draw(canvas)
            hasContent = true
        } catch (_: RuntimeException) {
            // Ảnh là bitmap phần cứng (Glide): Canvas thường không vẽ được → bỏ qua nguồn này
        }
        canvas.restoreToCount(save)
    }

    /** Làm mờ hộp theo hàng: [src] → [dst], cửa sổ trượt bán kính [radius], mép lặp lại điểm biên. */
    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        val last = width - 1
        for (y in 0 until height) {
            val row = y * width
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val color = src[row + (if (i < 0) 0 else if (i > last) last else i)]
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
            }
            for (x in 0 until width) {
                dst[row + x] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = x - radius
                val into = x + radius + 1
                val leaving = src[row + (if (out < 0) 0 else if (out > last) last else out)]
                val entering = src[row + (if (into > last) last else into)]
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** Làm mờ hộp theo cột: [src] → [dst]. */
    private fun boxBlurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        val last = height - 1
        for (x in 0 until width) {
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val color = src[(if (i < 0) 0 else if (i > last) last else i) * width + x]
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = y - radius
                val into = y + radius + 1
                val leaving = src[(if (out < 0) 0 else if (out > last) last else out) * width + x]
                val entering = src[(if (into > last) last else into) * width + x]
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** Canvas của ảnh nền: view kính nhận ra nó trong `draw` và không vẽ gì, để không lẫn chính mình vào nền. */
    class CaptureCanvas internal constructor(bitmap: Bitmap) : Canvas(bitmap)

    companion object {
        /** Ảnh nền nhỏ hơn gốc nền chừng này lần mỗi chiều. */
        const val SCALE = 4

        /** Chụp lại nhanh nhất mỗi 12 ms: theo từng khung hình ở 60 Hz, cách khung ở 120 Hz. */
        private const val MIN_INTERVAL_MS = 12L

        /** Ba lượt mờ hộp chồng nhau cho ra nền mờ gần với mờ Gauss. */
        private const val BLUR_PASSES = 3

        /** Bán kính mờ hộp lớn nhất (điểm ảnh của ảnh nền) — chặn chi phí khi đặt độ mờ quá lớn. */
        private const val MAX_BOX_RADIUS = 12

        private val backdrops = WeakHashMap<View, GlassBackdrop>()

        /** Ảnh nền dùng chung của các view kính có gốc nền [root]. */
        fun of(root: View): GlassBackdrop = backdrops.getOrPut(root) { GlassBackdrop() }
    }
}
