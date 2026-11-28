// Package watchimage encodes arbitrary images into the compact, palettized
// bitmap format a Pebble (Basalt) watch renders. It is a self-contained,
// dependency-free (stdlib + golang.org/x/image/draw) image pipeline: decode →
// scale-to-fit/heap-budget → quantize to the Pebble's 64-color GColor8 space →
// pack at an appropriate bit depth, returning JSON the watch consumes.
//
// It was extracted from MirrorMsg's Google Messages bridge so the image path can
// be reused on its own. It knows nothing about MirrorMsg, messaging, or any
// bridge — callers hand it raw image bytes and sizing parameters and receive the
// encoded result. See EncodeImageForWatch for the entry point.
//
// # The Pebble color model (why this looks the way it does)
//
// Basalt's display is 2 bits per channel: 4×4×4 = 64 possible colors, each
// represented as a GColor8 byte 0b11rrggbb (top two bits are alpha=opaque). A
// given image is drawn from a per-image palette of up to 16 of those 64 colors
// (4bpp), so two things bound fidelity: the 64-color device gamut, and the
// 16-slot per-image palette. Crucially, an image with "hundreds of shades of
// blue and yellow" does NOT need hundreds of colors here — those shades collapse
// into a handful of GColor8 buckets once projected into 2-bit-per-channel space.
// The analyzer (AnalyzeColors) measures this post-quantization reality, not the
// source image's nominal color count, and picks the smallest bit depth that
// renders the image without visible loss.
package watchimage

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	_ "image/gif"  // register GIF decoder
	_ "image/jpeg" // register JPEG decoder
	_ "image/png"  // register PNG decoder
	"math"
	"sort"

	"golang.org/x/image/draw"
)

// BitDepth is the packed bits-per-pixel of an encoded image. Fewer bits = less
// watch heap (cost is w*h/(8/bpp) bytes), at the cost of fewer distinct colors.
type BitDepth int

const (
	Depth1 BitDepth = 1 // 2-color   (w*h/8 bytes)
	Depth2 BitDepth = 2 // 4-color   (w*h/4 bytes)
	Depth4 BitDepth = 4 // 16-color  (w*h/2 bytes) — the maximum / default
	Depth8 BitDepth = 8 // full GColor8, 1 byte/pixel (no per-image palette)
)

// WatchImageResult is the encoded image the watch consumes. Pixels is base64; its
// meaning depends on Format. Palette is base64 of up to 2^Depth GColor8 bytes
// (empty for Format 0 / 8bpp). Depth is the packed bit depth (1/2/4); it lets the
// watch size its heap reservation and unpack correctly.
type WatchImageResult struct {
	Width   int    `json:"w"`
	Height  int    `json:"h"`
	Pixels  string `json:"pixels"`  // base64: packed indices (4/2/1 bpp) or GColor8 bytes (8bpp)
	Format  int    `json:"fmt"`     // 0 = 8bpp full color, 1 = palettized (see Depth)
	Palette string `json:"palette"` // base64 of up to 2^Depth GColor8 bytes (palettized only)
	Depth   int    `json:"depth"`   // packed bits/pixel: 1, 2, 4 (palettized), or 8
	// EstCompressed: byte-exact row-compressed size of this result's TIGHT packing
	// (set in CompressFit mode). Lets the phone cross-check its own compression,
	// which is byte-identical by design.
	EstCompressed int `json:"estCompressed,omitempty"`
}

// ---- Pebble GColor8 space ---------------------------------------------------

// gcolorDist is the squared distance between two GColor8 bytes, weighted for
// rough perceptual similarity (green matters most, blue least — the classic
// 3:6:1 luma weighting). Working in the device's 2-bit-per-channel space means
// two source shades that land in the same bucket have distance 0, i.e. the watch
// genuinely cannot tell them apart.
func gcolorDist(a, b byte) int {
	ar := int((a >> 4) & 3)
	ag := int((a >> 2) & 3)
	ab := int(a & 3)
	br := int((b >> 4) & 3)
	bg := int((b >> 2) & 3)
	bb := int(b & 3)
	dr := ar - br
	dg := ag - bg
	db := ab - bb
	// 3:6:1 R:G:B weighting (scaled) so perceptual closeness drives merging.
	return 3*dr*dr + 6*dg*dg + 1*db*db
}

// gcolorHueFamily maps a GColor8 byte to a coarse hue family id. The Pebble gamut
// is hue-poor, and hue transitions are far more visible than brightness steps, so
// the analyzer counts hue families separately from raw buckets: an image that is
// "one hue, many brightnesses" can drop to a low depth safely, while one spanning
// many hues cannot. Families: 0 grey/neutral, then 6 primary/secondary sextants.
func gcolorHueFamily(v byte) int {
	r := int((v >> 4) & 3)
	g := int((v >> 2) & 3)
	b := int(v & 3)
	mx := r
	if g > mx {
		mx = g
	}
	if b > mx {
		mx = b
	}
	mn := r
	if g < mn {
		mn = g
	}
	if b < mn {
		mn = b
	}
	// Low chroma (max≈min) => neutral/grey, no meaningful hue.
	if mx-mn <= 1 {
		return 0
	}
	// Classify by which channels dominate — a coarse 6-sextant hue wheel.
	switch {
	case r == mx && b == mn:
		return 1 // red→yellow
	case g == mx && b == mn:
		return 2 // yellow→green
	case g == mx && r == mn:
		return 3 // green→cyan
	case b == mx && r == mn:
		return 4 // cyan→blue
	case b == mx && g == mn:
		return 5 // blue→magenta
	default:
		return 6 // magenta→red
	}
}

// ---- Color analysis (the detector) ------------------------------------------

// ColorAnalysis summarizes an image's color needs in Pebble space. It drives the
// bit-depth choice: the fields describe how many distinct device colors actually
// carry the image, how many hue families they span, and whether the image is
// smooth (gradient-like, prone to banding at low depth) or busy.
type ColorAnalysis struct {
	// SignificantColors is the number of GColor8 buckets needed to cover
	// CoverageTarget of the pixels (the long tail of rare buckets is dropped).
	SignificantColors int
	// DistinctBuckets is the raw count of non-empty GColor8 buckets (incl. tail).
	DistinctBuckets int
	// HueFamilies is how many coarse hue families the significant colors span.
	HueFamilies int
	// Smoothness in [0,1]: fraction of adjacent-pixel pairs that are identical
	// after quantization. High => large flat/gradient regions (banding risk at
	// low depth); low => busy/noisy (banding hides).
	Smoothness float64
	// RecommendedDepth is the chosen packed bit depth for encoding.
	RecommendedDepth BitDepth
}

