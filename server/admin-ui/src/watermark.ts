const MODERN_GRID_SIZE = 512;
const MODERN_CANONICAL_GRID_SIZE = 512;
const MODERN_BLOCK_SIZE = 8;
// Keep each mark to two logical cells. At a typical 1080–1440 px panel this is a
// 4–6 px patch after rasterisation, which is substantially less conspicuous than
// the previous 4×4-cell marks while still leaving enough samples for a pair mean.
const MODERN_MARK_WIDTH = 2;
const MODERN_MARK_HEIGHT = 2;
// v3 originally shipped with 4×4-cell marks. Keep that layout as a decode-only
// fallback so screenshots captured before the visibility reduction remain traceable.
const LEGACY_MODERN_MARK_WIDTH = 4;
const LEGACY_MODERN_MARK_HEIGHT = 4;
const MODERN_BLOCKS_PER_SIDE =
  MODERN_CANONICAL_GRID_SIZE / MODERN_BLOCK_SIZE;
const MODERN_REPETITIONS = 10;
const MODERN_SYNC = new Uint8Array([0x57, 0x4b, 0x57, 0x4d, 0x33, 0x56, 0x31, 0x00]);
const MODERN_SYNC_BITS = MODERN_SYNC.length * 8;
const MODERN_TOKEN_BYTES = 32;
const MODERN_CHECKSUM_BYTES = 4;
const MODERN_FRAME_BITS =
  (MODERN_SYNC.length + 2 + MODERN_TOKEN_BYTES + MODERN_CHECKSUM_BYTES) * 8;
const MODERN_MIN_SYNC_SCORE = 44;
const MODERN_LAYOUT_SEED = 0x6a09e667;

// WKWM2V1 compatibility.  Keep this decoder independent from the v3 geometry so screenshots
// captured before the low-frequency signal was introduced remain traceable.
const V2_GRID_SIZE = 512;
const V2_BLOCK_SIZE = 4;
const V2_BLOCKS_PER_SIDE = V2_GRID_SIZE / V2_BLOCK_SIZE;
const V2_REPETITIONS = 15;
const V2_SYNC = new Uint8Array([0x57, 0x4b, 0x57, 0x4d, 0x32, 0x56, 0x31, 0x00]);
const V2_SYNC_BITS = V2_SYNC.length * 8;
const V2_TOKEN_BYTES = 32;
const V2_FRAME_BITS = (V2_SYNC.length + 2 + V2_TOKEN_BYTES) * 8;
const V2_MIN_SYNC_SCORE = 44;
const V2_LAYOUT_SEED = 0x6a09e667;

// Legacy WKWM1V2 layout. Keep this path so screenshots made before the randomized layout was
// shipped remain traceable.
const LEGACY_GRID_SIZE = 128;
const LEGACY_REPETITIONS = 3;
const LEGACY_SYNC = 0x574b574d31563200n; // WKWM1V2\0
const LEGACY_SYNC_BITS = 64;
const LEGACY_CIPHER_BITS = 256 * 8;
const LEGACY_FRAME_BITS = LEGACY_SYNC_BITS + LEGACY_CIPHER_BITS;
const LEGACY_CELL_COUNT = LEGACY_FRAME_BITS * LEGACY_REPETITIONS * 2;

class WatermarkPrng {
  private state: number;

  constructor(seed: number) {
    this.state = seed >>> 0;
  }

  nextUnsigned(): number {
    this.state ^= this.state << 13;
    this.state ^= this.state >>> 17;
    this.state ^= this.state << 5;
    this.state >>>= 0;
    return this.state;
  }

  nextInt(bound: number): number {
    return this.nextUnsigned() % bound;
  }
}

interface ModernPair {
  first: number;
  second: number;
  reversed: boolean;
  vertical: boolean;
}

