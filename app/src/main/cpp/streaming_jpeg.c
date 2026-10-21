#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <setjmp.h>
#include "jpeglib.h"

#define OUTPUT_CAPACITY 65536

typedef struct {
    struct jpeg_compress_struct compressor;
    struct jpeg_error_mgr error;
    struct jpeg_destination_mgr destination;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
    JNIEnv *environment; /* Updated at every JNI entry; never used from another thread. */
    jobject owner;
    jbyteArray java_output;
    jmethodID deliver;
    JSAMPLE *row;
    size_t row_bytes;
    int created;
    int failed;
    JOCTET output[OUTPUT_CAPACITY];
} Encoder;

static void throw_new(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return; /* Preserve the original Java I/O/cancellation error. */
    jclass exception = (*env)->FindClass(env, type);
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
        (*env)->DeleteLocalRef(env, exception);
    }
}

static void codec_error(j_common_ptr info) {
    Encoder *encoder = (Encoder *)info->client_data;
    (*info->err->format_message)(info, encoder->message);
    encoder->failed = 1;
    longjmp(encoder->jump, 1);
}

static void deliver_output(Encoder *encoder, size_t count) {
    if (count == 0) return;
    JNIEnv *env = encoder->environment;
    (*env)->SetByteArrayRegion(env, encoder->java_output, 0, (jsize)count, (jbyte *)encoder->output);
    if (!(*env)->ExceptionCheck(env)) {
        (*env)->CallVoidMethod(env, encoder->owner, encoder->deliver, encoder->java_output, (jint)count);
    }
    if ((*env)->ExceptionCheck(env)) {
        encoder->failed = 1;
        /* No JNI array is pinned and no C++ stack objects cross this longjmp. */
        longjmp(encoder->jump, 1);
    }
}

static void destination_init(j_compress_ptr info) {
    Encoder *encoder = (Encoder *)info->client_data;
    info->dest->next_output_byte = encoder->output;
    info->dest->free_in_buffer = OUTPUT_CAPACITY;
}

static boolean destination_full(j_compress_ptr info) {
    Encoder *encoder = (Encoder *)info->client_data;
    deliver_output(encoder, OUTPUT_CAPACITY);
    info->dest->next_output_byte = encoder->output;
    info->dest->free_in_buffer = OUTPUT_CAPACITY;
    return TRUE;
}

static void destination_finish(j_compress_ptr info) {
    Encoder *encoder = (Encoder *)info->client_data;
    deliver_output(encoder, OUTPUT_CAPACITY - info->dest->free_in_buffer);
}

static void destroy_encoder(JNIEnv *env, Encoder *encoder) {
    if (encoder == NULL) return;
    /* destroy also aborts an unfinished stream; it does not call the destination manager. */
    if (encoder->created) jpeg_destroy_compress(&encoder->compressor);
    if (encoder->java_output != NULL) (*env)->DeleteGlobalRef(env, encoder->java_output);
    if (encoder->owner != NULL) (*env)->DeleteGlobalRef(env, encoder->owner);
    free(encoder->row);
    free(encoder);
}

