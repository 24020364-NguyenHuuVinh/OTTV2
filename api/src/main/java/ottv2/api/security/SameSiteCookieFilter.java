package ottv2.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Filter đảm bảo toàn bộ Set-Cookie (JSESSIONID, XSRF-TOKEN,...) đều có SameSite=None; Secure
 * để trình duyệt không chặn cookie khi Frontend (Vercel) gọi cross-origin sang Backend (Ngrok).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SameSiteCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(response) {
            @Override
            public void addHeader(String name, String value) {
                if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
                    value = formatSameSiteNone(value);
                }
                super.addHeader(name, value);
            }

            @Override
            public void setHeader(String name, String value) {
                if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
                    value = formatSameSiteNone(value);
                }
                super.setHeader(name, value);
            }

            @Override
            public void addCookie(Cookie cookie) {
                if (cookie != null) {
                    cookie.setSecure(true);
                    cookie.setAttribute("SameSite", "None");
                }
                super.addCookie(cookie);
            }

            private String formatSameSiteNone(String cookieHeader) {
                if (cookieHeader == null) return null;
                String lower = cookieHeader.toLowerCase(Locale.ROOT);
                StringBuilder sb = new StringBuilder(cookieHeader);
                if (!lower.contains("samesite")) {
                    sb.append("; SameSite=None");
                }
                if (!lower.contains("secure")) {
                    sb.append("; Secure");
                }
                return sb.toString();
            }
        };

        filterChain.doFilter(request, responseWrapper);
    }
}