// analysis tuning.
const (
	analyzeMaxDim   = 64   // downsample the image to this box for fast analysis
	coverageTarget  = 0.97 // significant colors must cover this fraction of pixels
	tailBucketFloor = 0.004
)

// AnalyzeColors projects an image into Pebble GColor8 space and reports how many
// colors it actually needs on-device, then recommends a bit depth (never above
// maxDepth). It analyzes a downsampled copy (analyzeMaxDim box) for speed —
// enough to estimate color population and smoothness without touching every pixel
// of a large source.
//
// maxDepth caps the recommendation: pass Depth2 for thumbnails (choose only 2 or
// 4 colors, never 16), Depth4 for the enhanced view (choose 16/4/2). See
// chooseDepth for the cap semantics.
//
// The recommendation logic, in order:
//   - Count GColor8 buckets; find the smallest set covering coverageTarget of
//     pixels (SignificantColors), ignoring a rare-bucket noise floor.
//   - Map SignificantColors to a base depth (≤2→1bpp, ≤4→2bpp, ≤16→4bpp).
//   - Refine with hue/smoothness: a low-hue image (few hue families) can drop a
//     step even with several shade-buckets, because shades collapse acceptably;
//     but a SMOOTH image (gradient) is held UP a step to avoid visible banding.
//   - Clamp to maxDepth.
func AnalyzeColors(src image.Image, maxDepth BitDepth, coverage float64) ColorAnalysis {
	small := downsampleForAnalysis(src)
	b := small.Bounds()
	w, h := b.Dx(), b.Dy()
	if w == 0 || h == 0 {
		d := Depth4
		if maxDepth == Depth1 || maxDepth == Depth2 {
			d = maxDepth
		}
		return ColorAnalysis{SignificantColors: 16, DistinctBuckets: 16, HueFamilies: 6, RecommendedDepth: d}
	}

	// Quantize every analysis pixel to a GColor8 bucket; histogram the 64 buckets.
	g8 := make([]byte, w*h)
	var counts [64]int
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			r, g, bl, _ := small.At(b.Min.X+x, b.Min.Y+y).RGBA()
			v := toGColor8(r, g, bl)
			g8[y*w+x] = v
			counts[int(v&0x3F)]++
		}
	}
	total := w * h

	// Distinct buckets + coverage-based significant-color count.
	type bc struct {
		idx int
		n   int
	}
	nonempty := make([]bc, 0, 64)
	for i, n := range counts {
		if n > 0 {
			nonempty = append(nonempty, bc{i, n})
		}
	}
	sort.Slice(nonempty, func(i, j int) bool { return nonempty[i].n > nonempty[j].n })

	floor := int(float64(total) * tailBucketFloor)
	// coverage: significant colors must cover this fraction of pixels. A caller can
	// pass a looser value for thumbnails (drop more of the rarest colors by area)
	// than for the full view. Guard against an unset (0) or nonsensical value.
	cov := coverage
	if cov <= 0 || cov > 1 {
		cov = coverageTarget
	}
	needed := int(math.Ceil(float64(total) * cov))
	acc, significant := 0, 0
	sigBuckets := make([]int, 0, 16)
	for _, e := range nonempty {
		if significant > 0 && e.n < floor && acc >= needed {
			break
		}
		acc += e.n
		significant++
		sigBuckets = append(sigBuckets, e.idx)
		if acc >= needed {
			break
		}
	}
	if significant == 0 {
		significant = 1
	}

	// Hue families among the significant buckets.
	var hueSeen [7]bool
	hueCount := 0
	for _, idx := range sigBuckets {
		f := gcolorHueFamily(byte(0xC0 | idx))
		if !hueSeen[f] {
			hueSeen[f] = true
			hueCount++
		}
	}

	// Smoothness: fraction of horizontally-adjacent pixel pairs that are equal
	// after quantization. Cheap proxy for "large flat/gradient regions."
	sameAdj, totAdj := 0, 0
	for y := 0; y < h; y++ {
		for x := 1; x < w; x++ {
			totAdj++
			if g8[y*w+x] == g8[y*w+x-1] {
				sameAdj++
			}
		}
	}
	smooth := 0.0
	if totAdj > 0 {
		smooth = float64(sameAdj) / float64(totAdj)
	}

	res := ColorAnalysis{
		SignificantColors: significant,
		DistinctBuckets:   len(nonempty),
		HueFamilies:       hueCount,
		Smoothness:        smooth,
	}
	res.RecommendedDepth = chooseDepth(res, maxDepth)
	return res
}