function modernLayout(
  markWidth = MODERN_MARK_WIDTH,
  markHeight = MODERN_MARK_HEIGHT,
): ModernPair[] {
  const anchors = Array.from(
    { length: MODERN_BLOCKS_PER_SIDE * (MODERN_BLOCKS_PER_SIDE - 1) },
    (_, index) => {
      const row = Math.floor(index / (MODERN_BLOCKS_PER_SIDE - 1));
      const column = index % (MODERN_BLOCKS_PER_SIDE - 1);
      return row * MODERN_BLOCKS_PER_SIDE + column;
    },
  );
  const random = new WatermarkPrng(MODERN_LAYOUT_SEED);
  for (let index = anchors.length - 1; index > 0; index--) {
    const swap = random.nextInt(index + 1);
    [anchors[index], anchors[swap]] = [anchors[swap]!, anchors[index]!];
  }
  const pairs = new Array<ModernPair>(MODERN_FRAME_BITS * MODERN_REPETITIONS);
  for (let index = 0; index < pairs.length; index++) {
    const block = anchors[index]!;
    const blockRow = Math.floor(block / MODERN_BLOCKS_PER_SIDE);
    const blockColumn = block % MODERN_BLOCKS_PER_SIDE;
    const vertical = random.nextInt(2) === 1;
    const width = vertical ? markHeight : markWidth;
    const height = vertical ? markWidth : markHeight;
    // Keep both patches inside the macroblock: the pair occupies two widths or two heights.
    const rowSpan = vertical ? height * 2 : height;
    const columnSpan = vertical ? width : width * 2;
    const rowOffset = random.nextInt(MODERN_BLOCK_SIZE - rowSpan + 1);
    const columnOffset = random.nextInt(MODERN_BLOCK_SIZE - columnSpan + 1);
    const first =
      (blockRow * MODERN_BLOCK_SIZE + rowOffset) * MODERN_CANONICAL_GRID_SIZE +
      blockColumn * MODERN_BLOCK_SIZE +
      columnOffset;
    pairs[index] = {
      first,
      second:
        first +
        (vertical
          ? markWidth * MODERN_CANONICAL_GRID_SIZE
          : markWidth),
      reversed: random.nextInt(2) === 1,
      vertical,
    };
  }
  return pairs;
}

const MODERN_LAYOUT = modernLayout();
const LEGACY_MODERN_LAYOUT = modernLayout(
  LEGACY_MODERN_MARK_WIDTH,
  LEGACY_MODERN_MARK_HEIGHT,
);

type Signal = "luminance" | "red" | "green" | "blue" | "median-red";

function luminance(data: Uint8ClampedArray, offset: number): number {
  // Integer Rec. 709 weights keep the comparison stable across browsers.
  return (
    data[offset]! * 2126 + data[offset + 1]! * 7152 + data[offset + 2]! * 722
  );
}

function signalValue(
  data: Uint8ClampedArray,
  offset: number,
  signal: Signal,
  medianRed?: Uint8Array,
): number {
  switch (signal) {
    case "red":
      return data[offset]!;
    case "green":
      return data[offset + 1]!;
    case "blue":
      return data[offset + 2]!;
    case "median-red":
      return medianRed![offset / 4]!;
    default:
      return luminance(data, offset);
  }
}

/**
 * JPEG encoders tend to turn the very low alpha watermark into isolated red-channel outliers.
 * A small median pass removes those outliers while preserving the pair ordering. Keep this
 * derived signal separate from the source pixels so the normal decoder remains unchanged.
 */
function medianRedChannel(
  data: Uint8ClampedArray,
  width: number,
  height: number,
): Uint8Array {
  const result = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    const y0 = Math.max(0, y - 1);
    const y1 = Math.min(height - 1, y + 1);
    for (let x = 0; x < width; x++) {
      const x0 = Math.max(0, x - 1);
      const x1 = Math.min(width - 1, x + 1);
      const values: number[] = [];
      for (let row = y0; row <= y1; row++) {
        for (let column = x0; column <= x1; column++)
          values.push(data[(row * width + column) * 4]!);
      }
      values.sort((a, b) => a - b);
      result[y * width + x] = values[Math.floor(values.length / 2)]!;
    }
  }
  return result;
}

function bit(value: bigint, index: number): number {
  return Number((value >> BigInt(63 - index)) & 1n);
}

function modernBit(value: Uint8Array, index: number): number {
  return (value[Math.floor(index / 8)]! >> (7 - (index % 8))) & 1;
}

