package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    /**
     * HaniyaX: background colour (ARGB) used to paint selected text. 0 keeps the upstream behaviour of inverting the
     * selected cells. Set by the host theme.
     */
    public static volatile int sSelectionColor = 0;
    /** HaniyaX: background colour (ARGB) of the scrollback-search highlight. 0 = invert like the selection. */
    public static volatile int sSearchHighlightColor = 0;
    /** HaniyaX: colour painted when no emulator is attached yet (upstream: black). */
    public static volatile int sEmptyBackgroundColor = 0xFF000000;
    /**
     * HaniyaX: how far dim (SGR 2) text is blended towards its background, 0..1. Upstream multiplied the colour by
     * 2/3, which makes dim text darker - i.e. more prominent - on light backgrounds.
     */
    public static volatile float sDimBlend = 0.38f;

    /** Run marks: whether a run is inside the selection or the search highlight. */
    /** HaniyaX: row value meaning "no search highlight" (rows may legitimately be negative). */
    public static final int NO_HIGHLIGHT = Integer.MIN_VALUE;
    private static final int MARK_NONE = 0, MARK_SELECTION = 1, MARK_HIGHLIGHT = 2;

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, NO_HIGHLIGHT, NO_HIGHLIGHT, -1, -1);
    }

    /**
     * HaniyaX: same as above plus a search highlight range (same coordinate semantics as the selection;
     * use {@link #NO_HIGHLIGHT} as the rows to
     * disable; end column is inclusive). The highlight is painted with {@link #sSearchHighlightColor}.
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             int highlightY1, int highlightY2, int highlightX1, int highlightX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }
            int hlx1 = -1, hlx2 = -1;
            if (highlightY1 != NO_HIGHLIGHT && row >= highlightY1 && row <= highlightY2) {
                hlx1 = (row == highlightY1) ? highlightX1 : 0;
                hlx2 = (row == highlightY2) ? highlightX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            int lastRunMark = MARK_NONE;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final long style = lineObject.getStyle(column);
                if (TextStyle.isTerminalBitmap(style)) {
                    Bitmap bitmap = mEmulator.getScreen().getSixelBitmap(style);
                    if (bitmap != null) {
                        float left = column * mFontWidth;
                        float top = heightOffset - mFontLineSpacing;
                        Rect bitmapSrcRect = mEmulator.getScreen().getSixelRect(style);
                        RectF bitmapDestRect = new RectF(left, top, left + mFontWidth, top + mFontLineSpacing);
                        canvas.drawBitmap(bitmap, bitmapSrcRect, bitmapDestRect, null);
                    }
                    column += 1;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunMark = MARK_NONE;
                    lastRunStartColumn = column + 1;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = false;
                    currentCharIndex += charsForCodePoint;
                    continue;
                }
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final boolean insideHighlight = column >= hlx1 && column <= hlx2;
                final int mark = insideSelection ? MARK_SELECTION : (insideHighlight ? MARK_HIGHLIGHT : MARK_NONE);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || mark != lastRunMark || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0 || column == lastRunStartColumn) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || invertsForMark(lastRunMark),
                            markColor(lastRunMark));
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunMark = mark;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || invertsForMark(lastRunMark),
                markColor(lastRunMark));
        }
    }

    /** HaniyaX: selection/highlight runs are inverted only when no explicit colour is configured. */
    private static boolean invertsForMark(int mark) {
        if (mark == MARK_SELECTION) return sSelectionColor == 0;
        if (mark == MARK_HIGHLIGHT) return sSearchHighlightColor == 0;
        return false;
    }

    private static int markColor(int mark) {
        if (mark == MARK_SELECTION) return sSelectionColor;
        if (mark == MARK_HIGHLIGHT) return sSearchHighlightColor;
        return 0;
    }

    private static int blend(int from, int to, float amount) {
        int r = Math.round(((from >> 16) & 0xFF) * (1 - amount) + ((to >> 16) & 0xFF) * amount);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - amount) + ((to >> 8) & 0xFF) * amount);
        int b = Math.round((from & 0xFF) * (1 - amount) + (to & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo, int markBackground) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }
        // HaniyaX: paint selection / search highlight with the theme colour instead of inverting.
        if (markBackground != 0) backColor = markBackground;

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= (((right - left) * 3) / 4.f);
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                // HaniyaX: blend dim text towards its background so it recedes on light and dark themes alike
                // (upstream multiplied by 2/3, which darkens - emphasises - text on light backgrounds).
                foreColor = blend(foreColor, backColor, sDimBlend);
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
            } else {
                canvas.drawText(text, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, mTextPaint);
            }
        }

        if (savedMatrix) canvas.restore();
    }

    /**
     * HaniyaX: draw the IME composition (preedit) at the cursor, underlined on the default background, so composing
     * keyboards show what is being typed before it is committed to the terminal.
     */
    public final void renderPreedit(TerminalEmulator emulator, Canvas canvas, int topRow, String preedit) {
        if (preedit == null || preedit.isEmpty()) return;
        final int row = emulator.getCursorRow() - topRow;
        if (row < 0 || row >= emulator.mRows) return;
        final int[] palette = emulator.mColors.mCurrentColors;
        int columns = 0;
        for (int i = 0; i < preedit.length(); ) {
            int cp = preedit.codePointAt(i);
            columns += Math.max(0, WcWidth.width(cp));
            i += Character.charCount(cp);
        }
        float left = emulator.getCursorCol() * mFontWidth;
        float width = Math.max(1, columns) * mFontWidth;
        // Keep the composition on screen: shift left if it would overflow the right edge.
        float maxRight = emulator.mColumns * mFontWidth;
        if (left + width > maxRight) left = Math.max(0, maxRight - width);
        float bottom = (row + 1) * mFontLineSpacing + mFontLineSpacingAndAscent;
        float top = bottom - mFontLineSpacing;
        mTextPaint.setFakeBoldText(false);
        mTextPaint.setTextSkewX(0.f);
        mTextPaint.setStrikeThruText(false);
        mTextPaint.setUnderlineText(true);
        mTextPaint.setColor(palette[TextStyle.COLOR_INDEX_BACKGROUND]);
        canvas.drawRect(left, top, left + width, bottom, mTextPaint);
        mTextPaint.setColor(palette[TextStyle.COLOR_INDEX_FOREGROUND]);
        float measured = mTextPaint.measureText(preedit);
        boolean scaled = measured > 0 && Math.abs(measured - width) > 0.5f;
        if (scaled) {
            canvas.save();
            canvas.scale(width / measured, 1.f, left, 0);
        }
        canvas.drawText(preedit, left, bottom - mFontLineSpacingAndAscent, mTextPaint);
        if (scaled) canvas.restore();
        mTextPaint.setUnderlineText(false);
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
