# watchimage

**A self-contained Go image pipeline for rendering photos on tiny, memory-starved
displays — built for the Pebble (Basalt) but usable anywhere you need to fit a
real image into a strict color and memory budget.**

`watchimage` takes any ordinary image (PNG, JPEG, GIF, …) and produces a compact,
palettized bitmap tuned for a display that has very few colors and very little
RAM. It was extracted from [MirrorMsg](https://github.com/killdano/mirrormsg) into
its own package so it can be reused. It depends only on the Go standard library
plus `golang.org/x/image` — no cgo, no platform SDK, no network.

If you are targeting a Pebble, an e-paper badge, an ESP32 panel, a retro handheld,
or any low-color/low-RAM screen, this does the hard part: quantization, dithering,
bit-depth reduction, and compression-aware sizing.

---

## What it does

The pipeline, in order:

1. **Decode** the source image (anything `image.Decode` supports).
2. **Scale** it to fit a bounding box or a memory budget.
3. **Analyze colors** and choose the smallest bit depth (1, 2, or 4 bits/pixel)
   that renders the image without visible loss — a mostly-flat screenshot might
   only need 2 colors, a photo needs 16.
4. **Build a palette** from the display's real hardware colors (for Pebble, the
   64-color GColor8 space — it never picks a color the panel can't show).
5. **Dither** (optional) — adaptive, edge-aware error diffusion that smooths
   *genuine gradients only*, leaving flat fills and hard edges crisp. This lets a
   4-color image look like it has far more colors, without turning solid areas or
   text into noise.
6. **Pack** the pixels into 1/2/4/8-bpp bytes.
7. **Compress** (optional, via the estimator + the sizing mode) — a row-based
   codec (PackBits + previous-row-repeat + a restart index for random access)
   designed so the display can store the image *compressed in RAM* and expand one
   scanline at a time at draw time. `watchimage` sizes the image so its
   *compressed* size fits your memory budget, in a single pass.

Everything after decode is deterministic and dependency-free.

---

## Install

```
go get github.com/killdano/mirrormsg/watchimage
```

```go
import "github.com/killdano/mirrormsg/watchimage"
```

It's a standalone Go module (its own `go.mod`), depending only on the standard
library and `golang.org/x/image`. You can also just copy `watchimage.go` into your
own module if you'd rather vendor it — it's a single file.

---

## Quick start

```go
package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"

	"github.com/killdano/mirrormsg/watchimage"
)

func main() {
	raw, _ := os.ReadFile("photo.jpg")

	// Encode with explicit options.
	out, err := watchimage.Encode(raw, watchimage.Options{
		MaxW:      144,               // Pebble width
		MaxBytes:  8000,              // memory budget in bytes
		Palettize: true,             // produce an indexed image
		AutoDepth: true,             // pick 1/2/4 bpp automatically
		MaxDepth:  watchimage.Depth4, // never exceed 16 colors
	})
	if err != nil {
		panic(err)
	}

	var res watchimage.WatchImageResult
	json.Unmarshal([]byte(out), &res)

	pixels, _ := base64.StdEncoding.DecodeString(res.Pixels)
	fmt.Printf("%dx%d, depth=%d, %d packed bytes\n",
		res.Width, res.Height, res.Depth, len(pixels))
	// res.Palette holds the base64 GColor8 palette when Format == 1.
}
```

`Encode` returns a **JSON string** (this keeps the API trivially bindable across
language boundaries — see *Using it from Android* below). Unmarshal it into
`WatchImageResult`.

---

## The result

```go
type WatchImageResult struct {
	Width   int    `json:"w"`
	Height  int    `json:"h"`
	Pixels  string `json:"pixels"`  // base64: packed indices (1/2/4 bpp) or GColor8 bytes (8bpp)
	Format  int    `json:"fmt"`     // 0 = 8bpp full color, 1 = palettized
	Palette string `json:"palette"` // base64 of up to 2^Depth GColor8 bytes (palettized only)
	Depth   int    `json:"depth"`   // packed bits/pixel: 1, 2, 4, or 8

	EstCompressed int `json:"estCompressed,omitempty"` // byte-exact compressed size (CompressFit mode)
}
```

- **`Format == 1`** (palettized): `Pixels` is MSB-first packed indices at `Depth`
  bits/pixel, rows are 4-byte word-aligned, and `Palette` holds the GColor8 color
  for each index. This is the compact path.
- **`Format == 0`** (full color): `Pixels` is one GColor8 byte per pixel, no
  palette. Used when `Palettize` is false.

---

## Options

```go
type Options struct {
	MaxW, MaxH int  // bounding box (MaxH ignored when MaxBytes > 0)
	MaxBytes   int  // memory budget; >0 selects enhanced sizing, 0 selects box-fit
	Palettize  bool // true = indexed output; false = full-color 8bpp
	AutoDepth  bool // pick the smallest depth (1/2/4) that looks right
	MaxDepth   BitDepth // cap for AutoDepth: Depth2, Depth4 (0 = Depth4)
	Coverage   float64  // 0..1: how aggressively to drop rare colors (lower = fewer colors)
	CompressFit bool    // size so the COMPRESSED estimate fits MaxBytes (see below)
}
```

Bit depths: `Depth1` (2 colors), `Depth2` (4 colors), `Depth4` (16 colors),
`Depth8` (full 8bpp, non-palettized).

### Sizing modes

- **Box fit** (`MaxBytes == 0`): the image is scaled to fit `MaxW × MaxH`.
- **Budget fit** (`MaxBytes > 0`): width is capped at `MaxW`, height is sized so
  the packed image fits `MaxBytes`.
- **Compression-aware budget fit** (`MaxBytes > 0 && CompressFit`): the image
  is sized so its *compressed* size fits `MaxBytes`, width and height scaled
  together so it keeps its shape, and never smaller than the plain budget fit.
  Because compressible images shrink a lot, this sends a **much larger image**
  for the same memory.
  The compressed-size estimate is byte-exact with the row codec, so the result is
  guaranteed to fit — in a single pass, no re-encode.

---

## Dithering and color caps (global config)

Some behavior is controlled by process-global config rather than per-call
`Options`, because in MirrorMsg it's driven by user settings that apply to every
image. Set it once:

```go
watchimage.SetConfig(
	thumbCoverage,   // float64 0..1  — coverage for small/preview images
	fullCoverage,    // float64 0..1  — coverage for full-size images
	thumbCapColors,  // int 2/4/16    — max colors for previews
	fullCapColors,   // int 2/4/16    — max colors for full-size
	compressFit,     // bool          — enable compression-aware sizing
	dither,          // bool          — enable adaptive dithering
	ditherEdge,      // int           — edge-protection threshold (squared weighted-RGB)
	ditherStrength,  // int           — gradient sensitivity threshold
	ditherIntensity, // int 0..100    — error-diffusion strength
)
```

**Dithering is adaptive and content-aware.** It classifies each region as flat,
edge, or gradient (by measuring color change over a span versus a concentrated
step) and only diffuses error inside genuine gradients. Flat fills and hard edges
are left untouched, so they stay crisp *and* stay compressible — the dithering
works *with* the compressor, only touching the regions that were going to band
anyway. The three knobs:

- **edge** — how sharp a jump counts as an edge to keep crisp (higher = protect more).
- **strength** — how readily a region is treated as a ditherable gradient.
- **intensity** — how strong the dither texture is (lower = softer, more compressible).

---

## Analyzing colors directly

If you just want the color analysis (e.g. to decide a depth yourself):

```go
img, _, _ := image.Decode(f)
a := watchimage.AnalyzeColors(img, watchimage.Depth4, 0.9)
// a.ChosenDepth, a.DistinctColors, a.Coverage, ...
```

---

## Using it from Android (or any non-Go host)

`watchimage` is pure Go and `Encode` returns JSON, which makes it a clean fit for
[`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile). MirrorMsg does
exactly this: it builds an `.aar` and calls the encoder from Kotlin.

One caveat learned the hard way: **gomobile chokes on exporting `image.Image` and
similar rich types.** So don't bind `watchimage` directly. Instead, expose thin
`[]byte`/`string`/`int` wrappers from a package that *is* bound, and forward:

```go
// in a bound package, e.g. mirrorbridge
package mirrorbridge

import "github.com/killdano/mirrormsg/watchimage"

func EncodeImageForWatch(rawBytes []byte, maxW, maxH, maxBytes, quality int) (string, error) {
	return watchimage.EncodeImageForWatch(rawBytes, maxW, maxH, maxBytes, quality)
}

func SetImageConfig(thumbCov, fullCov float64, thumbCap, fullCap int,
	compressFit, dither bool, ditherEdge, ditherStrength, ditherIntensity int) {
	watchimage.SetConfig(thumbCov, fullCov, thumbCap, fullCap,
		compressFit, dither, ditherEdge, ditherStrength, ditherIntensity)
}
```

Build the AAR:

```
gomobile bind -target android/arm64 -androidapi 26 -javapkg fi.mirrormsg \
  -o out/mirrorbridge.aar fi.mirrormsg/mirrorbridge
```

Then from Kotlin the JSON comes back as a `String` you parse into your own result
type. `EncodeImageForWatch` is the simplest entry point (it matches MirrorMsg's
original signature); new code should prefer `Encode(rawBytes, Options{...})`.

---

## Rendering the output on the device

`watchimage` produces the *data*; your firmware does the *drawing*. The packed
format is deliberately simple:

- **Palettized frames**: read `Depth` bits per pixel MSB-first, rows padded to a
  4-byte boundary, and look each index up in the `Palette` (GColor8 bytes). On
  Pebble this maps directly onto `GBitmapFormat*BitPalette`.
- **Full-color frames**: one GColor8 byte per pixel.

The **compression** path (row codec + restart index) is designed for
decode-on-draw: store the compressed blob resident, seek via the restart index,
and expand one scanline at a time straight into the framebuffer so the full bitmap
never exists in RAM. MirrorMsg's Pebble watchapp implements this; the codec is
documented inline in `watchimage.go` if you want to implement a decoder for your
own target.

---

## Why not just use PNG / zlib / a JPEG library?

Because the constraint isn't *transfer* size — it's *resident* RAM on the display.
A general-purpose codec decompresses back to a full bitmap in memory, which is
exactly what you can't afford on a device with a few KB of heap. `watchimage`'s
codec is built so the compressed form *is* the resident form, decoded per-scanline
at draw time. It also uses pure RLE + row prediction (no LZ dictionary), so the
decoder needs almost no state. Fewer colors and lower bit depth then stack on top:
they make each pixel cheaper *and* the data more compressible.

---

## License

Part of MirrorMsg, licensed under **AGPL-3.0**. See the repository root for
details. If you reuse `watchimage` in your own project, the AGPL terms apply.
