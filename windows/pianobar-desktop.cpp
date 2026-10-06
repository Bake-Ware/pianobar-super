// pianobar SUPER desktop shell
//
// A thin Win32 window around Microsoft Edge WebView2 that hosts the pianobar
// web client. Adds native things a browser tab cannot do:
//   - always-on-top (toggle in the tray, from the web UI, or Ctrl+Alt+T)
//   - minimize/close to tray (Ctrl+Alt+P toggles visibility)
//   - a compact 344x240 widget window with rounded corners (Ctrl+Alt+M)
//   - a server address dialog with http://user:pass@host Basic auth support
//
// Everything else (stations, player, library, settings, prompts) is the web
// client itself, so the desktop app never drifts from the browser.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <dwmapi.h>
#include <objidl.h>
#include <string>
#include <string.h>
#include <cstdlib>
#include <cwchar>
#include <cwctype>
#include <cmath>
#include <WebView2.h>

namespace {

constexpr wchar_t kWindowClass[] = L"PianobarSuperDesktop";
constexpr wchar_t kDialogClass[] = L"PianobarServerDialog";
constexpr wchar_t kMutexName[] = L"Local\\PianobarSuperDesktop";
constexpr int kIconResource = 124;
constexpr int kDefaultServerLength = 512;
constexpr int kWidgetWidth = 344;
constexpr int kWidgetHeight = 240;
constexpr int kFullWidth = 1080;
constexpr int kFullHeight = 720;
constexpr UINT kTrayMessage = WM_APP + 1;
constexpr UINT kSecondInstance = WM_APP + 2;
constexpr int kHotkeyTopmost = 1;
constexpr int kHotkeyWidget = 2;
constexpr int kHotkeyToggle = 3;
constexpr int kMenuShow = 100;
constexpr int kMenuTopmost = 101;
constexpr int kMenuWidget = 102;
constexpr int kMenuServer = 103;
constexpr int kMenuExit = 104;
constexpr int kEditSaveTimer = 1;
#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
#ifndef DWMWCP_ROUND
#define DWMWCP_ROUND 2
#endif

HWND g_hwnd = nullptr;
ICoreWebView2Environment* g_environment = nullptr;
ICoreWebView2Controller* g_controller = nullptr;
ICoreWebView2* g_view = nullptr;
std::wstring g_server = L"https://pianobar.bake.systems";
bool g_topmost = true;
bool g_widget = false;
bool g_closeToTray = true;
bool g_exiting = false;
RECT g_fullRect = {0, 0, 0, 0};
RECT g_widgetPos = {0, 0, 0, 0};
NOTIFYICONDATAW g_nid = {};
HICON g_icon = nullptr;
HTIMER g_saveTimer = nullptr;

std::wstring JsonGet(const std::wstring& text, const std::wstring& key) {
  size_t pos = text.find(L"\"" + key + L"\"");
  if (pos == std::wstring::npos) return {};
  pos = text.find(L':', pos);
  if (pos == std::wstring::npos) return {};
  ++pos;
  while (pos < text.size() && (text[pos] == L' ' || text[pos] == L'\t')) ++pos;
  if (pos < text.size() && text[pos] == L'"') {
    ++pos;
    size_t end = text.find(L'"', pos);
    if (end == std::wstring::npos) return {};
    return text.substr(pos, end - pos);
  }
  size_t end = pos;
  while (end < text.size() && iswalnum(text[end])) ++end;
  return text.substr(pos, end - pos);
}

std::wstring JsonString(const std::wstring& text, const std::wstring& key) {
  return JsonGet(text, key);
}

bool JsonBool(const std::wstring& text, const std::wstring& key, bool fallback) {
  std::wstring value = JsonGet(text, key);
  if (value.empty()) return fallback;
  return value == L"true";
}

long long JsonInt(const std::wstring& text, const std::wstring& key, long long fallback) {
  std::wstring value = JsonGet(text, key);
  if (value.empty()) return fallback;
  return _wtoi64(value.c_str());
}

std::wstring EscapeHtml(const std::wstring& text) {
  std::wstring out;
  for (wchar_t c : text) {
    switch (c) {
      case L'&': out += L"&amp;"; break;
      case L'<': out += L"&lt;"; break;
      case L'>': out += L"&gt;"; break;
      default: out += c;
    }
  }
  return out;
}

void NormalizeServer(std::wstring& server) {
  while (!server.empty() && (server.front() == L' ' || server.front() == L'\t')) server.erase(server.begin());
  while (!server.empty() && (server.back() == L' ' || server.back() == L'\t')) server.pop_back();
  if (server.find(L"://") == std::wstring::npos) server = L"http://" + server;
  while (server.size() > 8 && server.back() == L'/') server.pop_back();
}

std::wstring SanitizeServer(std::wstring raw) {
  std::wstring out;
  for (wchar_t c : raw) {
    if (iswspace(c)) continue;
    if (iswalnum(c) || L":/.@_~+-".find(c) != std::wstring::npos) out += c;
  }
  return out;
}

void SplitBasicAuth(const std::wstring& url, std::wstring* user, std::wstring* pass) {
  size_t schemeEnd = url.find(L"://");
  if (schemeEnd == std::wstring::npos) return;
  size_t authorityStart = schemeEnd + 3;
  size_t at = url.find(L'@', authorityStart);
  if (at == std::wstring::npos) return;
  size_t slash = url.find(L'/', authorityStart);
  if (slash != std::wstring::npos && slash < at) return;
  size_t colon = url.find(L':', authorityStart);
  if (colon == std::wstring::npos || colon > at) {
    user->assign(url, authorityStart, at - authorityStart);
    return;
  }
  user->assign(url, authorityStart, colon - authorityStart);
  pass->assign(url, colon + 1, at - colon - 1);
}

std::wstring EnvironmentPath(const wchar_t* variable) {
  wchar_t path[MAX_PATH] = {};
  GetEnvironmentVariableW(variable, path, MAX_PATH);
  return path;
}

std::wstring ConfigDir() {
  return EnvironmentPath(L"APPDATA") + L"\\pianobar-super";
}

void SaveSettings() {
  if (!g_hwnd) return;
  RECT full = g_fullRect, widget = g_widgetPos;
  if (full.right <= full.left) full = {0, 0, kFullWidth, kFullHeight};
  wchar_t text[1024];
  swprintf(text, 1024,
    L"{\"server\":\"%s\",\"topmost\":%s,\"widget\":%s,\"closeToTray\":%s,"
    L"\"window\":{\"x\":%ld,\"y\":%ld,\"w\":%ld,\"h\":%ld},"
    L"\"widgetPos\":{\"x\":%ld,\"y\":%ld}}",
    g_server.c_str(), g_topmost ? L"true" : L"false",
    g_widget ? L"true" : L"false", g_closeToTray ? L"true" : L"false",
    (long)full.left, (long)full.top, (long)(full.right - full.left), (long)(full.bottom - full.top),
    (long)widget.left, (long)widget.top);
  int length = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
  std::string bytes;
  bytes.resize(length > 0 ? (size_t)length - 1 : 0);
  if (length > 1) WideCharToMultiByte(CP_UTF8, 0, text, -1, &bytes[0], length, nullptr, nullptr);
  CreateDirectoryW(ConfigDir().c_str(), nullptr);
  HANDLE file = CreateFileW((ConfigDir() + L"\\desktop.json").c_str(), GENERIC_WRITE, 0,
    nullptr, CREATE_ALWAYS, 0, nullptr);
  if (file == INVALID_HANDLE_VALUE) return;
  WriteFile(file, bytes.c_str(), (DWORD)bytes.size(), nullptr, nullptr);
  CloseHandle(file);
}

void QueueSave() {
  if (!g_saveTimer) g_saveTimer = SetTimer(g_hwnd, kEditSaveTimer, 400, nullptr);
}

void LoadSettings() {
  HANDLE file = CreateFileW((ConfigDir() + L"\\desktop.json").c_str(), GENERIC_READ,
    FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
  if (file == INVALID_HANDLE_VALUE) return;
  char text[4096] = {};
  DWORD count = 0;
  ReadFile(file, text, sizeof(text) - 1, &count, nullptr);
  CloseHandle(file);
  int wide = MultiByteToWideChar(CP_UTF8, 0, text, (int)count, nullptr, 0);
  std::wstring json(wide, 0);
  std::wstring server = JsonString(json, L"server");
  if (!server.empty()) { NormalizeServer(server); g_server = server; }
  g_topmost = JsonBool(json, L"topmost", g_topmost);
  g_widget = JsonBool(json, L"widget", g_widget);
  g_closeToTray = JsonBool(json, L"closeToTray", g_closeToTray);
  size_t windowPos = json.find(L"\"window\"");
  if (windowPos != std::wstring::npos) {
    std::wstring windowSub = json.substr(windowPos);
    g_fullRect.left = (LONG)JsonInt(windowSub, L"x", g_fullRect.left);
    g_fullRect.top = (LONG)JsonInt(windowSub, L"y", g_fullRect.top);
    g_fullRect.right = g_fullRect.left + (LONG)JsonInt(windowSub, L"w", kFullWidth);
    g_fullRect.bottom = g_fullRect.top + (LONG)JsonInt(windowSub, L"h", kFullHeight);
  }
  size_t widgetPos = json.find(L"\"widgetPos\"");
  if (widgetPos != std::wstring::npos) {
    std::wstring widgetSub = json.substr(widgetPos);
    g_widgetPos.left = (LONG)JsonInt(widgetSub, L"x", g_widgetPos.left);
    g_widgetPos.top = (LONG)JsonInt(widgetSub, L"y", g_widgetPos.top);
  }
}

void SaveGeometry() {
  RECT rect;
  if (!GetWindowRect(g_hwnd, &rect)) return;
  if (g_widget) {
    g_widgetPos.left = rect.left;
    g_widgetPos.top = rect.top;
  } else {
    g_fullRect = rect;
  }
  QueueSave();
}

int Scale(int value) {
  HDC screen = GetDC(nullptr);
  int dpi = GetDeviceCaps(screen, LOGPIXELSX);
  ReleaseDC(nullptr, screen);
  return (int)lroundf((float)value * dpi / 96.0f);
}

void PushShellState() {
  if (!g_view) return;
  std::wstring json = L"{\"type\":\"shellState\",\"topmost\":" +
    (g_topmost ? L"true" : L"false") + L"}";
  g_view->PostWebMessageToScript(json.c_str());
}

std::wstring BuildErrorPage() {
  std::wstring url = g_server;
  std::wstring escaped = EscapeHtml(url);
  return L"<!doctype html><html><head><meta charset=\"utf-8\">"
    L"<meta name=\"theme-color\" content=\"#172b2b\">"
    L"<style>body{margin:0;background:#172b2b;color:#d7dfd0;"
    L"font:14px 'Segoe UI',Arial,sans-serif;display:flex;align-items:center;"
    L"justify-content:center;min-height:100vh}"
    L".box{max-width:440px;padding:32px}"
    L"h1{font-size:20px;letter-spacing:-.5px;margin:0 0 10px}"
    L"p{color:#95a598;font-size:12px;line-height:1.7;margin:0 0 20px;word-break:break-all}"
    L"button{background:#e68b55;color:#172b2b;border:0;font:inherit;font-weight:700;"
    L"padding:10px 16px;margin-right:8px;cursor:pointer}"
    L"button.ghost{background:transparent;color:#d7dfd0;border:1px solid #506056}"
    L"small{display:block;margin-top:22px;color:#5c6f66;font-size:10px;letter-spacing:.4px}"
    L"</style></head><body><div class=\"box\">"
    L"<h1>Can't reach the server</h1>"
    L"<p>" + escaped + L"</p>"
    L"<button onclick=\"try{window.chrome.webview.postMessage({type:'changeServer'})}"
    L"catch(e){location.reload()}\">Change server…</button>"
    L"<button class=\"ghost\" onclick=\"location.reload()\">Retry</button>"
    L"<small>pianobar SUPER · desktop shell</small>"
    L"</div></body></html>";
}

void Navigate(const std::wstring& path) {
  if (g_view) g_view->Navigate((g_server + path).c_str());
}

void ApplyTopmost() {
  HWND insertAfter = g_topmost ? HWND_TOPMOST : HWND_NOTOPMOST;
  SetWindowPos(g_hwnd, insertAfter, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
  PushShellState();
}

void SetWidgetMode(bool on) {
  if (g_hwnd == nullptr || on == g_widget) return;
  g_widget = on;
  SaveSettings();
  if (on) {
    if (IsWindowVisible(g_hwnd)) {
      RECT rect;
      if (GetWindowRect(g_hwnd, &rect)) g_fullRect = rect;
    }
    LONG ex = GetWindowLongPtrW(g_hwnd, GWL_EXSTYLE);
    SetWindowLongPtrW(g_hwnd, GWL_EXSTYLE, ex | WS_EX_TOOLWINDOW);
    SetWindowLongPtrW(g_hwnd, GWL_STYLE, WS_POPUP | WS_CLIPSIBLINGS);
    LONG x, y;
    if (g_widgetPos.left || g_widgetPos.top) {
      x = g_widgetPos.left;
      y = g_widgetPos.top;
    } else {
      x = g_fullRect.left + ((g_fullRect.right - g_fullRect.left) - Scale(kWidgetWidth)) / 2;
      y = g_fullRect.top + Scale(80);
    }
    SetWindowPos(g_hwnd, g_topmost ? HWND_TOPMOST : HWND_NOTOPMOST,
      x, y, Scale(kWidgetWidth), Scale(kWidgetHeight),
      SWP_FRAMECHANGED | SWP_SHOWWINDOW);
    DWM_WINDOW_CORNER_PREFERENCE corner = DWMWCP_ROUND;
    DwmSetWindowAttribute(g_hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, &corner, sizeof(corner));
    Navigate(L"/widget");
  } else {
    LONG ex = GetWindowLongPtrW(g_hwnd, GWL_EXSTYLE);
    SetWindowLongPtrW(g_hwnd, GWL_EXSTYLE, ex & ~WS_EX_TOOLWINDOW);
    SetWindowLongPtrW(g_hwnd, GWL_STYLE, WS_OVERLAPPEDWINDOW);
    SetWindowPos(g_hwnd, g_topmost ? HWND_TOPMOST : HWND_NOTOPMOST,
      g_fullRect.left, g_fullRect.top,
      g_fullRect.right - g_fullRect.left, g_fullRect.bottom - g_fullRect.top,
      SWP_FRAMECHANGED | SWP_SHOWWINDOW);
    Navigate(L"/");
  }
  SetForegroundWindow(g_hwnd);
}

void UpdateTrayTip(const std::wstring& title) {
  lstrcpynW(g_nid.szTip, title.c_str(), ARRAYSIZE(g_nid.szTip));
  Shell_NotifyIconW(NIM_MODIFY, &g_nid);
}

void ShowTrayMenu(HWND hwnd) {
  POINT point;
  GetCursorPos(&point);
  HMENU menu = CreatePopupMenu();
  AppendMenuW(menu, MF_STRING, kMenuShow, L"Show window");
  AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
  AppendMenuW(menu, MF_STRING | (g_topmost ? MF_CHECKED : 0), kMenuTopmost, L"Always on top");
  AppendMenuW(menu, MF_STRING | (g_widget ? MF_CHECKED : 0), kMenuWidget, L"Widget mode");
  AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
  AppendMenuW(menu, MF_STRING, kMenuServer, L"Change server…");
  AppendMenuW(menu, MF_STRING, kMenuExit, L"Exit");
  SetForegroundWindow(hwnd);
  TrackPopupMenu(menu, TPM_RIGHTBUTTON, point.x, point.y, 0, hwnd, nullptr);
  DestroyMenu(menu);
  PostMessageW(hwnd, WM_NULL, 0, 0);
}

struct ServerDialogResult {
  std::wstring value;
  bool ok = false;
};

void EndServerDialog(HWND hwnd, const ServerDialogResult& result) {
  ServerDialogResult* storage =
    reinterpret_cast<ServerDialogResult*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
  if (storage) *storage = result;
  DestroyWindow(hwnd);
}

void AcceptServerDialog(HWND hwnd) {
  ServerDialogResult* storage =
    reinterpret_cast<ServerDialogResult*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
  if (!storage) return;
  HWND edit = GetDlgItem(hwnd, 201);
  wchar_t raw[1024] = {};
  GetWindowTextW(edit, raw, 1024);
  std::wstring value = SanitizeServer(raw);
  if (value.empty()) return;
  NormalizeServer(value);
  storage->value = value;
  storage->ok = true;
  DestroyWindow(hwnd);
}

LRESULT CALLBACK DialogProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
  switch (message) {
    case WM_CREATE: {
      CREATESTRUCTW* cs = reinterpret_cast<CREATESTRUCTW*>(lParam);
      SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(cs->lpCreateParams));
      HINSTANCE module = GetModuleHandleW(nullptr);
      CreateWindowExW(0, L"STATIC", L"pianobar server address", WS_CHILD | WS_VISIBLE,
        16, 14, 460, 20, hwnd, nullptr, module, nullptr);
      HWND edit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL, 16, 42, 460, 26, hwnd,
        reinterpret_cast<HMENU>(201), module, nullptr);
      CreateWindowExW(0, L"STATIC", L"Supports http://user:pass@host:port", WS_CHILD | WS_VISIBLE,
        16, 76, 460, 16, hwnd, nullptr, module, nullptr);
      CreateWindowExW(0, L"BUTTON", L"OK", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
        316, 104, 76, 28, hwnd, reinterpret_cast<HMENU>(202), module, nullptr);
      CreateWindowExW(0, L"BUTTON", L"Cancel", WS_CHILD | WS_VISIBLE,
        400, 104, 76, 28, hwnd, reinterpret_cast<HMENU>(203), module, nullptr);
      SetFocus(edit);
      return 0;
    }
    case WM_COMMAND:
      if (HIWORD(wParam) == BN_CLICKED) {
        if (LOWORD(wParam) == 202) { AcceptServerDialog(hwnd); return 0; }
        if (LOWORD(wParam) == 203) { EndServerDialog(hwnd, {}); return 0; }
      }
      return 0;
    case WM_KEYDOWN:
      if (wParam == VK_RETURN) { AcceptServerDialog(hwnd); return 0; }
      if (wParam == VK_ESCAPE) { EndServerDialog(hwnd, {}); return 0; }
      return 0;
    case WM_CLOSE:
      EndServerDialog(hwnd, {});
      return 0;
  }
  return DefWindowProcW(hwnd, message, wParam, lParam);
}