// chooseDepth maps an analysis to a packed bit depth.
// chooseDepth maps an analysis to a packed bit depth, never exceeding maxDepth.
// maxDepth lets a caller cap the result: e.g. thumbnails cap at Depth2 (4 colors)
// so the detector chooses only between 2 and 4 colors — never the wasteful 16 —
// while the enhanced view caps at Depth4 (16 colors) and picks 16/4/2. A zero or
// out-of-range maxDepth is treated as Depth4 (the normal ceiling).
func chooseDepth(a ColorAnalysis, maxDepth BitDepth) BitDepth {
	if maxDepth != Depth1 && maxDepth != Depth2 && maxDepth != Depth4 {
		maxDepth = Depth4
	}
	// Base depth from significant color count.
	var depth BitDepth
	switch {
	case a.SignificantColors <= 2:
		depth = Depth1
	case a.SignificantColors <= 4:
		depth = Depth2
	default:
		depth = Depth4
	}

	// Banding risk threshold. Lowered from 0.85: a real photograph measures around
	// 0.82 (verified on a dark interior shot), so the old threshold sat just above
	// the exact case it existed to protect and never fired for photos.
	const smoothBandRisk = 0.60

	// Hue refinement: if the image spans very few hue families, its "extra" colors
	// are mostly shades of one hue, which for a FLAT GRAPHIC collapse acceptably —
	// allow one step down (never below 1bpp).
	//
	// Gated on low smoothness, which matters more than it looks: a dark, moody
	// PHOTO is also "one hue family" (all blue-grey), but its content is precisely
	// a long ramp of SHADES — the thing hue count cannot see. Without this gate a
	// photo measuring 4 significant colors got demoted to Depth2 and then again to
	// Depth1, i.e. rendered with TWO colors: solid black blobs against one flat
	// tone. That was the real cause of the "harsh black/cyan banding", not palette
	// selection or dither gating.
	if a.Smoothness < smoothBandRisk {
		if a.HueFamilies <= 1 && depth == Depth4 && a.SignificantColors <= 8 {
			depth = Depth2
		} else if a.HueFamilies <= 1 && depth == Depth2 {
			depth = Depth1
		}
	}

	// Banding guard: a SMOOTH image (large gradient regions) bands visibly at low
	// depth. Hold it UP one step (never above 4bpp) when smoothness is high but it
	// isn't a truly flat 1-2 color graphic. This runs BEFORE the maxDepth clamp so
	// the clamp always has the final say (a thumbnail capped at Depth2 stays there
	// even if banding would otherwise push toward Depth4).
	if a.Smoothness >= smoothBandRisk && a.SignificantColors > 2 {
		switch depth {
		case Depth1:
			depth = Depth2
		case Depth2:
			depth = Depth4
		}
	}

	// Clamp to the caller's ceiling. When an image genuinely needs more colors
	// than the cap allows (e.g. a photo thumbnail capped at Depth2), it renders at
	// the cap — acceptable degradation for a small preview, and the whole point of
	// capping thumbnails so they never over-allocate at 16 colors.
	if depth > maxDepth {
		depth = maxDepth
	}
	return depth
}

