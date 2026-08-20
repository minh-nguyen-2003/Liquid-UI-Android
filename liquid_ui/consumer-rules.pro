# View được inflate từ XML bằng reflection — giữ constructor (Context, AttributeSet).
-keepclasseswithmembers class vn.minh_nguyen.vkey.liquid.ui.** {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
