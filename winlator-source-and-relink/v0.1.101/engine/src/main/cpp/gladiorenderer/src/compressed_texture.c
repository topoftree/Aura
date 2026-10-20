/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
#define STB_DXT_IMPLEMENTATION
#include "stb_dxt.h"

#include "compressed_texture.h"
#include "bc_decoder.h"

void compressTexImage2D(uint32_t format, int width, int height, void* imageData, void* compressedData) {
    static const uint8_t offsets[16][2] = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {0, 1}, {1, 1}, {2, 1}, {3, 1}, {0, 2}, {1, 2}, {2, 2}, {3, 2}, {0, 3}, {1, 3}, {2, 3}, {3, 3}};
    int blockSize = format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT || format == GL_COMPRESSED_RGBA_S3TC_DXT1_EXT ? 8 : 16;
    int alpha = (format != GL_COMPRESSED_RGB_S3TC_DXT1_EXT && blockSize == 16) ? 1 : 0;

    GLuint* src = imageData;
    GLuint block[16];
    for (int y = 0, x, i; y < height; y += 4) {
        for (x = 0; x < width; x += 4) {
            for (i = 0; i < 16; i++) {
                block[i] = (x+offsets[i][0]) < width && (y+offsets[i][1]) < height ? src[x+offsets[i][0]+(y+offsets[i][1])*width] : 0;
            }
            stb_compress_dxt_block((uint8_t*)compressedData, (const uint8_t*)block, alpha, STB_DXT_NORMAL);
            compressedData += blockSize;
        }
    }
}

void* decompressTexImage2D(uint32_t format, int width, int height, const void* imageData, size_t imageSize, ThreadPool* threadPool) {
    // Closing the guest can interrupt RING_READ_BEGIN before any texture bytes arrive.
    // BCTask also stores dimensions in uint16_t; never silently truncate them.
    if (!imageData || width <= 0 || height <= 0 || width > UINT16_MAX || height > UINT16_MAX) return NULL;
    int bcN = 0;
    bool isNoAlphaU = format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT;

    switch (format) {
        case GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
        case GL_COMPRESSED_RGBA_S3TC_DXT1_EXT:
            bcN = 1;
            break;
        case GL_COMPRESSED_RGBA_S3TC_DXT3_EXT:
            bcN = 2;
            break;
        case GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
            bcN = 3;
            break;
        default:
            return NULL;
    }

    const size_t blocksWide = ((size_t)width + 3) / 4;
    const size_t blocksHigh = ((size_t)height + 3) / 4;
    const size_t required = blocksWide * blocksHigh * (bcN == 1 ? 8 : 16);
    if (imageSize < required) return NULL;
    void* decompressedData = malloc((size_t)width * height * 4);
    if (!decompressedData) return NULL;
    BCDecoder_decode(imageData, decompressedData, width, height, bcN, isNoAlphaU, threadPool);
    return decompressedData;
}