func downsampleForAnalysis(src image.Image) image.Image {
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	if sw <= analyzeMaxDim && sh <= analyzeMaxDim {
		return src
	}
	scale := 1.0
	if sw >= sh {
		scale = float64(analyzeMaxDim) / float64(sw)
	} else {
		scale = float64(analyzeMaxDim) / float64(sh)
	}
	dw := int(float64(sw)*scale + 0.5)
	dh := int(float64(sh)*scale + 0.5)
	if dw < 1 {
		dw = 1
	}
	if dh < 1 {
		dh = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	// Nearest-neighbor is fine (and fast) for analysis: we want a representative
	// color sample, not a pretty thumbnail, and NN avoids inventing blended colors
	// that would inflate the bucket count.
	draw.NearestNeighbor.Scale(dst, dst.Bounds(), src, b, draw.Over, nil)
	return dst
}

func toGColor8(r, g, b uint32) byte {
	r2 := uint8(r >> 14)
	g2 := uint8(g >> 14)
	b2 := uint8(b >> 14)
	return 0xC0 | (r2 << 4) | (g2 << 2) | b2
}

// ---- palette building -------------------------------------------------------

// buildPalette picks up to size (2,4,16) palette entries by MEDIAN CUT over the
// image's GColor8 histogram (Heckbert's algorithm): treat the used colors as a
// box in color space, repeatedly split the box with the largest spread, and take
// each final box's population-weighted average as a palette entry.
//
// This replaced a frequency sort (take the N most common colors), which failed
// badly on the images that matter most. Dithering can only mix colors that are
// IN the palette — it cannot invent one — so palette CONTENT decides everything.
// On a dark photo the 16 most COMMON colors are 16 near-identical dark blue-greys:
// no midtone exists, so every mid-grey has to be approximated by spatially mixing
// near-black with whatever lone bright entry survived the sort. That's the "hard
// black with cyan speckle" failure: banded shadows, no smooth ramp.
//
// Median cut instead spreads the same N slots ACROSS the range. Splitting by
// spread (not by count) guarantees distant colors get their own boxes, and the
// per-box AVERAGE lands on colors that may be nowhere near the most common —
// which is exactly where the missing midtones come from. Both the box choice and
// the split point are population-weighted, so a large flat region doesn't lose a
// slot to a handful of stray pixels.
//
// The result always has exactly size entries (unused slots repeat the last real
// one), matching the old contract.
func buildPalette(g8 []byte, size int) []byte {
	// Histogram over the 64-color GColor8 space (2 bits/channel).
	counts := make(map[byte]int, 64)
	for _, v := range g8 {
		counts[v]++
	}
	if len(counts) == 0 {
		pal := make([]byte, size)
		for i := range pal {
			pal[i] = 0xC0
		}
		return pal
	}

	type pcol struct {
		r, g, b int // 0..3 per channel (GColor8 space)
		count   int
	}
	initial := make([]pcol, 0, len(counts))
	for v, c := range counts {
		initial = append(initial, pcol{
			r: int((v >> 4) & 3), g: int((v >> 2) & 3), b: int(v & 3), count: c,
		})
	}
	// Deterministic starting order (map iteration is randomized in Go; without
	// this the same image could encode to different palettes run to run).
	sort.Slice(initial, func(i, j int) bool {
		a, b := initial[i], initial[j]
		if a.r != b.r {
			return a.r < b.r
		}
		if a.g != b.g {
			return a.g < b.g
		}
		return a.b < b.b
	})

	population := func(box []pcol) int {
		n := 0
		for _, c := range box {
			n += c.count
		}
		return n
	}
	// extent returns the per-channel range of a box.
	extent := func(box []pcol) (int, int, int) {
		rl, rh, gl, gh, bl, bh := 3, 0, 3, 0, 3, 0
		for _, c := range box {
			if c.r < rl {
				rl = c.r
			}
			if c.r > rh {
				rh = c.r
			}
			if c.g < gl {
				gl = c.g
			}
			if c.g > gh {
				gh = c.g
			}
			if c.b < bl {
				bl = c.b
			}
			if c.b > bh {
				bh = c.b
			}
		}
		return rh - rl, gh - gl, bh - bl
	}
	spread := func(box []pcol) int {
		dr, dg, db := extent(box)
		m := dr
		if dg > m {
			m = dg
		}
		if db > m {
			m = db
		}
		return m
	}
	longestAxis := func(box []pcol) int {
		dr, dg, db := extent(box)
		// Tie-break toward green, then red: matches the 3:6:1 perceptual weighting
		// used by gcolorDist/rgbDist elsewhere in this file.
		if dg >= dr && dg >= db {
			return 1
		}
		if dr >= db {
			return 0
		}
		return 2
	}

	boxes := [][]pcol{initial}
	for len(boxes) < size {
		// Split the box with the largest spread x population. Spread alone would
		// chase a two-pixel gradient; population alone would keep subdividing the
		// dominant flat region. The product is the standard compromise.
		bi, bestScore := -1, 0
		for i, box := range boxes {
			if len(box) < 2 {
				continue
			}
			s := spread(box) * population(box)
			if bi == -1 || s > bestScore {
				bi, bestScore = i, s
			}
		}
		if bi == -1 {
			break // every box is a single color: nothing left to split
		}
		box := boxes[bi]
		axis := longestAxis(box)
		sort.SliceStable(box, func(i, j int) bool {
			switch axis {
			case 0:
				return box[i].r < box[j].r
			case 1:
				return box[i].g < box[j].g
			default:
				return box[i].b < box[j].b
			}
		})
		// Weighted median: split where half the box's PIXELS lie, keeping both
		// halves non-empty.
		half := population(box) / 2
		acc, mid := 0, 1
		for i := range box {
			acc += box[i].count
			if acc > half {
				mid = i + 1
				break
			}
		}
		if mid < 1 {
			mid = 1
		}
		if mid > len(box)-1 {
			mid = len(box) - 1
		}
		boxes[bi] = box[:mid]
		boxes = append(boxes, box[mid:])
	}

	// Each box contributes its population-weighted average, snapped back to
	// GColor8. This is where midtones the frequency sort never saw come from.
	roundDiv := func(sum, n int) int {
		if n <= 0 {
			return 0
		}
		v := (sum*2 + n) / (n * 2)
		if v < 0 {
			v = 0
		}
		if v > 3 {
			v = 3
		}
		return v
	}
	entries := make([]byte, 0, size)
	seen := make(map[byte]bool, size)
	for _, box := range boxes {
		if len(box) == 0 {
			continue
		}
		sr, sg, sb, sn := 0, 0, 0, 0
		for _, c := range box {
			sr += c.r * c.count
			sg += c.g * c.count
			sb += c.b * c.count
			sn += c.count
		}
		v := byte(0xC0 | roundDiv(sr, sn)<<4 | roundDiv(sg, sn)<<2 | roundDiv(sb, sn))
		if !seen[v] {
			seen[v] = true
			entries = append(entries, v)
		}
	}
	// Two boxes can average to the same GColor8 (the space is only 64 wide). Backfill
	// from the most common unused colors so we never waste a slot.
	if len(entries) < size {
		type vc struct {
			v byte
			c int
		}
		arr := make([]vc, 0, len(counts))
		for v, c := range counts {
			arr = append(arr, vc{v, c})
		}
		sort.Slice(arr, func(i, j int) bool {
			if arr[i].c != arr[j].c {
				return arr[i].c > arr[j].c
			}
			return arr[i].v < arr[j].v // deterministic
		})
		for _, e := range arr {
			if len(entries) >= size {
				break
			}
			if !seen[e.v] {
				seen[e.v] = true
				entries = append(entries, e.v)
			}
		}
	}

	pal := make([]byte, size)
	n := len(entries)
	if n > size {
		n = size
	}
	copy(pal[:n], entries[:n])
	last := byte(0xC0)
	if n > 0 {
		last = entries[n-1]
	}
	for i := n; i < size; i++ {
		pal[i] = last
	}
	return pal
}

// ---- encoding ---------------------------------------------------------------

// Options controls how EncodeImageForWatch sizes and encodes an image.
type Options struct {
	// MaxW, MaxH: bounding box. In heap-budget mode (MaxBytes>0) MaxH is ignored
	// and width is capped at MaxW; otherwise the image fits within MaxW×MaxH.
	MaxW, MaxH int
	// MaxBytes: watch heap budget in bytes. >0 selects enhanced-view sizing
	// (width cap + shrink to fit budget); 0 selects chat-preview box fit.
	MaxBytes int
	// Palettize: if false, encode full-color 8bpp (Format 0). If true, palettize.
	Palettize bool
	// AutoDepth: when palettizing, analyze the image and pick the smallest bit
	// depth (1/2/4) that renders it without visible loss. When false, always 4bpp
	// (the historical behavior). Ignored when Palettize is false.
	AutoDepth bool
	// MaxDepth caps the depth AutoDepth may choose. Depth2 for thumbnails (choose
	// 2 or 4 colors, never 16 — keeps churny, dynamically-allocated thumbnails as
	// cheap as possible); Depth4 (or zero) for the enhanced view (16/4/2). Only
	// meaningful with AutoDepth; without it the depth is fixed at 4bpp regardless.
	MaxDepth BitDepth
	// Coverage is the fraction of pixels (by color area) the significant-color
	// count must cover before the tail of rarest colors is dropped (see
	// AnalyzeColors). Lower = drop more rare colors = more images fall to a lower
	// depth. Thumbnails can afford a looser value than the full view. 0 falls back
	// to the default (coverageTarget). Only meaningful with AutoDepth.
	Coverage float64
	// CompressFit: size the image so its ROW-COMPRESSED estimate fits MaxBytes
	// (rather than the raw packed size). Lets the enhanced view send a LARGER image
	// when it will compress — the phone applies the same compression, and the
	// estimate here is byte-exact with it, so the compressed result is guaranteed to
	// fit MaxBytes. Only meaningful in enhanced mode (MaxBytes>0, Palettize). When
	// off, MaxBytes bounds the raw size as before.
	CompressFit bool
}

// autoDepthDefault gates the variable-bit-depth feature for the backward-compatible
// EncodeImageForWatch entry point. It stays FALSE until the watch renderer can read
// the result's "depth" field and unpack 1/2/4 bpp — otherwise the watch (which
// assumes 4bpp) would misrender a lower-depth image. Flip to true in lockstep with
// the watch update. The clean Encode(...) API is unaffected; it honors Options as
// passed.
const autoDepthDefault = true

// EncodeImageForWatch is the backward-compatible entry point matching MirrorMsg's
// original signature: quality==1 selects 16-color palettization (4bpp), any other
// value selects full-color 8bpp. It preserves the exact historical behavior while
// the variable-depth feature is gated by autoDepthDefault. New callers should
// prefer Encode(rawBytes, Options{...}) for explicit control.
// Per-path coverage thresholds (the fraction of pixels, by color area, the
// significant-color count must cover before the rarest colors are dropped — see
// AnalyzeColors). Lower = more images fall to a lower depth. Thumbnails are tiny
// and churny, so they run looser (drop more rare colors, land at lower depth more
// often); the full view stays tighter to protect the image the user opened to
// look at. These DEFAULTS can be overridden at runtime via SetConfig — the app's
// Image Quality settings call it so the user can tune without a rebuild.
const (
	defaultThumbCoverage = 0.60 // thumbnail / chat-preview path (maxBytes == 0)
	defaultFullCoverage  = 0.90 // enhanced / full-size path (maxBytes > 0)
	defaultThumbCap      = 2    // Depth2 = 4 colors max for thumbnails
	defaultFullCap       = 4    // Depth4 = 16 colors max for the full view
)

// Live, runtime-tunable image-quality config. Package-level so the gomobile-bound
// EncodeImageForWatch entry point can read it without threading four extra params
// through every bridge call site. Set once at app startup and on every settings
// change via SetConfig. (This is the one bit of mutable package state in the
// library; kept minimal and guarded so the encoder stays otherwise stateless.)
var (
	cfgThumbCoverage = defaultThumbCoverage
	cfgFullCoverage  = defaultFullCoverage
	cfgThumbCap      = BitDepth(defaultThumbCap)
	cfgFullCap       = BitDepth(defaultFullCap)
	cfgCompressFit   = false // enhanced view sizes for compressed budget when true
)

// SetConfig updates the runtime image-quality dials. Coverages are fractions in
// (0,1]; caps are a color COUNT (2, 4, or 16) which map to depth 1/2/4. Any value
// out of range is clamped to a safe default so a bad setting can't break encoding.
// Called from Kotlin at startup (from SharedPreferences) and whenever a dial moves.
func SetConfig(thumbCoverage, fullCoverage float64, thumbCapColors, fullCapColors int, compressFit bool, dither bool, ditherEdge, ditherStrength, ditherIntensity int) {
	cfgThumbCoverage = clampCoverage(thumbCoverage, defaultThumbCoverage)
	cfgFullCoverage = clampCoverage(fullCoverage, defaultFullCoverage)
	cfgThumbCap = capColorsToDepth(thumbCapColors, BitDepth(defaultThumbCap))
	cfgFullCap = capColorsToDepth(fullCapColors, BitDepth(defaultFullCap))
	cfgCompressFit = compressFit
	cfgDither = dither
	if ditherEdge > 0 {
		cfgDitherEdge = ditherEdge
	}
	// >= 0 (not > 0): 0 is meaningful now — it means "dither the whole image",
	// which is how the strength slider reaches its maximum.
	if ditherStrength >= 0 {
		cfgDitherStrength = ditherStrength
	}
	if ditherIntensity >= 0 && ditherIntensity <= 100 {
		cfgDitherIntensity = ditherIntensity
	}
}

func clampCoverage(v, def float64) float64 {
	if v <= 0 || v > 1 {
		return def
	}
	return v
}

// capColorsToDepth maps a color count (2/4/16) to a palette depth (1/2/4). Anything
// else falls back to def — the watch only renders depths 1/2/4, so we never let a
// setting request an unsupported depth.
func capColorsToDepth(colors int, def BitDepth) BitDepth {
	switch colors {
	case 2:
		return Depth1
	case 4:
		return Depth2
	case 16:
		return Depth4
	default:
		return def
	}
}

func EncodeImageForWatch(rawBytes []byte, maxW, maxH, maxBytes, quality int) (string, error) {
	// maxBytes distinguishes the two callers: ==0 is a thumbnail/chat preview
	// (churny, dynamically allocated); >0 is the enhanced view (spends any freed
	// budget on a LARGER image). Cap + coverage come from the live config so the
	// user's Image Quality settings apply without a rebuild.
	maxDepth := cfgFullCap
	coverage := cfgFullCoverage
	if maxBytes == 0 {
		maxDepth = cfgThumbCap
		coverage = cfgThumbCoverage
	}
	return Encode(rawBytes, Options{
		MaxW:      maxW,
		MaxH:      maxH,
		MaxBytes:  maxBytes,
		Palettize: quality == 1,
		AutoDepth: autoDepthDefault,
		MaxDepth:  maxDepth,
		Coverage:  coverage,
		// Enhanced frames (maxBytes>0) size for the COMPRESSED budget when the user
		// enabled compression — so a compressible image comes through bigger, its
		// compressed size guaranteed to fit maxBytes. Thumbnails never CompressFit.
		CompressFit: cfgCompressFit && maxBytes > 0,
	})
}

// Encode decodes raw image bytes, scales to fit the requested bounding box / heap
// budget, quantizes to the Pebble GColor8 space, and packs the result. Returns the
// WatchImageResult as a JSON string.
//
// With Options.Palettize and Options.AutoDepth, it runs the color analyzer and
// packs at the smallest lossless-enough depth (1/2/4 bpp), shrinking watch heap
// use for simple images. Without AutoDepth it always uses 4bpp (16 colors).
func Encode(rawBytes []byte, opts Options) (string, error) {
	src, _, err := image.Decode(bytes.NewReader(rawBytes))
	if err != nil {
		return "", fmt.Errorf("image.Decode: %w", err)
	}

	// The budget math depends on bytes-per-pixel, which depends on the depth we'll
	// use. For AutoDepth we must know the depth BEFORE sizing (a 1bpp image can be
	// larger in pixels for the same heap budget). So analyze first when needed.
	depth := Depth4
	if opts.Palettize && opts.AutoDepth {
		depth = AnalyzeColors(src, opts.MaxDepth, opts.Coverage).RecommendedDepth
	} else if !opts.Palettize {
		depth = Depth8
	}

	dstW, dstH := sizeFor(src.Bounds().Dx(), src.Bounds().Dy(), opts, depth)

	// CompressFit (enhanced only): a compressible image can be bigger than its
	// raw-fit size for the same budget. Start at the raw-fit size (fits even when
	// it doesn't compress, because the phone then sends it raw) and grow BOTH
	// sides together toward the natural size while the compressed estimate stays
	// under budget, so the picture keeps its shape. Source is decoded once; each
	// trial only re-scales+quantizes in memory. The estimate is byte-exact with
	// the phone's compressor, so the result is guaranteed to fit.
	estCompressed := 0
	if opts.CompressFit && opts.Palettize && opts.MaxBytes > 0 {
		dstW, dstH, estCompressed = fitCompressed(src, opts, depth, dstW, dstH)
	}

	dst := image.NewRGBA(image.Rect(0, 0, dstW, dstH))
	draw.BiLinear.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Over, nil)

	// Quantize every pixel to a GColor8 byte.
	g8 := make([]byte, dstW*dstH)
	for y := 0; y < dstH; y++ {
		for x := 0; x < dstW; x++ {
			r, g, bb, _ := dst.At(x, y).RGBA()
			g8[y*dstW+x] = toGColor8(r, g, bb)
		}
	}

	var result WatchImageResult
	if !opts.Palettize {
		// Full color (8bpp): the GColor8 bytes are the pixels.
		result = WatchImageResult{
			Width:  dstW,
			Height: dstH,
			Pixels: base64.StdEncoding.EncodeToString(g8),
			Format: 0,
			Depth:  int(Depth8),
		}
	} else {
		palSize := 1 << int(depth) // 2,4,16
		palette := buildPalette(g8, palSize)
		// Adaptive dithering (opt-in): reconstruct smooth gradients against the
		// selected palette from the CONTINUOUS image, in place. Flat/edge regions
		// stay crisp (and compressible). Applies to full and thumbnail frames.
		if cfgDither {
			g8 = adaptiveDitherRGB(dst, dstW, dstH, palette,
				cfgDitherEdge, cfgDitherStrength, cfgDitherIntensity)
		}
		result = packIndexed(g8, dstW, dstH, palette, depth)
		if estCompressed > 0 {
			result.EstCompressed = estCompressed
		}
	}

	out, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(out), nil
}