void ChangeServer() {
  if (!g_hwnd) return;
  ServerDialogResult result;
  WNDCLASSEXW classInfo = {};
  classInfo.cbSize = sizeof(classInfo);
  classInfo.lpfnWndProc = DialogProc;
  classInfo.hInstance = GetModuleHandleW(nullptr);
  classInfo.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  classInfo.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
  classInfo.lpszClassName = kDialogClass;
  RegisterClassExW(&classInfo);
  HWND dialog = CreateWindowExW(0, kDialogClass, L"pianobar server address",
    WS_POPUP | WS_CAPTION | WS_SYSMENU | WS_VISIBLE,
    CW_USEDEFAULT, CW_USEDEFAULT, 500, 160, g_hwnd, nullptr,
    GetModuleHandleW(nullptr), &result);
  if (!dialog) return;
  SetWindowLongPtrW(dialog, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(&result));
  SetDlgItemTextW(dialog, 201, g_server.c_str());
  MSG message;
  while (IsWindow(dialog) && GetMessageW(&message, nullptr, 0, 0) > 0) {
    if (!IsDialogMessageW(dialog, &message)) {
      TranslateMessage(&message);
      DispatchMessageW(&message);
    }
  }
  if (result.ok && result.value != g_server) {
    g_server = result.value;
    SaveSettings();
    RecreateView();
  }
}

