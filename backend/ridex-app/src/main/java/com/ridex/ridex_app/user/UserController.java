package com.ridex.ridex_app.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "The logged-in user's own account")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Who am I?",
            description = "Reads the current account from the database, so a suspended user is "
                    + "refused even with a still-valid token.")
    public UserProfileResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return UserProfileResponse.from(userService.getActiveUser(principal.id()));
    }
}
