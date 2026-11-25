# Builds VoD Client for distribution. Bake in your server so users never have to type it:
#
#   make appimage windows VERSION=1.0.0 SERVER_URL=https://vod.example.com
#
# Linux builds need cgo and the X11/Wayland/GL headers:
#   apt install gcc libgl1-mesa-dev xorg-dev libwayland-dev libxkbcommon-dev
# Windows builds cross-compile from Linux with MinGW:
#   apt install gcc-mingw-w64-x86-64

VERSION    ?= dev
SERVER_URL ?=
DIST       ?= dist

CONFIG  := gitea.defaoite.dev/dylan/vod-system/client/internal/config
LDFLAGS := -s -w -X $(CONFIG).Version=$(VERSION) -X $(CONFIG).DefaultServerURL=$(SERVER_URL)
# Windows version resources must be numeric: v1.2.3-rc1 -> 1.2.3.0, dev -> 0.0.0.0.
WIN_VERSION := $(shell v=$$(echo "$(VERSION)" | sed -E 's/^[^0-9]*//; s/[^0-9.].*//'); \
	set -- $$(echo "$$v" | tr . ' ') 0 0 0; echo "$${1:-0}.$${2:-0}.$${3:-0}.0")
MINGW_CC ?= x86_64-w64-mingw32-gcc
WINRES   := cmd/vod-client/rsrc_windows_amd64.syso

.PHONY: test linux appimage windows clean

test:
	go test -race ./...

linux:
	CGO_ENABLED=1 GOOS=linux GOARCH=amd64 \
		go build -trimpath -ldflags "$(LDFLAGS)" -o $(DIST)/vod-client ./cmd/vod-client

appimage: linux
	packaging/linux/build-appimage.sh $(DIST)/vod-client $(DIST)/VoD-Client-x86_64.AppImage

# The .syso embeds the icon and manifest into the .exe; it is generated, never committed.
windows:
	go run github.com/tc-hib/go-winres@v0.3.3 simply --arch amd64 \
		--icon internal/ui/assets/icon.png --manifest gui \
		--product-name "VoD Client" --file-description "VoD Client" \
		--product-version $(WIN_VERSION) --file-version $(WIN_VERSION) \
		--out cmd/vod-client/rsrc
	CGO_ENABLED=1 GOOS=windows GOARCH=amd64 CC=$(MINGW_CC) \
		go build -trimpath -ldflags "$(LDFLAGS) -H windowsgui" -o $(DIST)/VoD-Client.exe ./cmd/vod-client
	rm -f $(WINRES)

clean:
	rm -f $(DIST)/vod-client $(DIST)/VoD-Client-x86_64.AppImage $(DIST)/VoD-Client.exe $(WINRES)
