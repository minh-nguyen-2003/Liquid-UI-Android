package vn.minh_nguyen.vkey.liquid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Ảnh nền mờ dùng chung cho mọi [GlassLayout] (và lớp con) có cùng gốc nền: bản chụp thu nhỏ 1/[SCALE] của những thứ nằm sau các view
 * kính, rồi làm mờ. Mỗi view kính vẽ lại đúng phần ảnh nằm sau nó, phóng to lên nên ra lớp kính mờ kiểu iOS. Hai chế độ chụp:
 * - **toàn bộ** ([GlassBackdropMode.ALL]): mọi thứ đang hiện trong cây view của gốc nền — giao diện thường (danh sách, chữ, ảnh…) vẽ lại
 *   vào Canvas thường, khung hình video (TextureView không tự vẽ vào Canvas thường nên đọc bằng `getBitmap`) ghép đúng vị trí và đúng thứ
 *   tự trên / dưới; các view kính bị bỏ qua;
 * - **chỉ video / ảnh** ([GlassBackdropMode.MEDIA], nhẹ hơn): TextureView có hình và ImageView phủ ít nhất nửa cửa sổ.
 * Có một view kính xin chế độ toàn bộ là cả gốc nền chụp toàn bộ.
 *
 * Chụp tối đa một lần mỗi [MIN_INTERVAL_MS], gọi từ `onPreDraw` của các view kính: màn hình có lượt vẽ mới (video chạy, danh sách cuộn…)
 * thì ảnh nền cập nhật; màn hình đứng yên thì không chụp nữa.
 */
class GlassBackdrop private constructor() {

    /** Ảnh nền đã làm mờ, cỡ = cửa sổ / [SCALE]; `null` khi chưa chụp được. */
    var bitmap: Bitmap? = null
        private set

    /** Lượt chụp gần nhất có thấy video / ảnh nền nào không (không thì nút kính chỉ vẽ nền màu). */
    var hasContent = false
        private set

    private var canvas: CaptureCanvas? = null

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

    /** Chụp lại nền của [root] nếu đã quá [MIN_INTERVAL_MS] từ lượt trước; [all] = view gọi cần chụp toàn bộ giao diện. */
    fun update(root: View, all: Boolean = false) {
        if (all) captureAll = true
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < MIN_INTERVAL_MS) return
        lastCaptureAt = now
        val width = root.width / SCALE
        val height = root.height / SCALE
        if (width <= 0 || height <= 0) return
        var target = bitmap
        if (target == null || target.width != width || target.height != height) {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = target
            canvas = CaptureCanvas(target)
            pixels = IntArray(width * height)
            scratch = IntArray(width * height)
        }
        val canvas = canvas ?: return
        // Phần không có video (viền đen quanh video) là nền đen
        canvas.drawColor(Color.BLACK)
        hasContent = false
        if (captureAll) {
            videoBranches.clear()
            collectVideoBranches(root)
            val save = canvas.save()
            canvas.scale(1f / SCALE, 1f / SCALE)
            drawTree(root, null, canvas)
            canvas.restoreToCount(save)
            videoBranches.clear()
            hasContent = true
        } else {
            drawSources(root, root, canvas)
        }
        if (!hasContent) return
        target.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(BLUR_PASSES) {
            boxBlurHorizontal(pixels, scratch, width, height, BLUR_RADIUS)
            boxBlurVertical(scratch, pixels, width, height, BLUR_RADIUS)
        }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** [view] có đang hiện không (ẩn, trong suốt hẳn hoặc chưa có kích thước thì bỏ qua cả nhánh). */
    private fun isDrawable(view: View) =
        view.visibility == View.VISIBLE && view.alpha > 0f && view.width > 0 && view.height > 0

    /** Ghi vào [videoBranches] mọi view trên đường từ [view] xuống các TextureView đang có hình; trả về nhánh này có video không. */
    private fun collectVideoBranches(view: View): Boolean {
        if (!isDrawable(view) || view is GlassLayout) return false
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
     */
    private fun drawTree(view: View, parent: View?, canvas: Canvas) {
        if (!isDrawable(view) || view is GlassLayout) return
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val save = canvas.save()
        if (parent != null) canvas.translate((view.left - parent.scrollX).toFloat(), (view.top - parent.scrollY).toFloat())
        canvas.concat(view.matrix)
        @Suppress("DEPRECATION")
        if (canvas.quickReject(0f, 0f, width, height, Canvas.EdgeType.BW)) {
            // Nằm ngoài ảnh nền (trang bên cạnh của ViewPager, mục đã cuộn khuất)
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
            view is GlassLayout -> return
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
        for (y in 0 until height) {
            val row = y * width
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val color = src[row + i.coerceIn(0, width - 1)]
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
            }
            for (x in 0 until width) {
                dst[row + x] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = src[row + (x - radius).coerceIn(0, width - 1)]
                val entering = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** Làm mờ hộp theo cột: [src] → [dst]. */
    private fun boxBlurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val color = src[i.coerceIn(0, height - 1) * width + x]
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** Canvas của ảnh nền: view kính nhận ra nó trong `draw` và không vẽ gì, để không lẫn chính mình vào nền. */
    class CaptureCanvas internal constructor(bitmap: Bitmap) : Canvas(bitmap)

    companion object {
        /** Ảnh nền nhỏ hơn cửa sổ chừng này lần mỗi chiều (ảnh càng nhỏ càng mờ và càng nhẹ). */
        const val SCALE = 6

        /** Chụp lại nhanh nhất mỗi 33 ms (≈ 30 lần / giây). */
        private const val MIN_INTERVAL_MS = 33L

        /** Làm mờ hộp bán kính 1 điểm ảnh nhỏ, lặp 2 lượt (mờ nhẹ ≈ 12 px trên màn hình: nền còn đủ rõ để thấy mép kính bẻ cong). */
        private const val BLUR_RADIUS = 1
        private const val BLUR_PASSES = 2

        private val backdrops = WeakHashMap<View, GlassBackdrop>()

        /** Ảnh nền dùng chung của cửa sổ có gốc [root]. */
        fun of(root: View): GlassBackdrop = backdrops.getOrPut(root) { GlassBackdrop() }
    }
}
