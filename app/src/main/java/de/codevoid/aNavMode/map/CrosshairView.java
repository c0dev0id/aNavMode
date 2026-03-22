package de.codevoid.aNavMode.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.content.ContextCompat;

import de.codevoid.aNavMode.R;

/**
 * Fixed crosshair reticle rendered at the exact centre of the screen.
 * Sits above MapView in a FrameLayout; touch events pass through because
 * this view is not clickable/focusable.
 *
 * The geographic coordinate under the crosshair is always:
 *   mapView.getModel().mapViewPosition.getCenter()
 *
 * Ported from aWayToGo/CrosshairView.kt (CC0 drawable, same package author).
 */
public class CrosshairView extends View {

    private static final float CENTER_SIZE_DP = 50f;

    private final float    centerRadius;
    private final Drawable drawable;

    public CrosshairView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        float density = context.getResources().getDisplayMetrics().density;
        centerRadius = CENTER_SIZE_DP * density / 2f;
        drawable = ContextCompat.getDrawable(context, R.drawable.ic_crosshair_center);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (drawable == null) return;
        float cx = getWidth()  / 2f;
        float cy = getHeight() / 2f;
        drawable.setBounds(
                (int)(cx - centerRadius), (int)(cy - centerRadius),
                (int)(cx + centerRadius), (int)(cy + centerRadius)
        );
        drawable.draw(canvas);
    }
}
