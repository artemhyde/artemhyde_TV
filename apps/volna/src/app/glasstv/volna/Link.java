package app.glasstv.volna;

import android.media.session.MediaController;
import android.os.Bundle;
import android.os.ResultReceiver;

// Связь «Волны» с плеером. В работе это сессия Aura, на стенде поддельный плеер, который не издаёт ни звука
interface Link {
    void play(String mediaId);
    void pause();
    void resume();
    void seekTo(long ms);
    void stopPlayer();
    void custom(String action, Bundle args);
    void command(String action, Bundle args, ResultReceiver rr);

    final class Ctl implements Link {
        final MediaController c;

        Ctl(MediaController c) { this.c = c; }

        @Override public void play(String mediaId) { c.getTransportControls().playFromMediaId(mediaId, null); }
        @Override public void pause() { c.getTransportControls().pause(); }
        @Override public void resume() { c.getTransportControls().play(); }
        @Override public void seekTo(long ms) { c.getTransportControls().seekTo(ms); }
        @Override public void stopPlayer() { c.getTransportControls().stop(); }
        @Override public void custom(String action, Bundle args) { c.getTransportControls().sendCustomAction(action, args); }
        @Override public void command(String action, Bundle args, ResultReceiver rr) { c.sendCommand(action, args, rr); }
    }
}
