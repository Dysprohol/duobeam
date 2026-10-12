package com.duobeam.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.widget.TextView;

/** White text with a black outline, readable on any background (same look as the Tizen receiver). */
class OutlineText extends TextView {

    private final float stroke;
    private boolean drawing;

    OutlineText(Context c, float strokePx) {
        super(c);
        stroke = strokePx;
        setTextColor(Color.WHITE);
        // the stroke reaches outside the glyphs, keep it from being clipped
        int pad = (int) Math.ceil(strokePx);
        setPadding(pad, pad, pad, pad);
    }

    @Override
    public void invalidate() {
        if (!drawing) super.invalidate(); // setTextColor() in onDraw would loop otherwise
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int fill = getCurrentTextColor();
        TextPaint p = getPaint();
        drawing = true;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(stroke * 2);
        setTextColor(Color.BLACK);
        super.onDraw(canvas);
        p.setStyle(Paint.Style.FILL);
        setTextColor(fill);
        super.onDraw(canvas);
        drawing = false;
    }
}
