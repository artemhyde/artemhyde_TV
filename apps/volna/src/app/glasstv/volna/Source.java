package app.glasstv.volna;

import java.util.List;
import java.util.Map;

// Откуда «Мозг» берёт подборки. В работе это открытые запросы YouTube Music (Net), на стенде поддельный источник
// с заданными задержками, пустыми ответами и ошибками: тысяча переключений не превращается в тысячу запросов YouTube
interface Source {
    List<Track> radio(String videoId) throws Exception;
    List<Track> songsOf(String artist) throws Exception;
    Map<String, String> genres() throws Exception;
    List<String> genrePlaylists(String params) throws Exception;
    List<Track> playlist(String playlistId) throws Exception;
    List<Track> charts(String country) throws Exception;
    List<String> newAlbums() throws Exception;
    List<Track> album(String browseId) throws Exception;
    Track alternative(Track t) throws Exception;

    Source NET = new Source() {
        @Override public List<Track> radio(String id) throws Exception { return Net.radio(id); }
        @Override public List<Track> songsOf(String a) throws Exception { return Net.songsOf(a); }
        @Override public Map<String, String> genres() throws Exception { return Net.genres(); }
        @Override public List<String> genrePlaylists(String p) throws Exception { return Net.genrePlaylists(p); }
        @Override public List<Track> playlist(String id) throws Exception { return Net.playlist(id); }
        @Override public List<Track> charts(String c) throws Exception { return Net.charts(c); }
        @Override public List<String> newAlbums() throws Exception { return Net.newAlbums(); }
        @Override public List<Track> album(String id) throws Exception { return Net.album(id); }
        @Override public Track alternative(Track t) throws Exception { return Net.alternative(t); }
    };
}
