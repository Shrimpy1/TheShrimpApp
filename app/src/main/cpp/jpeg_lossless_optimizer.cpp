#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <setjmp.h>
#include <jpeglib.h>

struct ShrimpJpegErrorManager {
    jpeg_error_mgr base;
    jmp_buf jump_buffer;
};

struct ShrimpJpegContext {
    jpeg_decompress_struct source;
    jpeg_compress_struct destination;
    ShrimpJpegErrorManager error;
    FILE* input_file;
    FILE* output_file;
    jvirt_barray_ptr* coefficient_arrays;
    bool source_created;
    bool destination_created;
};

static void shrimpJpegErrorExit(j_common_ptr common) {
    auto* error = reinterpret_cast<ShrimpJpegErrorManager*>(common->err);
    longjmp(error->jump_buffer, 1);
}

static void cleanupContext(ShrimpJpegContext* context) {
    if (context == nullptr) return;
    if (context->destination_created) {
        jpeg_destroy_compress(&context->destination);
        context->destination_created = false;
    }
    if (context->source_created) {
        jpeg_destroy_decompress(&context->source);
        context->source_created = false;
    }
    if (context->input_file != nullptr) {
        fclose(context->input_file);
        context->input_file = nullptr;
    }
    if (context->output_file != nullptr) {
        fclose(context->output_file);
        context->output_file = nullptr;
    }
}

static bool isIccProfileMarker(const jpeg_saved_marker_ptr marker) {
    static const unsigned char signature[] = {'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', '\0'};
    return marker->marker == JPEG_APP0 + 2 &&
           marker->data_length >= sizeof(signature) &&
           memcmp(marker->data, signature, sizeof(signature)) == 0;
}

static bool shouldCopyMarker(const jpeg_saved_marker_ptr marker, bool preserve_metadata) {
    // libjpeg recreates the JFIF and Adobe markers based on copied JPEG parameters.
    // Copying them as arbitrary markers can create duplicate/conflicting declarations.
    if (marker->marker == JPEG_APP0 || marker->marker == JPEG_APP0 + 14) return false;

    if (preserve_metadata) {
        return (marker->marker >= JPEG_APP0 && marker->marker <= JPEG_APP0 + 15) ||
               marker->marker == JPEG_COM;
    }

    // Color profiles affect how pixels are rendered, so preserve ICC APP2 segments even when
    // optional descriptive metadata removal is requested.
    return isIccProfileMarker(marker);
}

static void copySavedMarkers(
    const jpeg_decompress_struct* source,
    jpeg_compress_struct* destination,
    bool preserve_metadata
) {
    for (jpeg_saved_marker_ptr marker = source->marker_list; marker != nullptr; marker = marker->next) {
        if (shouldCopyMarker(marker, preserve_metadata)) {
            jpeg_write_marker(
                destination,
                marker->marker,
                marker->data,
                marker->data_length
            );
        }
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_theshrimpapp_features_imageoptimizer_data_JpegTranNative_nativeOptimizeFile(
    JNIEnv* env,
    jobject /* instance */,
    jstring input_path,
    jstring output_path,
    jboolean preserve_metadata
) {
    if (input_path == nullptr || output_path == nullptr) return 1;

    const char* input_path_chars = env->GetStringUTFChars(input_path, nullptr);
    if (input_path_chars == nullptr) return 1;
    const char* output_path_chars = env->GetStringUTFChars(output_path, nullptr);
    if (output_path_chars == nullptr) {
        env->ReleaseStringUTFChars(input_path, input_path_chars);
        return 1;
    }

    // Allocate the context on the heap because setjmp/longjmp must not make modified automatic
    // C++ objects indeterminate. The context contains only C structs, flags, and FILE pointers.
    auto* context = new ShrimpJpegContext{};
    context->input_file = nullptr;
    context->output_file = nullptr;
    context->coefficient_arrays = nullptr;
    context->source_created = false;
    context->destination_created = false;

    jint result = 1;
    context->source.err = jpeg_std_error(&context->error.base);
    context->error.base.error_exit = shrimpJpegErrorExit;
    context->destination.err = &context->error.base;

    if (setjmp(context->error.jump_buffer) != 0) {
        cleanupContext(context);
        remove(output_path_chars);
        result = 1;
        goto finish;
    }

    context->input_file = fopen(input_path_chars, "rb");
    if (context->input_file == nullptr) goto cleanup;
    context->output_file = fopen(output_path_chars, "wb");
    if (context->output_file == nullptr) goto cleanup;

    jpeg_create_decompress(&context->source);
    context->source_created = true;
    jpeg_stdio_src(&context->source, context->input_file);

    // Keep all APP marker payloads available to the marker-copy step. The JPEG entropy-coded
    // coefficients remain the source of truth; they are never converted back into bitmap pixels.
    for (int marker = 0; marker < 16; ++marker) {
        jpeg_save_markers(&context->source, JPEG_APP0 + marker, 0xFFFF);
    }
    jpeg_save_markers(&context->source, JPEG_COM, 0xFFFF);

    if (jpeg_read_header(&context->source, TRUE) != JPEG_HEADER_OK) goto cleanup;
    context->coefficient_arrays = jpeg_read_coefficients(&context->source);
    if (context->coefficient_arrays == nullptr) goto cleanup;

    jpeg_create_compress(&context->destination);
    context->destination_created = true;
    jpeg_stdio_dest(&context->destination, context->output_file);
    jpeg_copy_critical_parameters(&context->source, &context->destination);

    // Rebuild entropy/Huffman coding from the existing DCT coefficient arrays. This does not
    // decode and recompress pixel data, unlike BitmapFactory + Bitmap.compress(JPEG).
    context->destination.optimize_coding = TRUE;
    jpeg_write_coefficients(&context->destination, context->coefficient_arrays);
    copySavedMarkers(&context->source, &context->destination, preserve_metadata == JNI_TRUE);
    jpeg_finish_compress(&context->destination);
    jpeg_finish_decompress(&context->source);

    cleanupContext(context);
    result = 0;
    goto finish;

cleanup:
    cleanupContext(context);
    remove(output_path_chars);
    result = 1;

finish:
    delete context;
    env->ReleaseStringUTFChars(input_path, input_path_chars);
    env->ReleaseStringUTFChars(output_path, output_path_chars);
    return result;
}