// ---- CompressFit: byte-exact compressed-size estimator ---------------------
// These mirror the phone's Kotlin row-compressor EXACTLY (tight rows, PackBits,
// prev-row-repeat, restart rows never repeat, N=8). Both are pinned to the same
// reference, so estimateCompressed here == the phone's actual compressed size to
// the byte. If you change one, change the other in lockstep.

const restartN = 8

// packTightIdx packs already-resolved palette indices MSB-first at TIGHT stride
// (ceil(w*depth/8), no word align) — the layout the phone compresses.
func packTightIdx(idx []byte, w, h int, depth BitDepth) ([]byte, int) {
	bpp := int(depth)
	ppb := 8 / bpp
	rowBytes := (w + ppb - 1) / ppb
	out := make([]byte, rowBytes*h)
	mask := byte((1 << bpp) - 1)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			v := idx[y*w+x] & mask
			bi := y*rowBytes + x/ppb
			slot := x % ppb
			shift := 8 - bpp*(slot+1)
			out[bi] |= v << uint(shift)
		}
	}
	return out, rowBytes
}

func rowCompressSize(tight []byte, rowBytes, h int) int {
	total := 0
	prevStart := -1
	for y := 0; y < h; y++ {
		start := y * rowBytes
		isRestart := (y % restartN) == 0
		repeat := !isRestart && prevStart >= 0 && rowEqAt(tight, start, prevStart, rowBytes)
		if repeat {
			total++
		} else {
			total += packBitsRowSize(tight, start, rowBytes)
		}
		prevStart = start
	}
	return total
}