void HandleWebMessage(const std::wstring& raw) {
  std::wstring type = JsonGet(raw, L"type");
  if (type == L"topmost") {
    g_topmost = JsonGet(raw, L"value") == L"true";
    ApplyTopmost();
    SaveSettings();
  } else if (type == L"openFull") {
    if (g_widget) SetWidgetMode(false);
  } else if (type == L"widget") {
    SetWidgetMode(true);
  } else if (type == L"changeServer") {
    ChangeServer();
  }
}

class WebMessageHandler : public ICoreWebView2WebMessageReceivedHandler {
  long refs = 1;
 public:
  static void Attach(ICoreWebView2* view) {
    static WebMessageHandler handler;
    view->AddWebMessageReceivedWithHandlers(&handler);
  }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
    if (!out) return E_POINTER;
    if (riid == IID_IUnknown || riid == __uuidof(ICoreWebView2WebMessageReceivedHandler)) {
      *out = this;
      AddRef();
      return S_OK;
    }
    *out = nullptr;
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
  ULONG STDMETHODCALLTYPE Release() override {
    long remaining = InterlockedDecrement(&refs);
    if (!remaining) delete this;
    return (ULONG)remaining;
  }
  HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) override {
    PCWSTR json = nullptr;
    if (SUCCEEDED(args->get_WebMessageAsJson(&json)) && json) HandleWebMessage(json);
    return S_OK;
  }
};

