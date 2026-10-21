/**
 * Centralized Network & API Client Layer for OTTV2
 */

export const DEFAULT_HEADERS = {
  'Content-Type': 'application/json',
  'ngrok-skip-browser-warning': 'true',
};

let globalForceLogoutHandler = null;

export function registerForceLogoutHandler(handler) {
  globalForceLogoutHandler = handler;
}

export async function fetchWithTimeout(url, options = {}, timeoutMs = 3500) {
  const controller = new AbortController();
  const id = setTimeout(() => {
    try {
      controller.abort();
    } catch {}
  }, timeoutMs);
  try {
    const headers = {
      ...DEFAULT_HEADERS,
      ...(options?.headers || {}),
    };
    const response = await fetch(url, {
      ...(options || {}),
      headers,
      signal: options?.signal || controller.signal,
    });
    return response;
  } finally {
    clearTimeout(id);
  }
}

export async function checkBackendOnline(timeoutMs = 2500) {
  try {
    const res = await fetchWithTimeout(
      '/api/auth/health',
      {
        credentials: 'same-origin',
        headers: {
          ...DEFAULT_HEADERS,
        },
      },
      timeoutMs
    );
    if (!res || !res.status) {
      window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: true } }));
      return false;
    }
    if (res.status === 502 || res.status === 503 || res.status === 504) {
      window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: true } }));
      return false;
    }
    // Any valid response confirms server is reachable
    window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: false } }));
    return true;
  } catch (error) {
    console.error("LỖI KIỂM TRA MÁY CHỦ (checkBackendOnline):", error);
    window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: true } }));
    return false;
  }
}

export async function api(path, options = {}) {
  try {
    const response = await fetchWithTimeout(
      path,
      {
        credentials: 'same-origin',
        ...(options || {}),
        headers: {
          ...DEFAULT_HEADERS,
          ...(options?.headers || {}),
        },
      },
      8000
    );
    if (!response || !response.status) {
      window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: true } }));
      throw new Error('Backend 8080 đang tắt');
    }
    if (response.status === 502 || response.status === 503 || response.status === 504) {
      window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: true } }));
      throw new Error('Backend 8080 đang tắt');
    }
    // Valid response confirms backend 8080 is online
    window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: false } }));

    // Global 401 / 403 Handler (Xử lý tập trung Zombie Session & Hết hạn phiên)
    if (response.status === 401 || response.status === 403) {
      const isAuthPath = path.includes('/api/auth/login') || path.includes('/api/auth/register') || path.includes('/api/auth/me');
      if (!isAuthPath) {
        try {
          localStorage.removeItem('ottv2_last_active_user');
          localStorage.removeItem('ottv2_auth_token');
          sessionStorage.clear();
        } catch {}

        const expiredMsg = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại!';

        if (typeof globalForceLogoutHandler === 'function') {
          globalForceLogoutHandler(expiredMsg);
        }

        window.dispatchEvent(
          new CustomEvent('ottv2_auth_expired', {
            detail: {
              status: response.status,
              path,
              message: expiredMsg,
            },
          })
        );
      }
      return null;
    }

    if (!response.ok) {
      const body = await response.text().catch(() => '');
      if (
        body.includes('<!DOCTYPE') ||
        body.includes('<html') ||
        body.includes('<div') ||
        body.includes('ERR_NGROK') ||
        response.status === 502 ||
        response.status === 504
      ) {
        throw new Error('Không thể kết nối đến máy chủ Backend (Hệ thống đang ngoại tuyến). Bạn có thể bật máy chủ hoặc tiếp tục bằng chế độ Ngoại Tuyến bên dưới.');
      }
      throw new Error(body || `${response.status} ${response.statusText}`);
    }
    return response.status === 204 ? null : response;
  } catch (err) {
    if (err?.status === 401 || err?.message?.includes('Phiên đăng nhập đã hết hạn')) {
      return null;
    }
    console.error("LỖI GỌI API (api):", err);
    if (
      err?.name === 'AbortError' ||
      err?.message?.includes('Failed to fetch') ||
      err?.message?.includes('NetworkError') ||
      err?.message?.includes('Backend 8080')
    ) {
      window.dispatchEvent(new CustomEvent('ottv2_backend_offline', { detail: { offline: true } }));
    }
    throw err;
  }
}

export async function csrfHeaders() {
  try {
    const response = await api('/api/auth/csrf', {
      headers: {
        ...DEFAULT_HEADERS,
      },
    });
    if (!response) {
      return { ...DEFAULT_HEADERS };
    }
    const contentType = response.headers?.get('content-type') || '';
    if (!contentType.includes('application/json')) {
      return { ...DEFAULT_HEADERS };
    }
    const csrf = await response.json();
    return {
      [csrf.headerName]: csrf.token,
      ...DEFAULT_HEADERS,
    };
  } catch {
    return { ...DEFAULT_HEADERS };
  }
}

export function extractErrorMessage(err, fallback = 'Thao tác thất bại. Vui lòng thử lại sau.') {
  if (!err) return fallback;
  let raw = typeof err === 'string' ? err : err.message || '';
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (parsed && typeof parsed === 'object') {
      raw = parsed.error || parsed.message || raw;
    }
  } catch {}

  if (typeof raw !== 'string') return fallback;

  const isSuspicious =
    raw.includes('<html') ||
    raw.includes('<!DOCTYPE') ||
    raw.includes('<div') ||
    raw.includes('ERR_NGROK') ||
    raw.includes('org.springframework') ||
    raw.includes('java.') ||
    raw.includes('jakarta.') ||
    raw.includes('Whitelabel Error') ||
    raw.length > 250;

  if (isSuspicious) {
    return 'Không thể kết nối đến máy chủ Backend (Hệ thống đang ngoại tuyến). Bạn có thể bật máy chủ hoặc tiếp tục bằng chế độ Ngoại Tuyến bên dưới.';
  }

  return raw.trim() || fallback;
}