func rowEqAt(a []byte, o1, o2, n int) bool {
	for i := 0; i < n; i++ {
		if a[o1+i] != a[o2+i] {
			return false
		}
	}
	return true
}

func packBitsRowSize(row []byte, start, n int) int {
	total := 0
	i := 0
	for i < n {
		runLen := 1
		for i+runLen < n && row[start+i+runLen] == row[start+i] && runLen < 127 {
			runLen++
		}
		if runLen >= 2 {
			total += 2
			i += runLen
		} else {
			litLen := 1
			for i+litLen < n && litLen < 128 {
				if i+litLen+1 < n && row[start+i+litLen] == row[start+i+litLen+1] {
					break
				}
				litLen++
			}
			total += 1 + litLen
			i += litLen
		}
	}
	return total
}

// estimateCompressed: resolve g8 to palette indices, tight-pack, and return the
// byte-exact row-compressed size — what the phone will produce.
func estimateCompressed(g8 []byte, w, h int, palette []byte, depth BitDepth) int {
	idx := make([]byte, len(g8))
	cache := make(map[byte]uint8, 64)
	for i, v := range g8 {
		if c, ok := cache[v]; ok {
			idx[i] = c
			continue
		}
		best, bestD := 0, 1<<30
		for pi, pv := range palette {
			if d := gcolorDist(v, pv); d < bestD {
				bestD = d
				best = pi
			}
		}
		cache[v] = uint8(best)
		idx[i] = uint8(best)
	}
	tight, rb := packTightIdx(idx, w, h, depth)
	return rowCompressSize(tight, rb, h)
}

// ---- adaptive dithering (opt-in) -------------------------------------------
// Dither ONLY smooth-gradient regions, working from the CONTINUOUS scaled image
// (before the 64-colour snap) toward the selected N-colour palette, with channel-
// wise Floyd-Steinberg error diffusion. This is what "simulates more colours":
// gradients the palette can't hold flatly are approximated by a spatial mix of
// palette colours. Classification (flat/edge/gradient) runs on the continuous
// image so smooth gradients are seen; error diffuses only among gradient pixels so
// hard edges never stipple and flat fills stay clean (and compressible).
// Phone-side only, zero watch cost. Returns g8 (GColor8 bytes) with gradient
// regions dithered; flat/edge regions get the plain nearest-palette snap.
//
// Three user sliders: cfgDitherEdge (edge protection — a concentrated colour jump
// >= this is an edge, left crisp), cfgDitherStrength (a spread colour change >=
// this over a 5px span is a gradient worth dithering), cfgDitherIntensity (0..100
// scale on diffused error — lower = softer, cleaner, more compressible).
var (
	// cfgDither is ON by default: without error diffusion a 16-colour palette
	// shows hard banding on any photographic gradient. The intensity slider
	// remains the compression trade-off knob.
	cfgDither          = true
	cfgDitherEdge      = 50000 // squared weighted-RGB: concentrated step >= this = edge
	cfgDitherStrength  = 1500  // squared weighted-RGB: span change >= this = gradient
	cfgDitherIntensity = 100   // 0..100 error-diffusion scale
)

