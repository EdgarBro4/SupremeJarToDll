#include "utils.h"

#include <windows.h>
#include <winuser.h>

HMODULE global_dll_instance = nullptr;

void ShowMessage(const wchar_t* message) {
  // Removed
}

void Error(const wchar_t* error) {
  FreeLibraryAndExitThread(global_dll_instance, 1);
}