class NavigationHandler : public ICoreWebView2NavigationCompletedHandler {
  long refs = 1;
 public:
  static void Attach(ICoreWebView2* view) {
    static NavigationHandler handler;
    view->AddNavigationCompletedWithHandlers(&handler);
  }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
    if (!out) return E_POINTER;
    if (riid == IID_IUnknown || riid == __uuidof(ICoreWebView2NavigationCompletedHandler)) {
      *out = this;
      AddRef();
      return S_OK;
    }
    *out = nullptr;
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
  ULONG STDMETHODCALLTYPE Release() override {
    long remaining = InterlockedDecrement(&refs);
    if (!remaining) delete this;
    return (ULONG)remaining;
  }
  HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2* view,
      ICoreWebView2NavigationCompletedEventArgs* args) override {
    BOOL succeeded = FALSE;
    if (SUCCEEDED(args->get_IsSuccess(&succeeded))) {
      if (succeeded) {
        PCWSTR url = nullptr;
        if (SUCCEEDED(view->GetCurrentURL(&url)) && url && wcsstr(url, L"/widget")) {
          PushShellState();
        }
      } else {
        PCWSTR url = nullptr;
        if (SUCCEEDED(view->GetCurrentURL(&url)) && url &&
            wcsnicmp(url, g_server.c_str(), g_server.size()) == 0) {
          view->NavigateToString(BuildErrorPage().c_str(), L"about:blank");
        }
      }
    }
    return S_OK;
  }
};