// rgbDist: squared weighted distance, 3:6:1 R:G:B (matches gcolorDist weighting).
func rgbDist(ar, ag, ab, br, bg, bb int) int {
	dr, dg, db := ar-br, ag-bg, ab-bb
	return 3*dr*dr + 6*dg*dg + 1*db*db
}

// g8ToRGB unpacks a GColor8 byte (0xC0|rr gg bb, 2 bits/channel) to 0..255 RGB.
func g8ToRGB(b byte) (int, int, int) {
	b &= 0x3F
	return int((b>>4)&3) * 85, int((b>>2)&3) * 85, int(b&3) * 85
}

// adaptiveDitherRGB reads the continuous RGBA image, dithers toward palette (a
// slice of GColor8 bytes), and returns the GColor8 result bytes (dstW*dstH).
func adaptiveDitherRGB(dst *image.RGBA, w, h int, palette []byte, edgeThresh, flatThresh, intensity int) []byte {
	// pull continuous RGB into flat arrays (0..255 per channel)
	pr := make([]int, w*h)
	pg := make([]int, w*h)
	pb := make([]int, w*h)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			r, g, b, _ := dst.At(x, y).RGBA()
			i := y*w + x
			pr[i] = int(r >> 8)
			pg[i] = int(g >> 8)
			pb[i] = int(b >> 8)
		}
	}
	// palette in RGB
	pal := make([][3]int, len(palette))
	for i, pv := range palette {
		r, g, b := g8ToRGB(pv)
		pal[i] = [3]int{r, g, b}
	}
	// classify: 0 flat, 1 edge, 2 gradient — span change vs max concentrated step
	// over a 5-wide window, horizontally and vertically.
	cls := make([]byte, w*h)
	spanStep := func(get func(k int) (int, int, int), i, n int) (int, int) {
		lo, hi := i-2, i+2
		if lo < 0 {
			lo = 0
		}
		if hi > n-1 {
			hi = n - 1
		}
		lr, lg, lb := get(lo)
		hr, hg, hb := get(hi)
		span := rgbDist(lr, lg, lb, hr, hg, hb)
		mx := 0
		for k := lo; k < hi; k++ {
			ar, ag, ab := get(k)
			br, bg, bb := get(k + 1)
			if s := rgbDist(ar, ag, ab, br, bg, bb); s > mx {
				mx = s
			}
		}
		return span, mx
	}
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			sh, mh := spanStep(func(k int) (int, int, int) { j := y*w + k; return pr[j], pg[j], pb[j] }, x, w)
			sv, mv := spanStep(func(k int) (int, int, int) { j := k*w + x; return pr[j], pg[j], pb[j] }, y, h)
			span, step := sh, mh
			if sv > span {
				span = sv
			}
			if mv > step {
				step = mv
			}
			idx := y*w + x
			// flatThresh <= 0 means "dither the WHOLE image": every pixel is a
			// gradient pixel, no edge protection, no flat-region exclusion — the
			// unconditional whole-image Floyd-Steinberg the sliders could never
			// reach before (the gradient threshold used to floor at 200 and edge
			// pixels were excluded from diffusion entirely, so flat shadow regions
			// always got a plain snap no matter where the sliders sat).
			if flatThresh <= 0 {
				cls[idx] = 2
			} else if step >= edgeThresh {
				cls[idx] = 1
			} else if span >= flatThresh {
				cls[idx] = 2
			} else {
				cls[idx] = 0
			}
		}
	}
	// channel-wise Floyd-Steinberg, diffusion confined to gradient pixels.
	wr := make([]float64, w*h)
	wg := make([]float64, w*h)
	wb := make([]float64, w*h)
	for i := range pr {
		wr[i] = float64(pr[i])
		wg[i] = float64(pg[i])
		wb[i] = float64(pb[i])
	}
	k := float64(intensity) / 100.0
	clamp := func(v float64) int {
		if v < 0 {
			return 0
		}
		if v > 255 {
			return 255
		}
		return int(v + 0.5)
	}
	nearest := func(r, g, b int) int {
		best, bd := 0, 1<<62
		for i, pc := range pal {
			if d := rgbDist(r, g, b, pc[0], pc[1], pc[2]); d < bd {
				bd = d
				best = i
			}
		}
		return best
	}
	// map palette index back to its GColor8 byte
	out := make([]byte, w*h)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			idx := y*w + x
			r, g, b := clamp(wr[idx]), clamp(wg[idx]), clamp(wb[idx])
			pi := nearest(r, g, b)
			out[idx] = palette[pi]
			if cls[idx] == 2 {
				er := (float64(r) - float64(pal[pi][0])) * k
				eg := (float64(g) - float64(pal[pi][1])) * k
				eb := (float64(b) - float64(pal[pi][2])) * k
				diffuse := func(nx, ny int, wt float64) {
					if nx < 0 || nx >= w || ny < 0 || ny >= h {
						return
					}
					j := ny*w + nx
					if cls[j] != 2 {
						return
					}
					wr[j] += er * wt
					wg[j] += eg * wt
					wb[j] += eb * wt
				}
				diffuse(x+1, y, 7.0/16)
				diffuse(x-1, y+1, 3.0/16)
				diffuse(x, y+1, 5.0/16)
				diffuse(x+1, y+1, 1.0/16)
			}
		}
	}
	return out
}

// packIndexed maps each pixel to its nearest palette index and packs at the given
// depth (1/2/4 bpp), MSB-first within each byte. Returns the assembled result.
func packIndexed(g8 []byte, w, h int, palette []byte, depth BitDepth) WatchImageResult {
	// value -> nearest palette index, cached (there are ≤64 distinct values).
	idxCache := make(map[byte]uint8, 64)
	nibbleFor := func(v byte) uint8 {
		if i, ok := idxCache[v]; ok {
			return i
		}
		best, bestD := 0, 1<<30
		for i, pv := range palette {
			if d := gcolorDist(v, pv); d < bestD {
				bestD = d
				best = i
			}
		}
		idxCache[v] = uint8(best)
		return uint8(best)
	}

	bpp := int(depth)
	perByte := 8 / bpp // pixels packed per byte: 8,4,2
	// Pebble's palettized bitmap formats (1/2/4BitPalette) mandate each row be a
	// multiple of 4 bytes (32-bit word) — gbitmap's row_size_bytes rounds up to 4.
	// So we emit rows at that same word-aligned stride; otherwise a width whose
	// tight stride isn't a multiple of 4 shears diagonally on the watch. The extra
	// bytes per row (≤3) are left as zero padding at the row's tail.
	tightRow := (w + perByte - 1) / perByte
	rowBytes := (tightRow + 3) &^ 3 // round up to a multiple of 4
	packed := make([]byte, rowBytes*h)
	mask := byte((1 << bpp) - 1)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			idx := nibbleFor(g8[y*w+x]) & mask
			bi := y*rowBytes + x/perByte
			// MSB-first: the first pixel in a byte occupies the high bits.
			slot := x % perByte
			shift := 8 - bpp*(slot+1)
			packed[bi] |= idx << uint(shift)
		}
	}
	return WatchImageResult{
		Width:   w,
		Height:  h,
		Pixels:  base64.StdEncoding.EncodeToString(packed),
		Format:  1,
		Palette: base64.StdEncoding.EncodeToString(palette),
		Depth:   int(depth),
	}
}

