# Liquid UI – Android Library

**LiquidLayout** là một `FrameLayout` vẽ sẵn nền kính "Liquid Glass": nền mờ + vệt sáng chạy
theo mép (bevel sáng/tối như kính thật) + quầng sáng mềm. Bọc content vào là có ngay giao diện
liquid — không cần drawable, không cần `Activity`, dùng được cả trong item `RecyclerView`.

Một view, ba chế độ nền (`app:lgv_mode`): `tint` (mặc định, chỉ lớp màu), `blur` (kính mờ thật)
và `lens` (thấu kính bẻ cong thật ở vành mép).

Library id: `vn.minh_nguyen.vkey.liquid.ui`

## Demo

Trái: mặc định. Phải: `highlightAlpha` cao + `lgv_glow=false` (chỉ còn viền nét).

<p align="center">
  <img src="demo_liquid_glass.png" width="45%" style="margin-right:10px;" />
  <img src="demo_liquid_glass_no_glow.png" width="45%" />
</p>

---

## Cài đặt (qua JitPack)

Thêm vào `settings.gradle.kts` (hoặc `build.gradle` project):

```
	dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			google()
			mavenCentral()
			maven { url = uri("https://jitpack.io") }
		}
	}
```

Thêm dependency vào `build.gradle` (module):

```
	dependencies {
	        implementation("com.github.minh-nguyen-2003:Liquid-UI-Android:1.1.0")
	}
```

Nếu JitPack build ra dạng multi-module thì coordinate là
`com.github.minh-nguyen-2003.Liquid-UI-Android:liquid_ui:1.1.0` — xem log build trên JitPack để
lấy đúng dòng.

## Nhớ thay 1.1.0 bằng tag release mới nhé.

## Cách sử dụng cơ bản

Khai trong XML, content đặt bên trong như `FrameLayout` thường:

```xml
<vn.minh_nguyen.vkey.liquid.ui.LiquidLayout
    android:layout_width="44dp"
    android:layout_height="44dp"
    app:lgv_pill="true">

    <ImageView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:padding="11dp"
        android:src="@drawable/ic_menu" />
</vn.minh_nguyen.vkey.liquid.ui.LiquidLayout>
```

Không khai gì thêm thì mặc định đã ra kính: nền đen 20%, bo 12dp, vệt sáng trắng 1dp từ góc
trên-trái, có quầng sáng.

## Thuộc tính XML

| Attribute | Kiểu | Mặc định | Mô tả |
|-----------------------------|-----------|--------------------|-------------------------------------------------------------------------------------------------|
| `lgv_backgroundColor` | color | `#33000000` | Màu nền kính (đen 20%). |
| `lgv_cornerRadius` | dimension | `12dp` | Bán kính bo; tự clamp về nửa cạnh ngắn. Bị bỏ qua khi bật `lgv_pill`. |
| `lgv_pill` | boolean | `false` | `true`: bán kính = nửa cạnh ngắn ⇒ view vuông ra **hình tròn**, view ngang ra **pill**. |
| `lgv_highlightColor` | color | `#FFFFFFFF` | Màu vệt sáng. Vệt **tối** luôn là đen (bóng = thiếu sáng) nên không đổi theo attr này. |
| `lgv_highlightWidth` | dimension | `1dp` | Độ dày vệt sáng nét. |
| `lgv_highlightAlpha` | float | `0.55` | Alpha đỉnh của vệt sáng, tự clamp 0..1. Đặt `0` để tắt hẳn viền. |
| `lgv_highlightOrientation` | enum | `top_left_bottom_right` | Hướng nguồn sáng — xem bảng dưới. |
| `lgv_highlight` | boolean | `true` | `false`: tắt hẳn viền sáng lẫn quầng sáng. |
| `lgv_glow` | boolean | `true` | Quầng sáng mềm loang vào trong. Tắt cho item list cuộn nhiều. |
| `lgv_clipContent` | boolean | `true` | Bo góc luôn content bên trong (`clipToOutline`). |
| `lgv_mode` | enum | `tint` | `tint`: chỉ lớp màu + vệt sáng. `blur`: kính mờ — xem "Chế độ blur". `lens`: bẻ cong nền thật ở vành mép — xem "Chế độ lens". |
| `lgv_bevelWidth` | dimension | `12dp` | (lens) Bề rộng vành mép vát tròn, phần bị bẻ cong. Tự clamp về nửa cạnh ngắn. |
| `lgv_refraction` | float | `1` | (lens) Độ bẻ cong: sát mép hiện phần nền nằm sâu vào trong chừng này lần bề rộng vành. `0` = không bẻ. |
| `lgv_lensZoom` | float | `1` | (lens) Phóng to nội dung phía sau quanh tâm như kính lúp. `1` = không phóng. |
| `lgv_lensBlur` | dimension | `0dp` | (lens) Độ mờ của nội dung nhìn qua kính (mờ Gauss, như `blur()` của CSS). `0` = nét. |

