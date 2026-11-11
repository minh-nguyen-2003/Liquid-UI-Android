package vn.minh_nguyen.vkey.liquid.ui

/**
 * Phần nội dung phía sau mà [GlassLayout] (và lớp con) làm mờ. Giá trị khớp enum XML `gl_backdrop`.
 */
object GlassBackdropMode {
    /** Mọi thứ đang hiện phía sau: giao diện thường (danh sách, chữ, ảnh…) lẫn video. Giống kính iOS; mặc định. */
    const val ALL = 0

    /**
     * Chỉ video (TextureView) và ảnh phủ ít nhất nửa cửa sổ. Nhẹ hơn nhiều vì không vẽ lại cả cây view — hợp với nút nổi trên trình phát
     * video khi máy yếu.
     */
    const val MEDIA = 1
}
