//go:build darwin && cgo

// Global hotkeys on macOS through Carbon's RegisterEventHotKey. Unlike a CGEventTap, which sees
// every keystroke and so needs Accessibility permission, this only receives the registered
// combinations and needs no permission at all.

#include "carbon_darwin.h"

#include <dispatch/dispatch.h>
#include <pthread.h>

static OSStatus vodHotkeyHandler(EventHandlerCallRef next, EventRef event, void *data) {
	EventHotKeyID hk;
	if (GetEventParameter(event, kEventParamDirectObject, typeEventHotKeyID, NULL,
	                      sizeof(hk), NULL, &hk) == noErr) {
		vodHotkeyPressed(hk.id);
	}
	return noErr;
}

static void vodRegisterOnMain(void *p) {
	static int installed = 0;
	vodCall *c = p;
	if (!installed) {
		EventTypeSpec spec = {kEventClassKeyboard, kEventHotKeyPressed};
		c->status = InstallApplicationEventHandler(NewEventHandlerUPP(vodHotkeyHandler), 1, &spec,
		                                           NULL, NULL);
		if (c->status != noErr) {
			return;
		}
		installed = 1;
	}
	EventHotKeyID hk = {'VODC', c->id};
	c->status = RegisterEventHotKey(c->key, c->mods, hk, GetApplicationEventTarget(), 0, &c->ref);
}

static void vodUnregisterOnMain(void *p) {
	vodCall *c = p;
	c->status = UnregisterEventHotKey(c->ref);
}

// Carbon must be called on the main thread, whose run loop (driven by the GUI toolkit) is also
// what delivers the hotkey events. Callers are background goroutines, so hop over and wait.
static void vodOnMain(vodCall *c, dispatch_function_t f) {
	if (pthread_main_np()) {
		f(c);
	} else {
		dispatch_sync_f(dispatch_get_main_queue(), c, f);
	}
}

OSStatus vodRegister(vodCall *c) {
	vodOnMain(c, vodRegisterOnMain);
	return c->status;
}

OSStatus vodUnregister(vodCall *c) {
	vodOnMain(c, vodUnregisterOnMain);
	return c->status;
}