function modernPatchSignal(
  pixels: Uint8ClampedArray,
  cell: number,
  signal: Signal,
  medianRed?: Uint8Array,
  vertical = false,
  markWidth = MODERN_MARK_WIDTH,
  markHeight = MODERN_MARK_HEIGHT,
): number {
  const row = Math.floor(cell / MODERN_CANONICAL_GRID_SIZE);
  const column = cell % MODERN_CANONICAL_GRID_SIZE;
  let total = 0;
  let count = 0;
  const width = vertical ? markHeight : markWidth;
  const height = vertical ? markWidth : markHeight;
  for (let y = row; y < row + height; y++) {
    for (let x = column; x < column + width; x++) {
      total += signalValue(
        pixels,
        (y * MODERN_GRID_SIZE + x) * 4,
        signal,
        medianRed,
      );
      count++;
    }
  }
  return total / count;
}

function modernFrameBytes(bits: number[]): Uint8Array {
  const bytes = new Uint8Array(MODERN_FRAME_BITS / 8);
  for (let byte = 0; byte < bytes.length; byte++) {
    let value = 0;
    for (let index = 0; index < 8; index++)
      value = (value << 1) | bits[byte * 8 + index]!;
    bytes[byte] = value;
  }
  return bytes;
}

function watermarkCrc32(bytes: Uint8Array, length: number): number {
  let crc = 0xffffffff;
  for (let index = 0; index < length; index++) {
    crc ^= bytes[index]!;
    for (let bit = 0; bit < 8; bit++)
      crc = (crc & 1) !== 0 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function modernToken(bits: number[]): string | null {
  const frame = modernFrameBytes(bits);
  const lengthOffset = MODERN_SYNC.length;
  const length = (frame[lengthOffset]! << 8) | frame[lengthOffset + 1]!;
  if (length !== MODERN_TOKEN_BYTES) return null;
  const checksumOffset = frame.length - MODERN_CHECKSUM_BYTES;
  const expected =
    (frame[checksumOffset]! << 24) |
    (frame[checksumOffset + 1]! << 16) |
    (frame[checksumOffset + 2]! << 8) |
    frame[checksumOffset + 3]!;
  if ((expected >>> 0) !== watermarkCrc32(frame, checksumOffset)) return null;
  const output = frame.slice(lengthOffset + 2, checksumOffset);
  return new TextDecoder("ascii").decode(output);
}

interface V2Pair {
  first: number;
  second: number;
  reversed: boolean;
}

function v2Layout(): V2Pair[] {
  const anchors = Array.from(
    { length: V2_BLOCKS_PER_SIDE * (V2_BLOCKS_PER_SIDE - 1) },
    (_, index) => {
      const row = Math.floor(index / (V2_BLOCKS_PER_SIDE - 1));
      const column = index % (V2_BLOCKS_PER_SIDE - 1);
      return row * V2_BLOCKS_PER_SIDE + column;
    },
  );
  const random = new WatermarkPrng(V2_LAYOUT_SEED);
  for (let index = anchors.length - 1; index > 0; index--) {
    const swap = random.nextInt(index + 1);
    [anchors[index], anchors[swap]] = [anchors[swap]!, anchors[index]!];
  }
  const pairs = new Array<V2Pair>(V2_FRAME_BITS * V2_REPETITIONS);
  for (let index = 0; index < pairs.length; index++) {
    const block = anchors[index]!;
    const blockRow = Math.floor(block / V2_BLOCKS_PER_SIDE);
    const blockColumn = block % V2_BLOCKS_PER_SIDE;
    const rowOffset = random.nextInt(V2_BLOCK_SIZE);
    const columnOffset = random.nextInt(V2_BLOCK_SIZE - 1);
    const first =
      (blockRow * V2_BLOCK_SIZE + rowOffset) * V2_GRID_SIZE +
      blockColumn * V2_BLOCK_SIZE +
      columnOffset;
    pairs[index] = {
      first,
      second: first + 1,
      reversed: random.nextInt(2) === 1,
    };
  }
  return pairs;
}

const V2_LAYOUT = v2Layout();

function v2Token(bits: number[]): string {
  const output = new Uint8Array(V2_TOKEN_BYTES);
  for (let byte = 0; byte < output.length; byte++) {
    let value = 0;
    for (let index = 0; index < 8; index++)
      value = (value << 1) | bits[V2_SYNC_BITS + 16 + byte * 8 + index]!;
    output[byte] = value;
  }
  return new TextDecoder("ascii").decode(output);
}

function decodeV2Candidate(
  pixels: Uint8ClampedArray,
  signal: Signal,
  medianRed?: Uint8Array,
): { score: number; ciphertext: string | null } {
  const bits: number[] = [];
  let syncScore = 0;
  for (let logical = 0; logical < V2_FRAME_BITS; logical++) {
    let votes = 0;
    for (let repetition = 0; repetition < V2_REPETITIONS; repetition++) {
      const pair = V2_LAYOUT[logical * V2_REPETITIONS + repetition]!;
      const firstLuma = signalValue(pixels, pair.first * 4, signal, medianRed);
      const secondLuma = signalValue(pixels, pair.second * 4, signal, medianRed);
      votes += (firstLuma >= secondLuma ? 1 : 0) ^ (pair.reversed ? 1 : 0);
    }
    const decoded = votes >= Math.ceil(V2_REPETITIONS / 2) ? 1 : 0;
    bits.push(decoded);
    if (logical < V2_SYNC_BITS && decoded === modernBit(V2_SYNC, logical))
      syncScore++;
  }
  if (syncScore < V2_MIN_SYNC_SCORE)
    return { score: syncScore, ciphertext: null };
  const token = v2Token(bits);
  if (!/^[A-Za-z0-9_-]{32}$/.test(token))
    return { score: syncScore, ciphertext: null };
  return { score: syncScore, ciphertext: token };
}

function decodeModernCandidate(
  pixels: Uint8ClampedArray,
  signal: Signal,
  medianRed?: Uint8Array,
  layout: readonly ModernPair[] = MODERN_LAYOUT,
  markWidth = MODERN_MARK_WIDTH,
  markHeight = MODERN_MARK_HEIGHT,
): {
  score: number;
  ciphertext: string | null;
} {
  const bits: number[] = [];
  let syncScore = 0;
  for (let logical = 0; logical < MODERN_FRAME_BITS; logical++) {
    let votes = 0;
    for (let repetition = 0; repetition < MODERN_REPETITIONS; repetition++) {
      const pair = layout[logical * MODERN_REPETITIONS + repetition]!;
      const firstLuma = modernPatchSignal(
        pixels,
        pair.first,
        signal,
        medianRed,
        pair.vertical,
        markWidth,
        markHeight,
      );
      const secondLuma = modernPatchSignal(
        pixels,
        pair.second,
        signal,
        medianRed,
        pair.vertical,
        markWidth,
        markHeight,
      );
      // The renderer reverses the pair for a random orientation. Undo that before voting.
      votes += (firstLuma >= secondLuma ? 1 : 0) ^ (pair.reversed ? 1 : 0);
    }
    const decoded = votes >= Math.ceil(MODERN_REPETITIONS / 2) ? 1 : 0;
    bits.push(decoded);
    if (logical < MODERN_SYNC_BITS && decoded === modernBit(MODERN_SYNC, logical))
      syncScore++;
  }
  if (syncScore < MODERN_MIN_SYNC_SCORE)
    return { score: syncScore, ciphertext: null };
  const token = modernToken(bits);
  if (token === null || !/^[A-Za-z0-9_-]{32}$/.test(token))
    return { score: syncScore, ciphertext: null };
  return { score: syncScore, ciphertext: token };
}

function legacyCiphertext(bits: number[]): string {
  const output = new Uint8Array(256);
  for (let byte = 0; byte < output.length; byte++) {
    let value = 0;
    for (let index = 0; index < 8; index++)
      value = (value << 1) | bits[LEGACY_SYNC_BITS + byte * 8 + index]!;
    output[byte] = value;
  }
  let raw = "";
  for (const value of output) raw += String.fromCharCode(value);
  return btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function decodeLegacyCandidate(
  pixels: Uint8ClampedArray,
  signal: Signal,
  medianRed?: Uint8Array,
): {
  score: number;
  ciphertext: string | null;
} {
  const bits: number[] = [];
  let syncScore = 0;
  for (let logical = 0; logical < LEGACY_FRAME_BITS; logical++) {
    let ones = 0;
    for (let repetition = 0; repetition < LEGACY_REPETITIONS; repetition++) {
      const pair = (logical * LEGACY_REPETITIONS + repetition) * 2;
      if (pair + 1 >= LEGACY_CELL_COUNT) return { score: 0, ciphertext: null };
      const first = (pair % LEGACY_GRID_SIZE) + Math.floor(pair / LEGACY_GRID_SIZE) * LEGACY_GRID_SIZE;
      const second = ((pair + 1) % LEGACY_GRID_SIZE) + Math.floor((pair + 1) / LEGACY_GRID_SIZE) * LEGACY_GRID_SIZE;
      const firstLuma = signalValue(pixels, first * 4, signal, medianRed);
      const secondLuma = signalValue(pixels, second * 4, signal, medianRed);
      if (firstLuma >= secondLuma) ones++;
    }
    const decoded = ones >= 2 ? 1 : 0;
    bits.push(decoded);
    if (logical < LEGACY_SYNC_BITS && decoded === bit(LEGACY_SYNC, logical)) syncScore++;
  }
  if (syncScore < 58) return { score: syncScore, ciphertext: null };
  return { score: syncScore, ciphertext: legacyCiphertext(bits) };
}

interface Candidate {
  width: number;
  top: number;
  height: number;
}

interface RasterSource {
  image: CanvasImageSource;
  width: number;
  height: number;
}

const CANDIDATES: Candidate[] = [];
for (const width of [0.55, 0.58, 0.6, 0.62, 0.64, 0.66, 0.68, 0.7, 0.72, 0.74, 0.76, 0.78, 0.8, 0.82, 0.84, 0.86, 0.88, 0.9, 0.92, 0.94, 0.96, 0.98, 1]) {
  for (const top of [0, 0.025, 0.05, 0.075, 0.1, 0.125]) {
    for (const height of [0.8, 0.9, 1]) {
      if (top + height <= 1) CANDIDATES.push({ width, top, height });
    }
  }
}

// Decode cheap channel variants before the median pass. The latter is deliberately deferred
// until a candidate has a plausible sync score because it touches every pixel in the crop.
const FAST_SIGNALS: Signal[] = ["luminance", "red", "green", "blue"];
const MEDIAN_TRIGGER_SCORE = MODERN_MIN_SYNC_SCORE - 8;
const SMALL_ROTATIONS = [-2, -1, -0.5, 0.5, 1, 2];

function drawCandidate(
  context: CanvasRenderingContext2D,
  source: RasterSource,
  candidate: Candidate,
  correctionDegrees = 0,
): void {
  context.clearRect(0, 0, context.canvas.width, context.canvas.height);
  const sourceWidth = source.width * candidate.width;
  const sourceHeight = source.height * candidate.height;
  if (correctionDegrees === 0) {
    context.drawImage(
      source.image,
      0,
      source.height * candidate.top,
      sourceWidth,
      sourceHeight,
      0,
      0,
      context.canvas.width,
      context.canvas.height,
    );
    return;
  }
  // A small rotation is applied after the crop. This keeps the panel anchored at the left edge
  // while undoing the camera/gallery tilt seen in screenshots, without changing the crop search.
  context.save();
  context.translate(context.canvas.width / 2, context.canvas.height / 2);
  context.rotate((correctionDegrees * Math.PI) / 180);
  context.drawImage(
    source.image,
    0,
    source.height * candidate.top,
    sourceWidth,
    sourceHeight,
    -context.canvas.width / 2,
    -context.canvas.height / 2,
    context.canvas.width,
    context.canvas.height,
  );
  context.restore();
}

function quarterTurn(source: RasterSource, degrees: 90 | -90): RasterSource {
  const canvas = document.createElement("canvas");
  canvas.width = source.height;
  canvas.height = source.width;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("当前浏览器不支持截图解析。");
  context.translate(canvas.width / 2, canvas.height / 2);
  context.rotate((degrees * Math.PI) / 180);
  context.drawImage(source.image, -source.width / 2, -source.height / 2);
  return { image: canvas, width: canvas.width, height: canvas.height };
}

/**
 * Extracts opaque watermark token candidates. The panel may be captured as a crop or as the left
 * side of a full-screen screenshot, so a bounded set of normalized crops and signal transforms is
 * checked. No identity is present here: only initialization tokens are handed to the authenticated
 * Worker, which validates them against its private mapping.
 */
export async function extractWatermarkCandidates(file: File): Promise<string[]> {
  if (file.size > 32 * 1024 * 1024)
    throw new Error("截图文件过大（最大 32 MiB）。");
  const bitmap = await createImageBitmap(file);
  try {
    if (bitmap.width > 12000 || bitmap.height > 12000)
      throw new Error("截图分辨率过高，无法安全解析。");

    const modernCanvas = document.createElement("canvas");
    modernCanvas.width = MODERN_GRID_SIZE;
    modernCanvas.height = MODERN_GRID_SIZE;
    const modernContext = modernCanvas.getContext("2d", { willReadFrequently: true });
    if (!modernContext) throw new Error("当前浏览器不支持截图解析。");
    modernContext.imageSmoothingEnabled = true;

    const normalSource: RasterSource = {
      image: bitmap,
      width: bitmap.width,
      height: bitmap.height,
    };
    const discovered = new Map<string, { score: number; order: number }>();
    let discoveryOrder = 0;
    function remember(decoded: { score: number; ciphertext: string | null }): void {
      if (!decoded.ciphertext) return;
      const previous = discovered.get(decoded.ciphertext);
      if (!previous || decoded.score > previous.score)
        discovered.set(decoded.ciphertext, { score: decoded.score, order: discoveryOrder });
      discoveryOrder++;
    }
    const decodeModernSource = (
      source: RasterSource,
      rotations: readonly number[],
      layout: readonly ModernPair[] = MODERN_LAYOUT,
      markWidth = MODERN_MARK_WIDTH,
      markHeight = MODERN_MARK_HEIGHT,
    ): boolean => {
      for (const correction of rotations) {
        for (const candidate of CANDIDATES) {
          drawCandidate(modernContext, source, candidate, correction);
          const pixels = modernContext.getImageData(
            0,
            0,
            MODERN_GRID_SIZE,
            MODERN_GRID_SIZE,
          ).data;
          let bestCandidateScore = 0;
          let hasFullSync = false;
          for (const signal of FAST_SIGNALS) {
            const decoded = decodeModernCandidate(
              pixels,
              signal,
              undefined,
              layout,
              markWidth,
              markHeight,
            );
            bestCandidateScore = Math.max(bestCandidateScore, decoded.score);
            remember(decoded);
            hasFullSync ||= decoded.score === MODERN_SYNC_BITS && decoded.ciphertext !== null;
          }
          if (bestCandidateScore >= MEDIAN_TRIGGER_SCORE) {
            const decoded = decodeModernCandidate(
              pixels,
              "median-red",
              medianRedChannel(pixels, MODERN_GRID_SIZE, MODERN_GRID_SIZE),
              layout,
              markWidth,
              markHeight,
            );
            remember(decoded);
            hasFullSync ||= decoded.score === MODERN_SYNC_BITS && decoded.ciphertext !== null;
          }
          // Decode the previous one-pixel WKWM2V1 layout from the same normalized raster.  This
          // stays on the compatibility path and does not affect the v3 low-frequency signal.
          for (const signal of FAST_SIGNALS) {
            const decoded = decodeV2Candidate(pixels, signal);
            bestCandidateScore = Math.max(bestCandidateScore, decoded.score);
            remember(decoded);
            hasFullSync ||= decoded.score === V2_SYNC_BITS && decoded.ciphertext !== null;
          }
          if (bestCandidateScore >= V2_MIN_SYNC_SCORE) {
            const decoded = decodeV2Candidate(
              pixels,
              "median-red",
              medianRedChannel(pixels, MODERN_GRID_SIZE, MODERN_GRID_SIZE),
            );
            remember(decoded);
            hasFullSync ||= decoded.score === V2_SYNC_BITS && decoded.ciphertext !== null;
          }
          // Keep the first geometrically valid crop, but preserve all channel candidates from it
          // so the caller can ask the server which one maps to an initialization. This matters
          // when JPEG noise produces a different, syntactically valid token with the same sync.
          if (hasFullSync) return true;
        }
      }
      return false;
    };
    const normalOrientationFound = decodeModernSource(normalSource, [0, ...SMALL_ROTATIONS]);
    if (!normalOrientationFound) {
      // Try the old 4×4-cell v3 layout before rotating. The frame format is unchanged, so the
      // decoder can preserve screenshots made by the previous renderer without affecting the
      // current low-visibility layout.
      const legacyModernFound = decodeModernSource(
        normalSource,
        [0, ...SMALL_ROTATIONS],
        LEGACY_MODERN_LAYOUT,
        LEGACY_MODERN_MARK_WIDTH,
        LEGACY_MODERN_MARK_HEIGHT,
      );
      if (legacyModernFound) return [...discovered.entries()]
        .sort(([, left], [, right]) => right.score - left.score || left.order - right.order)
        .slice(0, 16)
        .map(([ciphertext]) => ciphertext);
      // Allocate the wide rotated canvases only when the normal orientation did not produce a
      // plausible frame; large screenshots otherwise needlessly double their peak memory use.
      decodeModernSource(quarterTurn(normalSource, 90), [0]);
      decodeModernSource(quarterTurn(normalSource, -90), [0]);
      decodeModernSource(
        quarterTurn(normalSource, 90),
        [0],
        LEGACY_MODERN_LAYOUT,
        LEGACY_MODERN_MARK_WIDTH,
        LEGACY_MODERN_MARK_HEIGHT,
      );
      decodeModernSource(
        quarterTurn(normalSource, -90),
        [0],
        LEGACY_MODERN_LAYOUT,
        LEGACY_MODERN_MARK_WIDTH,
        LEGACY_MODERN_MARK_HEIGHT,
      );
    }

    // Legacy fallback: screenshots generated before the randomized 512² layout used the fixed
    // adjacent-cell layout. Keep the old decoder isolated so the modern path stays deterministic.
    const legacyCanvas = document.createElement("canvas");
    legacyCanvas.width = LEGACY_GRID_SIZE;
    legacyCanvas.height = LEGACY_GRID_SIZE;
    const legacyContext = legacyCanvas.getContext("2d", { willReadFrequently: true });
    if (!legacyContext) throw new Error("当前浏览器不支持截图解析。");
    legacyContext.imageSmoothingEnabled = true;
    for (const candidate of CANDIDATES) {
      drawCandidate(legacyContext, normalSource, candidate);
      const pixels = legacyContext.getImageData(
        0,
        0,
        LEGACY_GRID_SIZE,
        LEGACY_GRID_SIZE,
      ).data;
      let bestCandidateScore = 0;
      let hasFullSync = false;
      for (const signal of FAST_SIGNALS) {
        const decoded = decodeLegacyCandidate(pixels, signal);
        bestCandidateScore = Math.max(bestCandidateScore, decoded.score);
        remember(decoded);
        hasFullSync ||= decoded.score === LEGACY_SYNC_BITS && decoded.ciphertext !== null;
      }
      if (bestCandidateScore >= 50) {
        const decoded = decodeLegacyCandidate(
          pixels,
          "median-red",
          medianRedChannel(pixels, LEGACY_GRID_SIZE, LEGACY_GRID_SIZE),
        );
        remember(decoded);
        hasFullSync ||= decoded.score === LEGACY_SYNC_BITS && decoded.ciphertext !== null;
      }
      if (hasFullSync) break;
    }
    const candidates = [...discovered.entries()]
      .sort(([, left], [, right]) => right.score - left.score || left.order - right.order)
      .slice(0, 16)
      .map(([ciphertext]) => ciphertext);
    if (candidates.length > 0) return candidates;
    throw new Error("未检测到有效的 WeKit 水印。");
  } finally {
    bitmap.close();
  }
}

/** Compatibility helper for callers that only need the highest-scoring decoded token. */
export async function extractWatermark(file: File): Promise<string> {
  return (await extractWatermarkCandidates(file))[0]!;
}