class TitleHandler : public ICoreWebView2DocumentTitleChangedHandler {
  long refs = 1;
 public:
  static void Attach(ICoreWebView2* view) {
    static TitleHandler handler;
    view->AddDocumentTitleChangedWithHandlers(&handler);
  }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
    if (!out) return E_POINTER;
    if (riid == IID_IUnknown || riid == __uuidof(ICoreWebView2DocumentTitleChangedHandler)) {
      *out = this;
      AddRef();
      return S_OK;
    }
    *out = nullptr;
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
  ULONG STDMETHODCALLTYPE Release() override {
    long remaining = InterlockedDecrement(&refs);
    if (!remaining) delete this;
    return (ULONG)remaining;
  }
  HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2* view, IUnknown*) override {
    BSTR title = nullptr;
    if (SUCCEEDED(view->get_DocumentTitle(&title)) && title) {
      std::wstring text(title);
      SysFreeString(title);
      UpdateTrayTip(text.empty() ? std::wstring(L"pianobar SUPER") : text);
    }
    return S_OK;
  }
};

void CreateController();
void RecreateView();

// COM callback classes. Each is a single-interface COM object with a simple
// refcount; the three handler objects live for the process lifetime.
class EnvironmentCallback : public ICreateCoreWebView2EnvironmentCallback {
  long refs = 1;
 public:
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
    if (!out) return E_POINTER;
    if (riid == IID_IUnknown || riid == __uuidof(ICreateCoreWebView2EnvironmentCallback)) {
      *out = this;
      AddRef();
      return S_OK;
    }
    *out = nullptr;
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
  ULONG STDMETHODCALLTYPE Release() override {
    long remaining = InterlockedDecrement(&refs);
    if (!remaining) delete this;
    return (ULONG)remaining;
  }
  HRESULT STDMETHODCALLTYPE Invoke(HRESULT result, ICoreWebView2Environment* environment) override {
    if (SUCCEEDED(result) && environment) {
      environment->AddRef();
      g_environment = environment;
      CreateController();
    }
    Release();
    return S_OK;
  }
};