## Enum `lgv_highlightOrientation`

Giá trị khớp hằng số trong `LiquidGlassOrientation` nên đổi bằng XML hay bằng code đều như nhau.

| Enum XML | Hằng số Kotlin | Nguồn sáng đặt ở |
|--------------------------|--------------------------------------------------|-------------------|
| `left_right` | `LiquidGlassOrientation.LEFT_RIGHT` | Cạnh trái |
| `top_bottom` | `LiquidGlassOrientation.TOP_BOTTOM` | Cạnh trên |
| `top_left_bottom_right` | `LiquidGlassOrientation.TOP_LEFT_BOTTOM_RIGHT` | Góc trên-trái (mặc định) |
| `top_right_bottom_left` | `LiquidGlassOrientation.TOP_RIGHT_BOTTOM_LEFT` | Góc trên-phải |

## API trong code

Mỗi attribute đều có một property tương ứng, set xong tự `invalidate()`:

| Property | Kiểu | Ghi chú |
|------------------------|---------|-------------------------------------------------------------------------|
| `glassColor` | `Int` | Không đặt tên `backgroundColor` vì trùng `View.setBackgroundColor`. |
| `cornerRadius` | `Float` | Đơn vị **px** (dùng `resources.getDimension(...)` để đổi từ dp). |
| `isPill` | `Boolean` | Bật thì `cornerRadius` bị bỏ qua. |
| `highlightColor` | `Int` | |
| `highlightWidth` | `Float` | px. |
| `highlightAlpha` | `Float` | Tự clamp 0..1. |
| `highlightOrientation` | `Int` | Nhận hằng số `LiquidGlassOrientation`. |
| `isHighlightEnabled` | `Boolean` | `false` = không vẽ viền sáng lẫn quầng sáng. |
| `isGlowEnabled` | `Boolean` | |
| `isClipContent` | `Boolean` | |
| `mode` | `Int` | Nhận hằng số `LiquidGlassMode` (`TINT` / `BLUR` / `LENS`). |
| `bevelWidth` | `Float` | px. Chỉ dùng ở chế độ lens. |
| `refraction` | `Float` | Chỉ dùng ở chế độ lens. |
| `lensZoom` | `Float` | Chỉ dùng ở chế độ lens; nhỏ nhất 1. |
| `lensBlur` | `Float` | px. Chỉ dùng ở chế độ lens; 0 = nét. |
| `backdropRoot` | `View?` | (lens) Gốc nội dung nhìn qua kính khi view nằm ở cửa sổ khác (hộp thoại, bottom sheet). |

```kotlin
  glassView.highlightAlpha = 0.8f
  glassView.highlightOrientation = LiquidGlassOrientation.TOP_BOTTOM
  glassView.isGlowEnabled = false
```

Hoặc đổi nhiều thứ trong một lần — tham số nào bỏ trống thì giữ nguyên giá trị hiện tại:

```kotlin
  glassView.applyGlass(
      cornerRadius = resources.getDimension(R.dimen.card_radius),
      highlightAlpha = 0.7f,
      glow = false
  )
```

Tạo hoàn toàn bằng code cũng được (dùng như `FrameLayout`):

```kotlin
  val glass = LiquidLayout(context).apply {
      isPill = true
      addView(icon)
  }
```

## Chế độ lens — bẻ cong nền thật

Mặc định (`tint`) `LiquidLayout` không đụng tới nội dung phía sau. Bật `lgv_mode="lens"` thì nó
thành một tấm kính thật: **mặt kính phẳng, vành mép vát tròn**. Nội dung phía sau hiện nét nguyên
ở giữa; riêng vành mép rộng `lgv_bevelWidth` bị khúc xạ theo đúng hình kính (tròn, pill, chữ nhật
bo góc) — chữ, ảnh sát mép bị ép lại và lật ngược như nhìn qua mép kính dày. Mặc định không làm mờ.

