#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <vpx/vp8dx.h>
#include <vpx/vpx_decoder.h>

typedef struct {
  vpx_codec_ctx_t codec;
  vpx_codec_iter_t iterator;
} WeKitVpxDecoder;

typedef struct {
  uint32_t width;
  uint32_t height;
  const uint8_t *planes[3];
  int32_t strides[3];
  int32_t high_bit_depth;
  int32_t full_range;
} WeKitVpxFrame;

WeKitVpxDecoder *wekit_vpx_decoder_create(void) {
  WeKitVpxDecoder *decoder = calloc(1, sizeof(WeKitVpxDecoder));
  if (decoder == NULL) {
    return NULL;
  }
  if (vpx_codec_dec_init(&decoder->codec, vpx_codec_vp9_dx(), NULL, 0) !=
      VPX_CODEC_OK) {
    free(decoder);
    return NULL;
  }
  return decoder;
}

void wekit_vpx_decoder_destroy(WeKitVpxDecoder *decoder) {
  if (decoder == NULL) {
    return;
  }
  vpx_codec_destroy(&decoder->codec);
  free(decoder);
}

int32_t wekit_vpx_decoder_decode(WeKitVpxDecoder *decoder, const uint8_t *data,
                                 size_t data_size) {
  if (decoder == NULL || data == NULL || data_size == 0) {
    return -1;
  }
  decoder->iterator = NULL;
  return (int32_t)vpx_codec_decode(&decoder->codec, data,
                                   (unsigned int)data_size, NULL, 0);
}

int32_t wekit_vpx_decoder_next_frame(WeKitVpxDecoder *decoder,
                                     WeKitVpxFrame *frame) {
  if (decoder == NULL || frame == NULL) {
    return -1;
  }
  vpx_image_t *image = vpx_codec_get_frame(&decoder->codec, &decoder->iterator);
  if (image == NULL) {
    return 0;
  }
  if (image->fmt != VPX_IMG_FMT_I420 && image->fmt != VPX_IMG_FMT_I42016) {
    return -2;
  }
  memset(frame, 0, sizeof(WeKitVpxFrame));
  frame->width = image->d_w;
  frame->height = image->d_h;
  frame->planes[0] = image->planes[0];
  frame->planes[1] = image->planes[1];
  frame->planes[2] = image->planes[2];
  frame->strides[0] = image->stride[0];
  frame->strides[1] = image->stride[1];
  frame->strides[2] = image->stride[2];
  frame->high_bit_depth = image->fmt == VPX_IMG_FMT_I42016;
  frame->full_range = image->range == VPX_CR_FULL_RANGE;
  return 1;
}

const char *wekit_vpx_decoder_error(WeKitVpxDecoder *decoder) {
  if (decoder == NULL) {
    return "VP9 decoder is unavailable";
  }
  const char *detail = vpx_codec_error_detail(&decoder->codec);
  return detail != NULL ? detail : vpx_codec_error(&decoder->codec);
}
