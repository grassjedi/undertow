package io.undertow.protocols.mh_stream;

import java.nio.ByteBuffer;

import io.undertow.util.HttpString;

class AjpUtils {


    static boolean notNull(Boolean attachment) {
        return attachment == null ? false : attachment;
    }

    static int notNull(Integer attachment) {
        return attachment == null ? 0 : attachment;
    }

    static String notNull(String attachment) {
        return attachment == null ? "" : attachment;
    }

    static void putInt(final ByteBuffer buf, int value) {
        buf.put((byte) ((value >> 8) & 0xFF));
        buf.put((byte) (value & 0xFF));
    }

    static void putString(final ByteBuffer buf, String value) {
        final int length = value.length();
        putInt(buf, length);
        for (int i = 0; i < length; ++i) {
            buf.put((byte) value.charAt(i));
        }
        buf.put((byte) 0);
    }

    static void putHttpString(final ByteBuffer buf, HttpString value) {
        final int length = value.length();
        putInt(buf, length);
        value.appendTo(buf);
        buf.put((byte) 0);
    }

}