```xml
<vn.minh_nguyen.vkey.liquid.ui.LiquidLayout
    android:layout_width="56dp"
    android:layout_height="56dp"
    app:lgv_backgroundColor="@android:color/transparent"
    app:lgv_mode="lens"
    app:lgv_pill="true" />

<!-- Kính lúp trơn: không màu phủ, không viền; vành dày hơn, bẻ mạnh hơn, phóng 1,4 lần -->
<vn.minh_nguyen.vkey.liquid.ui.LiquidLayout
    android:layout_width="96dp"
    android:layout_height="96dp"
    app:lgv_backgroundColor="@android:color/transparent"
    app:lgv_highlight="false"
    app:lgv_bevelWidth="16dp"
    app:lgv_lensZoom="1.4"
    app:lgv_mode="lens"
    app:lgv_pill="true"
    app:lgv_refraction="1.3" />
```

- Nền là mọi thứ đang hiện trong cửa sổ nằm sau view: giao diện thường lẫn video vẽ bằng
  `TextureView` (khung hình video lấy ở 1/4 độ phân giải nên mềm hơn chữ). Ảnh là bitmap phần cứng
  không vẽ lại được (Glide: tải với `disallowHardwareConfig()`).
- Nền chụp lại trước mỗi lượt vẽ của cửa sổ (tối đa một lần mỗi 12 ms), nên cuộn nội dung bên dưới
  hay kéo view đi thì hình trong kính chạy theo. Màn hình đứng yên thì không tốn gì.
- Tốn hơn `tint`: mỗi lượt phải vẽ lại phần giao diện nằm sau view ở độ phân giải thật. Hợp với
  nút, thanh nổi, kính lúp; đừng dùng cho hàng chục item trong list. View lớn hơn chừng 600 nghìn
  điểm ảnh thì hình trong kính tự thu nhỏ độ phân giải (hơi mềm đi).
- Lớp màu `lgv_backgroundColor` vẫn phủ lên trên hình khúc xạ; muốn kính trong hẳn thì đặt trong
  suốt. Vệt sáng, quầng sáng vẽ như chế độ `tint`.
- Muốn nền qua kính mờ đi (kính mờ nhưng mép vẫn bẻ cong kiểu lens): đặt `app:lgv_lensBlur`, ví dụ
  `4dp`. Càng mờ thì ảnh chụp càng được thu nhỏ nên không nặng thêm.
- Kính trơn, chỉ còn phần bẻ cong (không màu phủ, không viền): đặt
  `app:lgv_backgroundColor="@android:color/transparent"` và `app:lgv_highlight="false"`.
- Content đặt bên trong view không lẫn vào hình nhìn qua kính.

### Cùng chế độ lens trên GlassLayout / GlassLensLayout / LiquidGlassLayout

Ba view kính cũng có đúng chế độ này (cùng một phần mã bẻ cong với `LiquidLayout`), bật bằng
`app:gl_mode="lens"`. Khi đó view **không làm mờ** nữa: nền nét ở giữa, bẻ cong ở vành mép; lớp
màu và phần vẽ thêm của từng view (viền sáng, ánh trượt, lún khi bấm của `LiquidGlassLayout`) vẫn
giữ nguyên.

```xml
<vn.minh_nguyen.vkey.liquid.ui.LiquidGlassLayout
    android:layout_width="56dp"
    android:layout_height="56dp"
    app:gl_mode="lens"
    app:gl_bevelWidth="12dp"
    app:gl_lensRefraction="1"
    app:gl_lensZoom="1" />
```

| Attribute | Mặc định | Tương ứng của `LiquidLayout` |
|---|---|---|
| `gl_mode` (`blur` / `lens`) | `blur` | `lgv_mode` |
| `gl_bevelWidth` | `12dp` | `lgv_bevelWidth` |
| `gl_lensRefraction` | `1` | `lgv_refraction` |
| `gl_lensZoom` | `1` | `lgv_lensZoom` |
| `gl_lensBlur` | `0dp` | `lgv_lensBlur` |

