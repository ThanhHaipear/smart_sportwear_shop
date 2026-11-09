package com.dunghaiquyen.ecommerce.modules.user.service;

import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.user.api.UserReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.user.dto.UpdateMeRequest;
import com.dunghaiquyen.ecommerce.modules.user.dto.UserResponse;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserStatus;
import com.dunghaiquyen.ecommerce.modules.user.mapper.UserMapper;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service only (GET/PATCH /api/v1/me). Admin user management (list,
 * lock/unlock, role change) is a separate concern, out of scope here.
 */
@Service
public class UserService implements UserReferenceQuery {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public User getReference(UUID userId) {
        return userRepository.getReferenceById(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findActiveAdmins() {
        return userRepository.findAllByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(UUID userId) {
        return userMapper.toResponse(findUser(userId));
    }

    @Transactional
    public UserResponse updateMe(UUID userId, UpdateMeRequest request) {
        User user = findUser(userId);
        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        return userMapper.toResponse(userRepository.save(user));
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
