package ai.aeiou.edgelab.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 16 kHz mono float samples -> a 16-bit PCM wav file, for sending audio to the gateway. */
public final class WavEncoder {

    private WavEncoder() {}

    public static byte[] encode(float[] samples, int sampleRate) {
        int dataBytes = samples.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'}).putInt(36 + dataBytes).put(new byte[] {'W', 'A', 'V', 'E'});
        b.put(new byte[] {'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1)            // PCM
                .putShort((short) 1)            // mono
                .putInt(sampleRate)
                .putInt(sampleRate * 2)         // byte rate
                .putShort((short) 2)            // block align
                .putShort((short) 16);          // bits per sample
        b.put(new byte[] {'d', 'a', 't', 'a'}).putInt(dataBytes);
        for (float f : samples) {
            float c = Math.max(-1f, Math.min(1f, f));
            b.putShort((short) Math.round(c * 32767));
        }
        return b.array();
    }
}