class ControllerCallback : public ICreateCoreWebView2ControllerCallback {
  long refs = 1;
 public:
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
    if (!out) return E_POINTER;
    if (riid == IID_IUnknown || riid == __uuidof(ICreateCoreWebView2ControllerCallback)) {
      *out = this;
      AddRef();
      return S_OK;
    }
    *out = nullptr;
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
  ULONG STDMETHODCALLTYPE Release() override {
    long remaining = InterlockedDecrement(&refs);
    if (!remaining) delete this;
    return (ULONG)remaining;
  }
  HRESULT STDMETHODCALLTYPE Invoke(HRESULT result, ICoreWebView2Controller* controller) override {
    if (SUCCEEDED(result) && controller) {
      controller->AddRef();
      g_controller = controller;
      ICoreWebView2* view = nullptr;
      if (SUCCEEDED(controller->get_CoreWebView2(&view)) && view) {
        g_view = view;
        ICoreWebView2Settings* settings = nullptr;
        if (SUCCEEDED(view->get_Settings(&settings)) && settings) {
          settings->put_IsZoomControlEnabled(FALSE);
          settings->Release();
        }
        WebMessageHandler::Attach(view);
        NavigationHandler::Attach(view);
        TitleHandler::Attach(view);
        Navigate(g_widget ? L"/widget" : L"/");
      }
    }
    Release();
    return S_OK;
  }
};
void CreateController() {
  if (g_environment) g_environment->CreateCoreWebView2Controller(g_hwnd,
    new ControllerCallback());
}

void RecreateView() {
  if (g_controller) {
    g_controller->Close();
    g_controller = nullptr;
  }
  g_view = nullptr;
  CreateController();
}


