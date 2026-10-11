package com.ridex.ridex_app.user;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ForbiddenException;
import com.ridex.ridex_app.common.ResourceNotFoundException;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads the user from the database. Unlike the token, the database knows the CURRENT
     * status, so a suspended user is stopped here even if their token is still valid.
     */
    @Transactional(readOnly = true)
    public User getActiveUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("This account is not active");
        }
        return user;
    }
}
