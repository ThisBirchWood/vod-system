package com.ddf.vodsystem.auth;

import com.ddf.vodsystem.user.User;

/**
 * Guards for service methods that act on behalf of a user resolved by the controller.
 */
public final class AuthGuard {
    private AuthGuard() {
    }

    /**
     * Ensures a user is present, so a missing principal fails as {@code 401} instead of an NPE.
     *
     * @param user the user resolved from the request, possibly {@code null}
     * @return {@code user}, guaranteed non-null
     * @throws NotAuthenticatedException if {@code user} is {@code null}
     */
    public static User requireUser(User user) {
        if (user == null) {
            throw new NotAuthenticatedException("User is not authenticated");
        }
        return user;
    }
}