LRESULT CALLBACK WndProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
  switch (message) {
    case kTrayMessage:
      switch (LOWORD(lParam)) {
        case WM_LBUTTONUP:
          if (IsWindowVisible(hwnd)) ShowWindow(hwnd, SW_HIDE);
          else {
            ShowWindow(hwnd, SW_SHOW);
            SetForegroundWindow(hwnd);
          }
          return 0;
        case WM_RBUTTONUP:
        case WM_CONTEXTMENU:
          ShowTrayMenu(hwnd);
          return 0;
      }
      return 0;
    case kSecondInstance:
      ShowWindow(hwnd, SW_SHOW);
      SetForegroundWindow(hwnd);
      return 0;
    case WM_HOTKEY:
      switch (wParam) {
        case kHotkeyTopmost:
          g_topmost = !g_topmost;
          ApplyTopmost();
          SaveSettings();
          return 0;
        case kHotkeyWidget:
          SetWidgetMode(!g_widget);
          return 0;
        case kHotkeyToggle:
          if (IsWindowVisible(hwnd)) ShowWindow(hwnd, SW_HIDE);
          else {
            ShowWindow(hwnd, SW_SHOW);
            SetForegroundWindow(hwnd);
          }
          return 0;
      }
      return 0;
    case WM_COMMAND:
      switch (LOWORD(wParam)) {
        case kMenuShow:
          ShowWindow(hwnd, SW_SHOW);
          SetForegroundWindow(hwnd);
          return 0;
        case kMenuTopmost:
          g_topmost = !g_topmost;
          ApplyTopmost();
          SaveSettings();
          return 0;
        case kMenuWidget:
          SetWidgetMode(!g_widget);
          return 0;
        case kMenuServer:
          ChangeServer();
          return 0;
        case kMenuExit:
          g_exiting = true;
          DestroyWindow(hwnd);
          return 0;
      }
      return 0;
    case WM_TIMER:
      if (wParam == kEditSaveTimer) {
        KillTimer(hwnd, kEditSaveTimer);
        g_saveTimer = nullptr;
        SaveSettings();
      }
      return 0;
    case WM_SIZE:
      if (wParam == SIZE_MINIMIZED && g_closeToTray) ShowWindow(hwnd, SW_HIDE);
      else SaveGeometry();
      return 0;
    case WM_MOVE:
      SaveGeometry();
      return 0;
    case WM_DPICHANGED: {
      if (g_widget) {
        RECT* suggested = reinterpret_cast<RECT*>(lParam);
        SetWindowPos(hwnd, nullptr, suggested->left, suggested->top,
          Scale(kWidgetWidth), Scale(kWidgetHeight), SWP_NOZORDER);
      }
      return 0;
    }
    case WM_GETMINMAXINFO: {
      if (!g_widget) {
        MINMAXINFO* info = reinterpret_cast<MINMAXINFO*>(lParam);
        info->ptMinTrackSize.x = Scale(720);
        info->ptMinTrackSize.y = Scale(480);
      }
      return 0;
    }
    case WM_CLOSE:
      if (g_closeToTray && !g_exiting) {
        ShowWindow(hwnd, SW_HIDE);
        return 0;
      }
      g_exiting = true;
      DestroyWindow(hwnd);
      return 0;
    case WM_DESTROY:
      if (g_saveTimer) {
        KillTimer(hwnd, kEditSaveTimer);
        g_saveTimer = nullptr;
      }
      SaveSettings();
      PostQuitMessage(0);
      return 0;
  }
  return DefWindowProcW(hwnd, message, wParam, lParam);
}

