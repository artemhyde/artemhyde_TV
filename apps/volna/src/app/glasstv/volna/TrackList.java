package app.glasstv.volna;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// Список треков в плеере: очередь волны или все треки вкуса. Строка: обложка, название, исполнитель, время.
// Играющий трек отмечен полоской цвета обложки, отыгравшие притушены. OK на строке включает трек
final class TrackList extends ListView {
    static final int PAST = 0, NOW = 1, NEXT = 2, LIB = 3;

    static final class Item {
        final Track t;
        final int kind;
        Item(Track t, int kind) { this.t = t; this.kind = kind; }
    }

    interface Pick { void on(Item it); }

    private final float dp;
    private final List<Item> items = new ArrayList<>();
    private int accent = Fonts.ACCENT;
    private final Adapter adapter = new Adapter();

    TrackList(Context c, Pick pick) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setDivider(null);
        setDividerHeight(px(4));
        setVerticalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(px(24));
        setClipToPadding(false);
        setCacheColorHint(0);
        GradientDrawable sel = new GradientDrawable();
        sel.setCornerRadius(px(12));
        sel.setColor(0x24FFFFFF);
        setSelector(sel);
        setDrawSelectorOnTop(false);
        setAdapter(adapter);
        setOnItemClickListener((p, v, pos, id) -> { if (pos < items.size()) pick.on(items.get(pos)); });
    }

    private int px(float v) { return Math.round(v * dp); }

    void set(List<Item> list, int accentColor) {
        items.clear();
        items.addAll(list);
        accent = accentColor;
        adapter.notifyDataSetChanged();
    }

    int indexOfNow() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).kind == NOW) return i;
        return -1;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Row r = convert instanceof Row ? (Row) convert : new Row(getContext());
            r.bind(items.get(pos));
            return r;
        }
    }

    private final class Row extends LinearLayout {
        final View mark;
        final ImageView thumb;
        final TextView title, artist, time;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(px(8), 0, px(12), 0);
            setLayoutParams(new ListView.LayoutParams(-1, px(56)));
            mark = new View(c);
            GradientDrawable m = new GradientDrawable();
            m.setCornerRadius(px(2));
            mark.setBackground(m);
            addView(mark, new LayoutParams(px(3), px(28)));
            thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0x1AFFFFFF);
            thumb.setClipToOutline(true);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 8 * dp); }
            });
            LayoutParams tl = new LayoutParams(px(40), px(40));
            tl.leftMargin = px(7);
            addView(thumb, tl);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setPadding(px(12), 0, px(8), 0);
            title = Fonts.text(c, Fonts.BODY, Fonts.reg18, Fonts.WHITE_92);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            artist = Fonts.text(c, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            artist.setPadding(0, px(4), 0, 0);
            col.addView(title);
            col.addView(artist);
            addView(col, new LayoutParams(0, -2, 1));
            time = Fonts.text(c, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
            addView(time);
        }

        void bind(Item it) {
            Track t = it.t;
            title.setText(t.title);
            artist.setText(it.kind == LIB && !t.badge.isEmpty() ? t.artist + "  ·  " + t.badge : t.artist);
            time.setText(t.durationMs > 0 ? Ui.time(t.durationMs) : "");
            ((GradientDrawable) mark.getBackground()).setColor(it.kind == NOW ? accent : 0x00000000);
            title.setTypeface(it.kind == NOW ? Fonts.med18 : Fonts.reg18);
            title.setTextColor(it.kind == NOW ? Ui.light(accent) : Fonts.WHITE_92);
            setAlpha(it.kind == PAST ? 0.55f : 1f);
            Thumbs.load(thumb, t.id);
        }
    }
}
