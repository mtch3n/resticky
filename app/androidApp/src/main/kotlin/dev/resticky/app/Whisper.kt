package dev.resticky.app

object Whisper {
    init {
        System.loadLibrary("whisper_jni")
    }

    @JvmStatic external fun load(path: String): Long
    @JvmStatic external fun free(ctx: Long)
    @JvmStatic external fun decodeOpus(frames: ByteArray): FloatArray?
    @JvmStatic external fun opusOpen(): Long
    @JvmStatic external fun opusReset(dec: Long)
    @JvmStatic external fun opusClose(dec: Long)
    @JvmStatic external fun opusFrame(dec: Long, packet: ByteArray, offset: Int, length: Int, out: FloatArray): Int
    @JvmStatic external fun transcribe(ctx: Long, pcm: FloatArray, threads: Int, prompt: String): Boolean
    @JvmStatic external fun segments(ctx: Long): Int
    @JvmStatic external fun segmentStartMs(ctx: Long, i: Int): Long
    @JvmStatic external fun segmentEndMs(ctx: Long, i: Int): Long
    @JvmStatic external fun segmentText(ctx: Long, i: Int): ByteArray
    @JvmStatic external fun tokens(ctx: Long, i: Int): Int
    @JvmStatic external fun tokenTimes(ctx: Long, i: Int): LongArray
    @JvmStatic external fun tokenText(ctx: Long, i: Int, j: Int): ByteArray
}