void InstallTrayIcon(HINSTANCE instance) {
  g_nid.cbSize = sizeof(g_nid);
  g_nid.hWnd = g_hwnd;
  g_nid.uID = 1;
  g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
  g_nid.uCallbackMessage = kTrayMessage;
  g_nid.hIcon = g_icon ? g_icon : (HICON)LoadImageW(instance, MAKEINTRESOURCEW(IDI_APPLICATION),
    IMAGE_ICON, 0, 0, 0);
  lstrcpynW(g_nid.szTip, L"pianobar SUPER", ARRAYSIZE(g_nid.szTip));
  Shell_NotifyIconW(NIM_ADD, &g_nid);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
  SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
  int argumentCount = 0;
  LPWSTR* arguments = CommandLineToArgvW(GetCommandLineW(), &argumentCount);
  for (int i = 1; i < argumentCount; ++i) {
    std::wstring argument = arguments[i];
    if (argument == L"--widget") g_widget = true;
    else if (argument == L"--topmost") g_topmost = true;
    else if (argument.rfind(L"--server=", 0) == 0) {
      std::wstring server = SanitizeServer(argument.substr(9));
      if (!server.empty()) {
        NormalizeServer(server);
        g_server = server;
      }
    } else if (argument == L"--no-tray") g_closeToTray = false;
  }
  LocalFree(arguments);
  HANDLE singleInstance = CreateMutexW(nullptr, TRUE, kMutexName);
  if (GetLastError() == ERROR_ALREADY_EXISTS) {
    HWND existing = FindWindowW(kWindowClass, nullptr);
    if (existing) {
      ShowWindow(existing, SW_SHOW);
      SetForegroundWindow(existing);
    }
    return 0;
  }
  LoadSettings();
  WNDCLASSEXW classInfo = {};
  classInfo.cbSize = sizeof(classInfo);
  classInfo.lpfnWndProc = WndProc;
  classInfo.hInstance = instance;
  classInfo.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  classInfo.hbrBackground = reinterpret_cast<HBRUSH>(CreateSolidBrush(RGB(0x17, 0x2b, 0x2b)));
  classInfo.lpszClassName = kWindowClass;
  g_icon = (HICON)LoadImageW(instance, MAKEINTRESOURCEW(kIconResource), IMAGE_ICON, 0, 0, 0);
  classInfo.hIcon = g_icon ? g_icon : LoadIconW(nullptr, IDI_APPLICATION);
  if (!RegisterClassExW(&classInfo)) return 1;
  LONG style = g_widget ? (WS_POPUP | WS_CLIPSIBLINGS) : WS_OVERLAPPEDWINDOW;
  LONG exStyle = g_widget ? WS_EX_TOOLWINDOW : 0;
  int width = g_widget ? Scale(kWidgetWidth) : kFullWidth;
  int height = g_widget ? Scale(kWidgetHeight) : kFullHeight;
  int x = g_widget ? g_widgetPos.left : g_fullRect.left;
  int y = g_widget ? g_widgetPos.top : g_fullRect.top;
  bool hasPosition = g_widget ? (g_widgetPos.left || g_widgetPos.top) :
    (g_fullRect.left || g_fullRect.top);
  g_hwnd = CreateWindowExW(exStyle, kWindowClass, L"pianobar SUPER", style,
    hasPosition ? x : CW_USEDEFAULT, hasPosition ? y : CW_USEDEFAULT,
    hasPosition ? width : (g_widget ? width : kFullWidth),
    hasPosition ? height : (g_widget ? height : kFullHeight),
    nullptr, nullptr, instance, nullptr);
  if (!g_hwnd) return 1;
  if (g_widget && g_fullRect.right <= g_fullRect.left) {
    g_fullRect = {0, 0, kFullWidth, kFullHeight};
  }
  ShowWindow(g_hwnd, SW_SHOW);
  UpdateTrayTip(L"pianobar SUPER");
  InstallTrayIcon(instance);
  RegisterHotKey(g_hwnd, kHotkeyTopmost, MOD_CONTROL | MOD_ALT, 'T');
  RegisterHotKey(g_hwnd, kHotkeyWidget, MOD_CONTROL | MOD_ALT, 'M');
  RegisterHotKey(g_hwnd, kHotkeyToggle, MOD_CONTROL | MOD_ALT, 'P');
  if (g_topmost) ApplyTopmost();
  std::wstring userData = EnvironmentPath(L"LOCALAPPDATA") + L"\\pianobar-super\\webview";
  ICoreWebView2EnvironmentOptions* options = nullptr;
  if (SUCCEEDED(CreateCoreWebView2EnvironmentOptions(nullptr, &options)) && options) {
    static const wchar_t kUserAgent[] =
      L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
      L"Chrome/140.0.0.0 Safari/537.36 WebView/1.0 PianobarDesktop/1.0";
    options->SetUserAgent(kUserAgent);
    std::wstring user, pass;
    SplitBasicAuth(g_server, &user, &pass);
    if (!user.empty()) {
      options->SetBasicAuthUsername(user.c_str());
      if (!pass.empty()) options->SetBasicAuthPassword(pass.c_str());
    }
    CreateCoreWebView2EnvironmentWithOptions(nullptr, userData.c_str(), options,
      new EnvironmentCallback());
    options->Release();
  }
  MSG message;
  while (GetMessageW(&message, nullptr, 0, 0) > 0) {
    TranslateMessage(&message);
    DispatchMessageW(&message);
  }
  if (g_saveTimer) {
    KillTimer(g_hwnd, kEditSaveTimer);
    g_saveTimer = nullptr;
  }
  Shell_NotifyIconW(NIM_DELETE, &g_nid);
  UnregisterHotKey(g_hwnd, kHotkeyTopmost);
  UnregisterHotKey(g_hwnd, kHotkeyWidget);
  UnregisterHotKey(g_hwnd, kHotkeyToggle);
  if (g_controller) g_controller->Close();
  if (g_environment) g_environment->Release();
  if (g_icon) DestroyIcon(g_icon);
  CloseHandle(singleInstance);
  return 0;
}
