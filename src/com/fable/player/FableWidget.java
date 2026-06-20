package com.fable.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

/** Виджет на рабочий стол: обложка, название и кнопки управления. */
public class FableWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        PlayerService s = PlayerService.get();
        Track t = s != null ? s.current() : null;
        boolean playing = s != null && s.isPlaying();
        render(ctx, mgr, ids,
                t != null ? t.title : ctx.getString(R.string.app_name),
                t != null ? t.artist : ctx.getString(R.string.nothing_playing),
                s != null ? s.widgetArt() : null, playing);
    }

    /** Перерисовать все экземпляры виджета (вызывается из сервиса). */
    static void refresh(Context ctx, String title, String artist, Bitmap art, boolean playing) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, FableWidget.class));
        if (ids == null || ids.length == 0) return;
        new FableWidget().render(ctx, mgr, ids, title, artist, art, playing);
    }

    private void render(Context ctx, AppWidgetManager mgr, int[] ids,
                        String title, String artist, Bitmap art, boolean playing) {
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
            rv.setTextViewText(R.id.w_title, title);
            rv.setTextViewText(R.id.w_artist, artist);
            rv.setImageViewResource(R.id.w_play,
                    playing ? R.drawable.ic_pause : R.drawable.ic_play);
            // Виджет ограничен по памяти на картинку — уменьшаем обложку,
            // иначе большие обложки роняют приложение (RemoteViews bitmap limit).
            if (art != null) {
                Bitmap small = art;
                try { small = Bitmap.createScaledBitmap(art, 256, 256, true); }
                catch (Exception ignored) { }
                rv.setImageViewBitmap(R.id.w_cover, small);
            } else {
                rv.setImageViewResource(R.id.w_cover, R.drawable.ic_track_placeholder);
            }

            rv.setOnClickPendingIntent(R.id.w_prev, broadcast(ctx, PlayerService.ACT_PREV, 21));
            rv.setOnClickPendingIntent(R.id.w_play, broadcast(ctx, PlayerService.ACT_PLAY, 22));
            rv.setOnClickPendingIntent(R.id.w_next, broadcast(ctx, PlayerService.ACT_NEXT, 23));

            PendingIntent open = PendingIntent.getActivity(ctx, 24,
                    new Intent(ctx, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            rv.setOnClickPendingIntent(R.id.w_cover, open);
            rv.setOnClickPendingIntent(R.id.w_title, open);

            mgr.updateAppWidget(id, rv);
        }
    }

    private PendingIntent broadcast(Context ctx, String action, int req) {
        Intent i = new Intent(action).setPackage(ctx.getPackageName());
        return PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