Trong code: `mode = GlassMode.LENS`, `bevelWidth`, `lensRefraction`, `lensZoom`, `lensBlur`. Ở chế
độ này `gl_blur`, `gl_blurRadius`, `gl_backdrop`, `gll_refraction` / `lgl_refraction` không dùng tới;
độ mờ của nền qua thấu kính chỉnh bằng `gl_lensBlur`.

## Chế độ blur — kính mờ

`app:lgv_mode="blur"` biến `LiquidLayout` thành kính mờ kiểu iOS: làm mờ thật mọi thứ phía sau
(giao diện thường lẫn video vẽ bằng `TextureView`), rồi phủ lớp màu và vệt sáng lên trên.

```xml
<vn.minh_nguyen.vkey.liquid.ui.LiquidLayout
    android:layout_width="56dp"
    android:layout_height="56dp"
    android:clickable="true"
    app:lgv_mode="blur"
    app:lgv_pill="true"
    app:lgv_pressScale="0.92" />
```

| Attribute | Kiểu | Mặc định | Mô tả |
|---|---|---|---|
| `lgv_blurRadius` | dimension | `4dp` | Độ mờ của nền (mờ Gauss, như `blur()` của CSS). |
| `lgv_backdrop` | enum | `all` | `all`: làm mờ mọi thứ phía sau. `media`: chỉ video và ảnh phủ màn, nhẹ hơn. |
| `lgv_edgeRefraction` | float | `0` | Bẻ cong nền mờ ở mép, kéo cả vành nền ngoài view vào. `0` = nền mờ phẳng. |
| `lgv_blurSaturation` | float | `1` | Độ đậm màu của nền mờ; kính kiểu iOS dùng khoảng `1.4`. |
| `lgv_lensZoom` | float | `1` | Phóng nền quanh tâm (dùng chung với chế độ lens). |
| `lgv_pressScale` | float | `1` | View bấm được thì lún còn chừng này khi giữ (mọi chế độ). `1` = không lún. |

Trong code: `mode = LiquidGlassMode.BLUR`, `blurRadius`, `backdropMode`, `edgeRefraction`,
`blurSaturation`, `pressScale`, `backdropRoot`.

- Nền lấy từ một ảnh mờ dùng chung cho mọi view cùng cửa sổ, chụp thu nhỏ 1/4 và chỉ chụp các vùng
  nằm sau view kính, nên nhẹ: đặt được nhiều view cùng lúc. Phần "Nền được lấy từ đâu" và "Chi phí"
  ở mục view cũ bên dưới vẫn đúng cho chế độ này.
- View nằm trong hộp thoại / bottom sheet thì đặt `backdropRoot` là nội dung của màn phía sau.
- Khác chế độ lens có `lgv_lensBlur`: blur dùng ảnh nền chung (nhẹ, mép có thể lấy cả nền ngoài
  view); lens chụp riêng từng view ở độ phân giải thật (nét hơn, mép kiểu kính dày, tốn hơn).

## Chọn chế độ nào

Giờ chỉ cần một view `LiquidLayout`, chọn nền bằng `lgv_mode`:

| `lgv_mode` | Nền phía sau | Bẻ cong | Chi phí | Hợp với |
|---|---|---|---|---|
| `tint` (mặc định) | không đụng tới | không | gần như không | mọi nơi, kể cả item list |
| `blur` | mờ | tuỳ chọn, ở mép (`lgv_edgeRefraction`) | thấp | nút / thanh nổi trên nội dung, video |
| `lens` | nét (hoặc mờ với `lgv_lensBlur`) | có, ở vành mép | cao hơn | nút, thanh nổi, kính lúp |

Viền sáng bật tắt bằng `lgv_highlight` ở cả ba chế độ.

### Chuyển từ view cũ

`GlassLayout`, `GlassLensLayout`, `LiquidGlassLayout` vẫn chạy như trước nhưng đã đánh dấu
deprecated; phần nền của chúng giờ dùng chung mã với `LiquidLayout`. Đổi sang `LiquidLayout`:

| View cũ | `LiquidLayout` tương đương |
|---|---|
| `GlassLayout` | `lgv_mode="blur"` `lgv_highlight="false"` |
| `GlassLensLayout` | như trên + `lgv_edgeRefraction="0.8"` `lgv_lensZoom="1.12"` `lgv_blurSaturation="1.4"` |
| `LiquidGlassLayout` | `lgv_mode="blur"` + ba thuộc tính trên + `lgv_pressScale="0.92"` (viền là vệt sáng của `LiquidLayout`) |
| view cũ + `gl_mode="lens"` | `lgv_mode="lens"` |

