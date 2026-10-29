package il.sendeasy.bt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * אייקון מצויר ב-Canvas (בלי גליפים של גופן, שחסרים באנדרואיד ישן). ציור על רשת של 24 יחידות,
 * ממורכז ובגודל יחסי ל-View. הרקע (עיגול צבעוני וטבעת פוקוס) נקבע מבחוץ.
 */
public class IconView extends View {
    static final int SEND = 0, MIC = 1, ATTACH = 2, PAUSE = 3, PLAY = 4, TRASH = 5, CLOSE = 6, STOP = 7, SMILEY = 8;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private int type = SEND;
    private float scale = 0.55f; // חלק מהצד הקטן של ה-View שהאייקון תופס

    public IconView(Context c) { this(c, null); }

    public IconView(Context c, AttributeSet a) {
        super(c, a);
        setFocusable(true);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(2f);
        line.setColor(0xFFFFFFFF);
    }

    void setIcon(int t) {
        if (t != type) { type = t; invalidate(); }
    }

    void setIconColor(int color) {
        fill.setColor(color);
        line.setColor(color);
        invalidate();
    }

    /** גודל ה-View ב-dp (ריבוע). */
    void setSize(int dp) {
        int px = UiKit.dp(getContext(), dp);
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) lp = new android.view.ViewGroup.LayoutParams(px, px);
        lp.width = px;
        lp.height = px;
        setLayoutParams(lp);
    }

    @Override
    protected void onDraw(Canvas c) {
        float side = Math.min(getWidth(), getHeight());
        float s = side * scale / 24f;
        c.save();
        c.translate(getWidth() / 2f - 12f * s, getHeight() / 2f - 12f * s);
        c.scale(s, s);
        switch (type) {
            case SEND:
                path.reset();
                path.moveTo(2f, 21f); path.lineTo(23f, 12f); path.lineTo(2f, 3f);
                path.lineTo(2f, 10f); path.lineTo(17f, 12f); path.lineTo(2f, 14f);
                path.close();
                c.drawPath(path, fill);
                break;
            case MIC:
                rf.set(9f, 2f, 15f, 14f);
                c.drawRoundRect(rf, 3f, 3f, fill);
                line.setStrokeWidth(2f);
                rf.set(5.5f, 5f, 18.5f, 17f);
                c.drawArc(rf, 0f, 180f, false, line);
                c.drawLine(12f, 17f, 12f, 21f, line);
                c.drawLine(8f, 21f, 16f, 21f, line);
                break;
            case ATTACH:
                line.setStrokeWidth(2f);
                path.reset();
                path.moveTo(16f, 6f);
                path.lineTo(16f, 17f);
                rf.set(8f, 13f, 16f, 21f);
                path.arcTo(rf, 0f, 180f, false);
                path.lineTo(8f, 6f);
                rf.set(8f, 2f, 12f, 10f);
                path.arcTo(rf, 180f, 180f, false);
                path.lineTo(12f, 15f);
                c.drawPath(path, line);
                break;
            case PAUSE:
                rf.set(6.5f, 5f, 10.5f, 19f);
                c.drawRoundRect(rf, 1f, 1f, fill);
                rf.set(13.5f, 5f, 17.5f, 19f);
                c.drawRoundRect(rf, 1f, 1f, fill);
                break;
            case PLAY:
                path.reset();
                path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f);
                path.close();
                c.drawPath(path, fill);
                break;
            case TRASH:
                rf.set(9f, 3.5f, 15f, 5.5f);
                c.drawRoundRect(rf, 1f, 1f, fill);
                rf.set(4.5f, 5.5f, 19.5f, 8f);
                c.drawRoundRect(rf, 1f, 1f, fill);
                path.reset();
                path.moveTo(6.5f, 9.5f); path.lineTo(17.5f, 9.5f);
                path.lineTo(16.5f, 20.5f); path.lineTo(7.5f, 20.5f);
                path.close();
                c.drawPath(path, fill);
                break;
            case CLOSE:
                line.setStrokeWidth(2.6f);
                c.drawLine(6f, 6f, 18f, 18f, line);
                c.drawLine(18f, 6f, 6f, 18f, line);
                break;
            case SMILEY:
                line.setStrokeWidth(1.8f);
                c.drawCircle(12f, 12f, 9f, line);
                c.drawCircle(8.8f, 9.8f, 1.3f, fill);
                c.drawCircle(15.2f, 9.8f, 1.3f, fill);
                rf.set(7f, 7.5f, 17f, 17f);
                c.drawArc(rf, 30f, 120f, false, line);
                break;
            case STOP:
                rf.set(6f, 6f, 18f, 18f);
                c.drawRoundRect(rf, 1.5f, 1.5f, fill);
                break;
            default:
                break;
        }
        c.restore();
    }
}
