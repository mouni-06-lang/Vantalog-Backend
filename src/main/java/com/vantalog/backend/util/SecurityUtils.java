package com.vantalog.backend.util;

import com.vantalog.backend.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {
    }

    public static AuthenticatedUser requireUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return user;
    }

    public static AuthenticatedUser requireAdmin() {
        AuthenticatedUser user = requireUser();
        if (!"admin".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
        }
        return user;
    }
}