Tên thuộc tính đổi theo: `gl_color` / `lgl_color` → `lgv_backgroundColor`; `gl_cornerRadius` →
`lgv_cornerRadius` (view cũ không khai là bo tròn hai đầu → thêm `lgv_pill="true"`);
`gl_blurRadius` → `lgv_blurRadius`; `gl_backdrop` → `lgv_backdrop`; `gll_refraction` /
`lgl_refraction` → `lgv_edgeRefraction`; `lgl_pressScale` → `lgv_pressScale`.

Khác biệt cần biết: màu nền mặc định của `LiquidLayout` là đen 20 % (view cũ là `#171717` 30 %), và
viền của `LiquidLayout` là vệt sáng theo pháp tuyến mép, dáng hơi khác viền + ánh trượt của
`LiquidGlassLayout`.

## (Cũ) GlassLayout / GlassLensLayout — kính thuần, không liquid

Chỉ cần lớp kính mờ (thanh điều khiển, nhãn, khung chữ nổi trên video) mà không muốn viền sáng:

```xml
<!-- Chỉ làm mờ -->
<vn.minh_nguyen.vkey.liquid.ui.GlassLayout
    android:layout_width="match_parent"
    android:layout_height="56dp"
    app:gl_cornerRadius="16dp" />

<!-- Làm mờ + bẻ cong nền ở mép như thấu kính -->
<vn.minh_nguyen.vkey.liquid.ui.GlassLensLayout
    android:layout_width="40dp"
    android:layout_height="40dp"
    app:gll_refraction="0.8" />
```

| Attribute | View | Kiểu | Mặc định | Mô tả |
|-------------------|--------------------|-----------|---------------|-------------------------------------------------------------------------|
| `gl_color` | cả hai | color | `#4D171717` | Màu phủ trên nền đã làm mờ, kèm độ mờ. |
| `gl_cornerRadius` | cả hai | dimension | bo tròn hai đầu | Bán kính bo; không khai thì tròn / pill. |
| `gl_blur` | cả hai | boolean | `true` | Làm mờ nền phía sau. Tắt thì chỉ còn lớp màu. |
| `gl_blurRadius` | cả hai | dimension | `4dp` | Độ mờ của nền: độ lệch chuẩn của mờ Gauss (như `blur()` của CSS), càng lớn càng nhoè. Dùng được cả trên `LiquidGlassLayout`. |
| `gl_backdrop` | cả hai | enum | `all` | `all`: làm mờ mọi thứ phía sau (giao diện + video). `media`: chỉ video và ảnh phủ màn, nhẹ hơn. |
| `gll_refraction` | `GlassLensLayout` | float | `0.8` | Độ bẻ cong nền ở mép; `0` = tắt. |

`GlassLayout` giữ nguyên màu và cỡ của nền (chỉ mờ đi). `GlassLensLayout` phóng nền 12 % quanh tâm
và đậm màu hơn 40 % cho ra cảm giác thấu kính. Property trong code: `glassColor`, `cornerRadius`,
`blurEnabled`, `refraction`.

## (Cũ) LiquidGlassLayout — kính có blur nền thật + khúc xạ

`LiquidLayout` ở trên không blur. Khi cần nút kính đầy đủ — **làm mờ thật nội dung phía sau**, bẻ
cong ở mép và có viền sáng liquid (kiểu iOS), ví dụ nút nổi trên trình phát video — dùng
`LiquidGlassLayout`:

```xml
<vn.minh_nguyen.vkey.liquid.ui.LiquidGlassLayout
    android:layout_width="40dp"
    android:layout_height="40dp">

    <ImageView
        android:layout_width="16dp"
        android:layout_height="16dp"
        android:layout_gravity="center"
        android:src="@drawable/ic_star" />
</vn.minh_nguyen.vkey.liquid.ui.LiquidGlassLayout>
```

Lớp kính gồm, từ dưới lên: nền phía sau đã làm mờ (đậm màu hơn 40 %, bị **bẻ cong ở mép** như
thấu kính) → nền màu → ánh trượt trên mặt kính → viền sáng theo hướng đèn. Mặc định bo tròn hai
đầu (view vuông ra hình tròn), content bên trong được cắt theo hình kính.