// sizeFor computes destination dimensions given the sizing options and the packed
// bit depth (which sets bytes-per-pixel for the heap-budget mode).
func sizeFor(srcW, srcH int, opts Options, depth BitDepth) (int, int) {
	var dstW, dstH int
	if opts.MaxBytes > 0 {
		// Heap-budget (enhanced) mode: width cap, height free, then shrink to fit
		// the byte budget. Pixels-per-byte depends on depth: 8bpp→1, 4bpp→2,
		// 2bpp→4, 1bpp→8. A lower depth fits MORE pixels in the same budget.
		scale := 1.0
		if srcW > opts.MaxW {
			scale = float64(opts.MaxW) / float64(srcW)
		}
		dstW = int(float64(srcW)*scale + 0.5)
		dstH = int(float64(srcH)*scale + 0.5)
		pixelsPerByte := 8 / int(depth)
		maxPixels := opts.MaxBytes * pixelsPerByte
		if maxPixels > 0 && dstW*dstH > maxPixels {
			k := math.Sqrt(float64(maxPixels) / float64(dstW*dstH))
			dstW = int(float64(dstW)*k + 0.5)
			dstH = int(float64(dstH)*k + 0.5)
		}
	} else {
		// Classic box fit (chat preview): fit within MaxW×MaxH, preserve AR, never
		// upscale.
		scale := 1.0
		if float64(srcW)/float64(opts.MaxW) > float64(srcH)/float64(opts.MaxH) {
			scale = float64(opts.MaxW) / float64(srcW)
		} else {
			scale = float64(opts.MaxH) / float64(srcH)
		}
		if scale > 1.0 {
			scale = 1.0
		}
		dstW = int(float64(srcW)*scale + 0.5)
		dstH = int(float64(srcH)*scale + 0.5)
	}
	if dstW < 1 {
		dstW = 1
	}
	if dstH < 1 {
		dstH = 1
	}
	return dstW, dstH
}

// fitCompressed sizes the enhanced image so its COMPRESSED estimate fits
// opts.MaxBytes, keeping the image's shape: width and height scale together.
// It takes the natural size (width capped at MaxW) when that fits; otherwise it
// binary-searches the width between the raw-fit size (rawW x rawH, from sizeFor)
// and the natural width, the height following the aspect ratio, for the largest
// size whose compressed estimate is <= MaxBytes. Because compression is
// content-dependent, each trial is measured, not predicted. Returns (w, h,
// estCompressedAtThatSize); the estimate is 0 when no size above the raw fit
// compresses under budget and the raw-fit size is returned (the phone then
// sends it raw, which fits). Never smaller than the raw fit, so compression
// never makes a picture smaller than it would be without it.
//
// (It used to keep the full width and shrink only the height, which squashed
// any picture that didn't fit at its natural size.)
func fitCompressed(src image.Image, opts Options, depth BitDepth, rawW, rawH int) (int, int, int) {
	sw, sh := src.Bounds().Dx(), src.Bounds().Dy()
	scale := 1.0
	if sw > opts.MaxW {
		scale = float64(opts.MaxW) / float64(sw)
	}
	fullW := int(float64(sw)*scale + 0.5)
	fullH := int(float64(sh)*scale + 0.5)
	if fullW < 1 {
		fullW = 1
	}
	if fullH < 1 {
		fullH = 1
	}
	palSize := 1 << int(depth)

	// height for a trial width, at the natural aspect ratio.
	hFor := func(w int) int {
		if w >= fullW {
			return fullH
		}
		h := int(float64(fullH)*float64(w)/float64(fullW) + 0.5)
		if h < 1 {
			h = 1
		}
		return h
	}

	// estimate compressed size at a given size.
	estAt := func(w, h int) int {
		dst := image.NewRGBA(image.Rect(0, 0, w, h))
		draw.BiLinear.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Over, nil)
		g8 := make([]byte, w*h)
		for y := 0; y < h; y++ {
			for x := 0; x < w; x++ {
				r, g, bb, _ := dst.At(x, y).RGBA()
				g8[y*w+x] = toGColor8(r, g, bb)
			}
		}
		pal := buildPalette(g8, palSize)
		// If dithering is on, it runs before packing in the real encode and ADDS
		// bytes in gradient regions — so the fit estimate must dither too (identically,
		// from the same continuous dst), or we'd size for the undithered (smaller)
		// size and overflow once dithered. Keeps the guaranteed one-pass fit.
		if cfgDither {
			g8 = adaptiveDitherRGB(dst, w, h, pal,
				cfgDitherEdge, cfgDitherStrength, cfgDitherIntensity)
		}
		return estimateCompressed(g8, w, h, pal, depth)
	}

	// If the full natural image already fits, take it — no shrink needed.
	if e := estAt(fullW, fullH); e <= opts.MaxBytes {
		return fullW, fullH, e
	}
	// Otherwise binary-search the largest width above the raw fit that fits.
	if rawW < 1 {
		rawW = 1
	}
	if rawH < 1 {
		rawH = 1
	}
	bestW, bestH, bestE := rawW, rawH, 0
	lo, hi := rawW+1, fullW-1
	for lo <= hi {
		mid := (lo + hi) / 2
		h := hFor(mid)
		e := estAt(mid, h)
		if e <= opts.MaxBytes {
			bestW, bestH, bestE = mid, h, e
			lo = mid + 1
		} else {
			hi = mid - 1
		}
	}
	return bestW, bestH, bestE
}