JNIEXPORT jlong JNICALL
Java_kr_ac_hit_networkprinterstudio_StreamingJpegEncoder_nativeCreate(
    JNIEnv *env, jobject owner, jint width, jint height, jint dpi, jint quality) {
    if (width <= 0 || height <= 0 || width > JPEG_MAX_DIMENSION || height > JPEG_MAX_DIMENSION ||
        dpi <= 0 || dpi > 65535 || quality < 0 || quality > 100) {
        throw_new(env, "java/lang/IllegalArgumentException", "Unsupported JPEG dimensions, DPI or quality");
        return 0;
    }
    Encoder *encoder = (Encoder *)calloc(1, sizeof(Encoder));
    if (encoder == NULL) {
        throw_new(env, "java/lang/OutOfMemoryError", "JPEG encoder allocation failed");
        return 0;
    }
    encoder->environment = env;
    encoder->row_bytes = (size_t)width * 3;
    encoder->row = (JSAMPLE *)malloc(encoder->row_bytes);
    encoder->owner = (*env)->NewGlobalRef(env, owner);
    if (encoder->row == NULL || encoder->owner == NULL) {
        destroy_encoder(env, encoder);
        throw_new(env, "java/lang/OutOfMemoryError", "JPEG row allocation failed");
        return 0;
    }
    jbyteArray bytes = (*env)->NewByteArray(env, OUTPUT_CAPACITY);
    if (bytes != NULL) {
        encoder->java_output = (jbyteArray)(*env)->NewGlobalRef(env, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    if (encoder->java_output == NULL || (*env)->ExceptionCheck(env)) {
        destroy_encoder(env, encoder);
        throw_new(env, "java/lang/OutOfMemoryError", "JPEG destination allocation failed");
        return 0;
    }
    jclass type = (*env)->GetObjectClass(env, owner);
    if (type != NULL && !(*env)->ExceptionCheck(env)) {
        encoder->deliver = (*env)->GetMethodID(env, type, "writeCompressed", "([BI)V");
        (*env)->DeleteLocalRef(env, type);
    }
    if (encoder->java_output == NULL || encoder->deliver == NULL || (*env)->ExceptionCheck(env)) {
        destroy_encoder(env, encoder);
        throw_new(env, "java/lang/OutOfMemoryError", "JPEG destination allocation failed");
        return 0;
    }
    encoder->compressor.err = jpeg_std_error(&encoder->error);
    encoder->error.error_exit = codec_error;
    encoder->compressor.client_data = encoder;
    if (setjmp(encoder->jump)) {
        throw_new(env, "java/io/IOException", encoder->message);
        destroy_encoder(env, encoder);
        return 0;
    }
    /* Mark before create, so an allocation failure during create can still be cleaned up. */
    encoder->created = 1;
    jpeg_create_compress(&encoder->compressor);
    encoder->destination.init_destination = destination_init;
    encoder->destination.empty_output_buffer = destination_full;
    encoder->destination.term_destination = destination_finish;
    encoder->compressor.dest = &encoder->destination;
    encoder->compressor.image_width = (JDIMENSION)width;
    encoder->compressor.image_height = (JDIMENSION)height;
    encoder->compressor.input_components = 3;
    encoder->compressor.in_color_space = JCS_RGB;
    jpeg_set_defaults(&encoder->compressor);
    jpeg_set_quality(&encoder->compressor, quality, TRUE);
    /* Same default 4:2:0 sampling as SkJpegEncoder/Bitmap.compress. Huffman optimization is
       deliberately off: it otherwise retains a full frame of DCT coefficients. */
    encoder->compressor.optimize_coding = FALSE;
    encoder->compressor.arith_code = FALSE;
    encoder->compressor.scan_info = NULL;
    encoder->compressor.num_scans = 0;
    encoder->compressor.density_unit = 1;
    encoder->compressor.X_density = (UINT16)dpi;
    encoder->compressor.Y_density = (UINT16)dpi;
    jpeg_start_compress(&encoder->compressor, TRUE);
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT void JNICALL
Java_kr_ac_hit_networkprinterstudio_StreamingJpegEncoder_nativeWrite(
    JNIEnv *env, jobject owner, jlong handle, jbyteArray bytes, jint row_count) {
    (void)owner;
    Encoder *encoder = (Encoder *)(intptr_t)handle;
    if (encoder == NULL || encoder->failed) {
        throw_new(env, "java/lang/IllegalStateException", "JPEG encoder is closed or failed");
        return;
    }
    encoder->environment = env;
    if (bytes == NULL || row_count <= 0 || (uint64_t)encoder->row_bytes * row_count > (uint64_t)(*env)->GetArrayLength(env, bytes) ||
        (uint64_t)encoder->compressor.next_scanline + row_count > encoder->compressor.image_height) {
        throw_new(env, "java/lang/IllegalArgumentException", "Invalid JPEG RGB row data");
        return;
    }
    if (setjmp(encoder->jump)) {
        throw_new(env, "java/io/IOException", encoder->message);
        return;
    }
    for (jint row = 0; row < row_count; ++row) {
        (*env)->GetByteArrayRegion(env, bytes, (jsize)((size_t)row * encoder->row_bytes), (jsize)encoder->row_bytes, (jbyte *)encoder->row);
        if ((*env)->ExceptionCheck(env)) { encoder->failed = 1; return; }
        JSAMPROW scanline = encoder->row;
        if (jpeg_write_scanlines(&encoder->compressor, &scanline, 1) != 1) {
            encoder->failed = 1;
            throw_new(env, "java/io/IOException", "JPEG encoder unexpectedly suspended");
            return;
        }
    }
}

JNIEXPORT void JNICALL
Java_kr_ac_hit_networkprinterstudio_StreamingJpegEncoder_nativeFinish(
    JNIEnv *env, jobject owner, jlong handle) {
    (void)owner;
    Encoder *encoder = (Encoder *)(intptr_t)handle;
    if (encoder == NULL || encoder->failed || encoder->compressor.next_scanline != encoder->compressor.image_height) {
        throw_new(env, "java/lang/IllegalStateException", "JPEG row data is incomplete");
        return;
    }
    encoder->environment = env;
    if (setjmp(encoder->jump)) {
        throw_new(env, "java/io/IOException", encoder->message);
        return;
    }
    jpeg_finish_compress(&encoder->compressor);
}

JNIEXPORT void JNICALL
Java_kr_ac_hit_networkprinterstudio_StreamingJpegEncoder_nativeDestroy(
    JNIEnv *env, jobject owner, jlong handle) {
    (void)owner;
    destroy_encoder(env, (Encoder *)(intptr_t)handle);
}
