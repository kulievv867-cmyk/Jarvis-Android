package com.devin.jarvis.music;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin facade over NewPipeExtractor that returns a single playable audio
 * stream URL for a free-text search query (e.g. song title + artist).
 *
 * Network-bound — must be called off the main thread.
 */
public class MusicSearch {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static class Result {
        public final String streamUrl;
        public final String title;
        public final String uploader;
        public final long durationMs;
        public final String thumbnailUrl;
        public Result(String streamUrl, String title, String uploader,
                      long durationMs, String thumbnailUrl) {
            this.streamUrl = streamUrl;
            this.title = title;
            this.uploader = uploader;
            this.durationMs = durationMs;
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    public static synchronized void init() {
        if (INITIALIZED.get()) return;
        try {
            NewPipe.init(NewPipeDownloader.get(), Localization.DEFAULT);
            INITIALIZED.set(true);
        } catch (Throwable t) {
            // NewPipe.init throws on re-init; ignore.
            INITIALIZED.set(true);
        }
    }

    public static Result findFirst(String query) throws Exception {
        init();
        StreamingService yt = ServiceList.YouTube;
        // music_songs filter biases the result towards music videos.
        List<String> filters = Collections.singletonList("music_songs");
        SearchInfo info = SearchInfo.getInfo(
                yt,
                yt.getSearchQHFactory().fromQuery(query, filters, ""));
        StreamInfoItem firstStream = null;
        for (InfoItem it : info.getRelatedItems()) {
            if (it instanceof StreamInfoItem) {
                firstStream = (StreamInfoItem) it;
                break;
            }
        }
        if (firstStream == null) return null;
        StreamInfo stream = StreamInfo.getInfo(yt, firstStream.getUrl());
        List<AudioStream> audios = stream.getAudioStreams();
        if (audios == null || audios.isEmpty()) return null;
        // Pick the highest bitrate that's still progressive (no DASH).
        AudioStream chosen = null;
        int bestBitrate = -1;
        for (AudioStream a : audios) {
            if (a.getDeliveryMethod() != null
                    && a.getDeliveryMethod() != org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            int br = a.getAverageBitrate();
            if (br > bestBitrate) {
                bestBitrate = br;
                chosen = a;
            }
        }
        if (chosen == null) chosen = audios.get(0);
        String url = chosen.getContent();
        if (url == null || url.isEmpty()) return null;

        String thumb = "";
        try {
            if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
                thumb = stream.getThumbnails().get(0).getUrl();
            }
        } catch (Throwable ignored) {}

        return new Result(
                url,
                stream.getName(),
                stream.getUploaderName(),
                stream.getDuration() * 1000L,
                thumb);
    }
}
