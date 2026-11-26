#ifndef VOD_CARBON_DARWIN_H
#define VOD_CARBON_DARWIN_H

#include <Carbon/Carbon.h>

typedef struct {
	UInt32 id;
	UInt32 key;
	UInt32 mods;
	EventHotKeyRef ref;
	OSStatus status;
} vodCall;

OSStatus vodRegister(vodCall *c);
OSStatus vodUnregister(vodCall *c);

// Implemented in Go (register_darwin.go); called on the main thread for each press.
extern void vodHotkeyPressed(UInt32 id);

#endif
