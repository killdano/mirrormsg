package watchimage

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"math"
	"math/rand"
	"testing"
)

func encodePNG(t *testing.T, img image.Image) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// A made-up long chat screenshot: flat background, bars of "text" in two colors.
func tallScreenshot(w, h int) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	bg := color.RGBA{250, 250, 250, 255}
	bubble := color.RGBA{30, 110, 230, 255}
	ink := color.RGBA{20, 20, 20, 255}
	r := rand.New(rand.NewSource(2))
	glyph := make([]bool, w/6+1)
	for y := 0; y < h; y++ {
		row := y % 120
		if row%8 == 0 { // a new line of "text": new made-up glyphs
			for i := range glyph {
				glyph[i] = r.Intn(3) != 0
			}
		}
		for x := 0; x < w; x++ {
			c := bg
			if row >= 20 && row < 100 && x > w/8 && x < w*7/8 {
				c = bubble
				if row%8 < 5 && glyph[x/6] && (x+y)%3 != 0 {
					c = ink
				}
			}
			img.Set(x, y, c)
		}
	}
	return img
}

func noise(w, h int) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	r := rand.New(rand.NewSource(1))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(r.Intn(256)), uint8(r.Intn(256)), uint8(r.Intn(256)), 255})
		}
	}
	return img
}

func encodeFit(t *testing.T, raw []byte, maxW, maxBytes int) WatchImageResult {
	t.Helper()
	out, err := Encode(raw, Options{
		MaxW: maxW, MaxH: 0, MaxBytes: maxBytes,
		Palettize: true, AutoDepth: true, MaxDepth: Depth4, Coverage: 0.94,
		CompressFit: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	var res WatchImageResult
	if err := json.Unmarshal([]byte(out), &res); err != nil {
		t.Fatal(err)
	}
	return res
}

// A compressed picture that doesn't fit at its natural size shrinks on both
// sides, keeping its shape (it used to keep the full width and get squashed).
func TestCompressFitKeepsShape(t *testing.T) {
	const sw, sh = 1080, 6000
	res := encodeFit(t, encodePNG(t, tallScreenshot(sw, sh)), 200, 4000)
	want := float64(sw) / float64(sh)
	got := float64(res.Width) / float64(res.Height)
	if math.Abs(got-want)/want > 0.02 {
		t.Fatalf("shape %dx%d: ratio %.4f, want %.4f", res.Width, res.Height, got, want)
	}
	if res.Width >= 200 {
		t.Fatalf("expected a shrunk picture, got %dx%d", res.Width, res.Height)
	}
	if res.EstCompressed <= 0 || res.EstCompressed > 4000 {
		t.Fatalf("compressed estimate %d, want 1..4000", res.EstCompressed)
	}
}

// Compression never makes a picture smaller than it is without it: a picture
// that doesn't compress comes out at the raw-fit size.
func TestCompressFitNeverBelowRawFit(t *testing.T) {
	raw := encodePNG(t, noise(400, 600))
	plain, err := Encode(raw, Options{
		MaxW: 144, MaxBytes: 6000,
		Palettize: true, AutoDepth: true, MaxDepth: Depth4, Coverage: 0.94,
	})
	if err != nil {
		t.Fatal(err)
	}
	var p WatchImageResult
	if err := json.Unmarshal([]byte(plain), &p); err != nil {
		t.Fatal(err)
	}
	res := encodeFit(t, raw, 144, 6000)
	if res.Width < p.Width || res.Height < p.Height {
		t.Fatalf("compressed fit %dx%d is smaller than the raw fit %dx%d",
			res.Width, res.Height, p.Width, p.Height)
	}
}

// A picture that fits at its natural size keeps it.
func TestCompressFitNaturalSize(t *testing.T) {
	res := encodeFit(t, encodePNG(t, tallScreenshot(200, 400)), 200, 48000)
	if res.Width != 200 || res.Height != 400 {
		t.Fatalf("got %dx%d, want 200x400", res.Width, res.Height)
	}
}