| Attribute | Kiểu | Mặc định | Mô tả |
|--------------------|-----------|---------------|-----------------------------------------------------------------------------------------|
| `lgl_color` | color | `#4D171717` | Màu nền kính kèm độ mờ. |
| `lgl_cornerRadius` | dimension | bo tròn hai đầu | Bán kính bo; không khai thì tròn / pill. |
| `lgl_rimWidth` | dimension | `1dp` | Độ dày viền sáng. |
| `lgl_rimColor` | color | trắng | Màu viền sáng và ánh trượt. |
| `lgl_rimAlpha` | float | `0.55` | Độ sáng lớn nhất của viền ở phía nguồn sáng. |
| `lgl_lightAngle` | float | `225` | Hướng nguồn sáng, độ: 0 = phải, tăng theo chiều kim đồng hồ (225 = trên trái). |
| `lgl_sheenAlpha` | float | `0.16` | Độ sáng ánh trượt trên mặt kính; `0` = tắt. |
| `lgl_pressScale` | float | `0.92` | Tỉ lệ lún khi giữ (chỉ khi view `clickable`); `1` = không lún. |
| `lgl_blur` | boolean | `true` | Làm mờ nền phía sau. Tắt thì chỉ còn nền màu + viền. |
| `lgl_refraction` | float | `0.8` | Độ bẻ cong nền ở mép: mép với tới vành nền ngoài nút bằng chừng này lần bán kính; `0` = tắt. |

Mỗi attribute có property tương ứng trong code: `glassColor`, `cornerRadius`, `rimWidth`,
`rimColor`, `rimAlpha`, `lightAngle`, `sheenAlpha`, `pressScale`, `blurEnabled`, `refraction`.

**Nền được lấy từ đâu.** View tự chụp phần nằm sau nó, không cần nối tay. Thuộc tính `gl_backdrop`
(dùng được cho cả `GlassLayout`, `GlassLensLayout`, `LiquidGlassLayout`; trong code là
`backdropMode`) chọn chụp gì:

- `all` (mặc định, giống kính iOS): **mọi thứ đang hiện** trong cửa sổ — danh sách, chữ, ảnh, nút —
  và cả video. Giao diện thường được vẽ lại vào ảnh nền; khung hình video đọc riêng rồi ghép đúng
  vị trí, đúng thứ tự trên / dưới. Các view kính không lẫn vào nền của nhau.
- `media`: chỉ video và `ImageView` phủ ít nhất nửa cửa sổ. Nhẹ hơn nhiều vì không vẽ lại cả cây
  view — hợp với nút nổi trên trình phát video khi cần chạy mượt trên máy yếu. Không có nguồn nào
  thì view chỉ còn lớp màu + viền.

Lưu ý chung:

- Video phải vẽ bằng `TextureView` — với Media3 / ExoPlayer đặt `app:surface_type="texture_view"`.
  `SurfaceView` **không** chụp lại được.
- Ảnh là bitmap phần cứng (Glide mặc định từ Android 8) không vẽ lại được vào ảnh nền, nhánh view
  chứa nó bị bỏ qua — tải ảnh với `.disallowHardwareConfig()`.
- Phép thu nhỏ / dịch chuyển / cuộn của nội dung phía sau (video đang pinch, trang đang vuốt) đều
  được tính.
- Có một view xin `all` là cả cửa sổ chụp ở chế độ `all`.

**View kính trong hộp thoại / popup.** Hộp thoại là cửa sổ riêng nên mặc định view chỉ thấy nội
dung của chính hộp thoại. Muốn làm mờ màn phía sau thì chỉ gốc nền:

```kotlin
  glass.backdropRoot = requireActivity().findViewById(android.R.id.content)
```

**Độ mờ.** Nền được làm mờ kiểu Gauss (ba lượt mờ hộp chồng nhau) nên mịn, không lộ ô vuông. Chỉnh
bằng `app:gl_blurRadius` / `blurRadius` trên từng view (mặc định 4dp; kính mờ đặc kiểu iOS khoảng
8–12dp; muốn thấy rõ nền bị bẻ cong thì để nhỏ).

