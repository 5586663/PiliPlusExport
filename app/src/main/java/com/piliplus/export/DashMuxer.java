package com.piliplus.export;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 用 Android 原生 MediaExtractor + MediaMuxer 合并 dash 的分离音视频。
 *
 * 不依赖 ffmpeg。B站 dash 分片为 fMP4，MediaExtractor 可直接读取。
 *
 * 流程：
 *   1. 分别建 video/audio extractor，读出各自 MediaFormat
 *   2. 建 MediaMuxer，addTrack 两条
 *   3. 依次把两条轨的 sample 原样写入（不重编码，只重封装）
 *
 * 失败常见原因：源分片非标准 fMP4、或视频为 AV1（部分设备解码器不支持封装）。
 * 调用方需处理异常并回退。
 */
public final class DashMuxer {

    private DashMuxer() {}

    private static final long TIMEOUT_US = 10000;

    public static void mux(File videoFile, File audioFile, File outFile) throws Exception {
        MediaExtractor vEx = new MediaExtractor();
        MediaExtractor aEx = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            vEx.setDataSource(videoFile.getAbsolutePath());
            int vTrack = selectTrack(vEx, "video");
            if (vTrack < 0) throw new Exception("视频轨未找到");
            MediaFormat vFmt = vEx.getTrackFormat(vTrack);

            aEx.setDataSource(audioFile.getAbsolutePath());
            int aTrack = selectTrack(aEx, "audio");
            if (aTrack < 0) throw new Exception("音频轨未找到");
            MediaFormat aFmt = aEx.getTrackFormat(aTrack);

            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (outFile.exists()) outFile.delete();

            mux = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = mux.addTrack(vFmt);
            int aOut = mux.addTrack(aFmt);
            mux.start();

            copyTrack(vEx, vTrack, mux, vOut);
            copyTrack(aEx, aTrack, mux, aOut);

            mux.stop();
        } finally {
            try { vEx.release(); } catch (Throwable ignored) {}
            try { aEx.release(); } catch (Throwable ignored) {}
            if (mux != null) {
                try { mux.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static int selectTrack(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                ex.selectTrack(i);
                return i;
            }
        }
        return -1;
    }

    private static void copyTrack(MediaExtractor ex, int track, MediaMuxer mux, int outTrack) {
        ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }
}
