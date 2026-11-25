package ui

import (
	_ "embed"
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/theme"
)

//go:embed assets/icon.png
var iconPNG []byte

// Icon is the app icon.
var Icon = fyne.NewStaticResource("icon.png", iconPNG)

// IconPNG returns the raw icon, e.g. for the Linux menu entry.
func IconPNG() []byte { return iconPNG }

// Colours from the web frontend (frontend/src/index.css), so the app feels like the same product.
var (
	terracotta = color.NRGBA{0xA8, 0x5A, 0x38, 0xFF}
	liveRed    = color.NRGBA{0xB5, 0x43, 0x2E, 0xFF}
	olive      = color.NRGBA{0x6E, 0x7A, 0x50, 0xFF}
)

type palette struct{ background, card, field, button, text, muted, faint, hairline color.Color }

var (
	lightPalette = palette{
		background: color.NRGBA{0xF7, 0xF4, 0xEE, 0xFF},
		card:       color.NRGBA{0xFD, 0xFB, 0xF7, 0xFF},
		field:      color.NRGBA{0xF4, 0xF0, 0xE8, 0xFF},
		button:     color.NRGBA{0xE7, 0xE0, 0xD2, 0xFF},
		text:       color.NRGBA{0x2B, 0x26, 0x20, 0xFF},
		muted:      color.NRGBA{0x6E, 0x65, 0x59, 0xFF},
		faint:      color.NRGBA{0x8A, 0x80, 0x73, 0xFF},
		hairline:   color.NRGBA{0xE2, 0xDA, 0xCB, 0xFF},
	}
	darkPalette = palette{
		background: color.NRGBA{0x16, 0x16, 0x15, 0xFF},
		card:       color.NRGBA{0x1F, 0x1F, 0x1E, 0xFF},
		field:      color.NRGBA{0x1A, 0x1A, 0x19, 0xFF},
		button:     color.NRGBA{0x2C, 0x2B, 0x29, 0xFF},
		text:       color.NRGBA{0xF5, 0xF4, 0xEF, 0xFF},
		muted:      color.NRGBA{0xA8, 0xA2, 0x98, 0xFF},
		faint:      color.NRGBA{0x8C, 0x87, 0x7F, 0xFF},
		hairline:   color.NRGBA{0x2C, 0x2B, 0x29, 0xFF},
	}
)

// Theme follows the system's light or dark mode in the VoD system's colours.
type Theme struct{}

func (Theme) Color(name fyne.ThemeColorName, variant fyne.ThemeVariant) color.Color {
	p := lightPalette
	if variant == theme.VariantDark {
		p = darkPalette
	}
	switch name {
	case theme.ColorNamePrimary, theme.ColorNameHyperlink:
		return terracotta
	case theme.ColorNameBackground:
		return p.background
	case theme.ColorNameOverlayBackground, theme.ColorNameMenuBackground, theme.ColorNameHeaderBackground:
		return p.card
	case theme.ColorNameInputBackground:
		return p.field
	case theme.ColorNameButton:
		return p.button
	case theme.ColorNameForeground:
		return p.text
	case theme.ColorNamePlaceHolder:
		return p.muted
	case theme.ColorNameDisabled:
		// Low-importance labels (captions, hotkey hints) use this; Fyne's default is too faint
		// to read on the cream background.
		return p.faint
	case theme.ColorNameSeparator, theme.ColorNameInputBorder:
		return p.hairline
	case theme.ColorNameError:
		return liveRed
	case theme.ColorNameSuccess:
		return olive
	}
	return theme.DefaultTheme().Color(name, variant)
}

func (Theme) Font(s fyne.TextStyle) fyne.Resource     { return theme.DefaultTheme().Font(s) }
func (Theme) Icon(n fyne.ThemeIconName) fyne.Resource { return theme.DefaultTheme().Icon(n) }
func (Theme) Size(n fyne.ThemeSizeName) float32 {
	if n == theme.SizeNameInputRadius || n == theme.SizeNameSelectionRadius {
		return 6
	}
	return theme.DefaultTheme().Size(n)
}