**Chi phí.** Các view kính cùng gốc nền dùng chung một ảnh nền (`GlassBackdrop`) cỡ 1/4, và mỗi lượt
chụp **chỉ vẽ lại + làm mờ đúng phần nằm sau các view kính** — vài nút nhỏ thì rất nhẹ, kính phủ cả
màn mới tốn như chụp cả cửa sổ. Nền cập nhật theo từng khung hình (tối đa một lần mỗi 12 ms), chỉ
khi cửa sổ có lượt vẽ mới: màn hình đứng yên thì không chụp. View kính trong hộp thoại chỉ chụp lại
khi màn phía sau vẽ lại. Chế độ `all` vẽ lại nhánh view nằm trong các vùng đó nên nặng hơn `media`;
thấy giật trên máy yếu thì chuyển các view trên video sang `media`.

**Khúc xạ theo hình kính.** Phần bẻ cong ở mép tính theo đúng hình của view — tròn, pill hay chữ
nhật bo góc (`lgl_cornerRadius` nhỏ cũng được): nền bị kéo cong vuông góc với mép gần nhất, bốn
góc không bị bỏ trống.

## Đổi mặc định cho cả app

Khai lại resource **cùng tên** trong app là ghi đè được default của library (resource của app
luôn thắng resource của thư viện):

```xml
<!-- app/src/main/res/values/liquid_glass_overrides.xml -->
<resources>
    <color name="liquid_glass_background">#40000000</color>
    <color name="liquid_glass_highlight">#FFFFFFFF</color>
    <!-- Dự án dùng sdp thì trỏ thẳng sang sdp cho khớp scale màn hình -->
    <dimen name="liquid_glass_corner_radius">@dimen/_12sdp</dimen>
    <dimen name="liquid_glass_highlight_width">@dimen/_1sdp</dimen>
    <dimen name="liquid_glass_bevel_width">@dimen/_12sdp</dimen>
</resources>
```

## Lưu ý

- Ở chế độ mặc định (`tint`) `LiquidLayout` **không blur nội dung phía sau**: nó chỉ phủ một lớp màu
  mờ + vẽ vệt sáng bằng `Canvas`, nhờ vậy nhẹ và dùng được ở mọi nơi (chế độ `blur` / `lens` thì có
  chụp lại nền). Muốn blur backdrop thật trên video / ảnh thì dùng
  `LiquidGlassLayout` (mục trên); trên giao diện thường thì bọc một `BlurView`
  (`eightbitlab:blurview`) bên ngoài rồi để `LiquidLayout` bên trong nó.
- Quầng sáng loang **vào trong**, không phải `BlurMaskFilter` — vì mask filter không chạy trên
  canvas hardware-accelerated, muốn có phải bật software layer (rất tốn khi cuộn list).
- Vệt sáng **không tự đảo chiều theo RTL**.
- Kính chỉ "ra chất" khi phía sau có màu/ảnh. Đặt trên nền phẳng một màu thì gần như chỉ thấy
  cái viền.

## Ví dụ hoàn chỉnh

```xml
<!-- Thanh search dẹt: vẫn chỉ 1 attr lgv_pill, không cần biết trước chiều cao -->
<vn.minh_nguyen.vkey.liquid.ui.LiquidLayout
    android:layout_width="match_parent"
    android:layout_height="52dp"
    app:lgv_pill="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="18dp"
        android:paddingEnd="18dp">

        <ImageView
            android:layout_width="20dp"
            android:layout_height="20dp"
            android:src="@drawable/ic_search" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:text="Tìm kiếm…"
            android:textColor="#80FFFFFF" />
    </LinearLayout>
</vn.minh_nguyen.vkey.liquid.ui.LiquidLayout>

<!-- Item trong list: tắt glow cho nhẹ -->
<vn.minh_nguyen.vkey.liquid.ui.LiquidLayout
    android:layout_width="match_parent"
    android:layout_height="60dp"
    app:lgv_cornerRadius="18dp"
    app:lgv_glow="false" />
```

## Cấu trúc project

| Module | Vai trò |
|--------------|-------------------------------------------------------------------------------|
| `liquid_ui` | Library — chỉ phụ thuộc `androidx.core:core-ktx`, `minSdk 21`. |
| `app` | App demo: pill / card / item list + 3 control chỉnh thuộc tính lúc chạy. |

Phần tính toán nằm trong `LiquidGlassGeometry` (thuần Kotlin, không import `android.*`) nên
test được thẳng trên JVM:

```bash
./gradlew :liquid_ui:testDebugUnitTest
```

## License

MIT — xem [LICENSE](LICENSE).
