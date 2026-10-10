package com.dunghaiquyen.ecommerce.modules.auth.service;

import com.dunghaiquyen.ecommerce.common.exception.AccountLockedException;
import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.EmailAlreadyExistsException;
import com.dunghaiquyen.ecommerce.common.exception.InvalidTokenException;
import com.dunghaiquyen.ecommerce.common.security.CustomUserDetails;
import com.dunghaiquyen.ecommerce.common.security.JwtTokenProvider;
import com.dunghaiquyen.ecommerce.common.security.TokenHasher;
import com.dunghaiquyen.ecommerce.config.AppGoogleProperties;
import com.dunghaiquyen.ecommerce.config.JwtProperties;
import com.dunghaiquyen.ecommerce.modules.auth.dto.AuthResponse;
import com.dunghaiquyen.ecommerce.modules.auth.dto.AuthTokensResponse;
import com.dunghaiquyen.ecommerce.modules.auth.dto.LoginRequest;
import com.dunghaiquyen.ecommerce.modules.auth.dto.RegisterRequest;
import com.dunghaiquyen.ecommerce.modules.auth.entity.RefreshToken;
import com.dunghaiquyen.ecommerce.modules.auth.entity.RefreshTokenRevocationReason;
import com.dunghaiquyen.ecommerce.modules.auth.entity.IdentityProvider;
import com.dunghaiquyen.ecommerce.modules.auth.entity.UserIdentity;
import com.dunghaiquyen.ecommerce.modules.auth.repository.RefreshTokenRepository;
import com.dunghaiquyen.ecommerce.modules.auth.repository.UserIdentityRepository;
import com.dunghaiquyen.ecommerce.modules.cart.service.CartMergeService;
import com.dunghaiquyen.ecommerce.modules.user.entity.LoginProvider;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserStatus;
import com.dunghaiquyen.ecommerce.modules.user.mapper.UserMapper;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Refresh tokens are rotated on every use and the DB row is the source of
 * truth for revocation - the JWT signature alone is not enough to consider a
 * refresh token valid. Reuse of an already-revoked token is treated as a
 * possible theft signal and revokes every other active session for that user.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final TokenHasher tokenHasher;
    private final UserMapper userMapper;
    private final RefreshTokenRevocationService refreshTokenRevocationService;
    private final CartMergeService cartMergeService;
    private final AppGoogleProperties googleProperties;
    private final RestClient restClient;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserIdentityRepository userIdentityRepository,
            AuthenticationManager authenticationManager,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties,
            TokenHasher tokenHasher,
            UserMapper userMapper,
            RefreshTokenRevocationService refreshTokenRevocationService,
            CartMergeService cartMergeService,
            AppGoogleProperties googleProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.authenticationManager = authenticationManager;
        this.passwordEncoder = passwordEncoder;
        this.googleProperties = googleProperties;
        this.restClient = RestClient.create();
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.tokenHasher = tokenHasher;
        this.userMapper = userMapper;
        this.refreshTokenRevocationService = refreshTokenRevocationService;
        this.cartMergeService = cartMergeService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, String guestSessionId) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhone(request.phone());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // Always CUSTOMER: RegisterRequest has no role field, so there is nothing for a client to override.
        user.setRole(UserRole.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // race: two concurrent registrations with the same email past the existsByEmail check
            throw new EmailAlreadyExistsException(email);
        }

        // PHASE1_SPEC.md 6.5: merge before returning, so the very first /api/v1/cart
        // call after register already sees the guest's items under the new user.
        cartMergeService.mergeGuestCartIntoUserCart(guestSessionId, user.getId());

        return new AuthResponse(userMapper.toResponse(user), issueTokens(user));
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String guestSessionId) {
        String email = normalizeEmail(request.email());
        // Guard before handing off to DaoAuthenticationProvider: Google users have
        // no password hash, so BCrypt would always fail (or NPE without the
        // CustomUserDetails guard), and the generic "Invalid email or password"
        // message would reach the user with no explanation. Checking here first
        // produces a clear, actionable message instead - "This account was created
        // with Google sign-in" - without leaking whether the email exists to an
        // unauthenticated caller (the message is only different if the account IS
        // found AND IS a Google account, which is the same information a successful
        // Google login would reveal anyway, so this is not an account-enumeration
        // concern beyond what the happy path already exposes).
        userRepository.findByEmail(email)
                .filter(u -> u.getLoginProvider() != LoginProvider.LOCAL)
                .ifPresent(u -> {
                    throw new com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException(
                            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            "This account uses " + u.getLoginProvider().name().toLowerCase() + " sign-in. Please use the corresponding login button.");
                });
        // Delegates to CustomUserDetailsService + CustomUserDetails: throws LockedException
        // for UserStatus.LOCKED (checked before the password) and BadCredentialsException
        // for a wrong password or unknown email - both mapped in GlobalExceptionHandler.
        Authentication authentication =
                authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        User user = ((CustomUserDetails) authentication.getPrincipal()).getUser();

        // Only bookkeeping admin user management (Phase J) actually reads - never
        // gated on, never returned from login itself, so no behavior elsewhere
        // depends on its value or timing.
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        cartMergeService.mergeGuestCartIntoUserCart(guestSessionId, user.getId());

        return new AuthResponse(userMapper.toResponse(user), issueTokens(user));
    }

    @Transactional
    public AuthTokensResponse refresh(String rawRefreshToken) {
        UUID tokenUserId = jwtTokenProvider.parseRefreshToken(rawRefreshToken)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        if (!tokenUserId.equals(stored.getUser().getId())) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Refresh token has expired");
        }

        User user = stored.getUser();

        // One-time-use, race-safe: this single UPDATE is the only place that decides
        // whether a refresh token is "still active". Two concurrent requests for the
        // same token both reach this statement, but only one can match revoked_at IS
        // NULL - Postgres re-checks the predicate when it acquires the row lock, so
        // the loser sees 0 affected rows instead of both passing a stale in-memory check.
        int claimed = refreshTokenRepository.revokeIfActive(
                stored.getId(), Instant.now(), RefreshTokenRevocationReason.ROTATED);
        if (claimed == 0) {
            // Not active anymore: either genuine reuse of an old, already-rotated
            // token, or this request lost the race to a concurrent rotation of the
            // very same token. Runs in its own committed transaction - see
            // RefreshTokenRevocationService javadoc for why this must not share this
            // method's transaction. Known tradeoff documented on
            // RefreshTokenRepository#revokeAllActiveForUser.
            refreshTokenRevocationService.revokeAllActiveForUser(user.getId());
            throw new InvalidTokenException("Refresh token has been revoked");
        }

        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AccountLockedException();
        }

        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken, UUID currentUserId) {
        jwtTokenProvider.parseRefreshToken(rawRefreshToken).ifPresent(tokenUserId -> {
            refreshTokenRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken)).ifPresent(token -> {
                boolean ownedByCaller = token.getUser().getId().equals(currentUserId);
                if (ownedByCaller && token.getRevokedAt() == null) {
                    token.setRevokedAt(Instant.now());
                    token.setRevocationReason(RefreshTokenRevocationReason.LOGOUT);
                    refreshTokenRepository.save(token);
                }
            });
        });
        // Unknown/foreign/already-revoked token: no-op, logout is idempotent and never
        // leaks whether the token existed.
    }

    private AuthTokensResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(tokenHasher.hash(refreshToken));
        entity.setExpiresAt(Instant.now().plus(jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS));
        refreshTokenRepository.save(entity);

        return new AuthTokensResponse(accessToken, refreshToken);
    }

    /**
     * Verifies a Google ID-token sent by the frontend, then finds or creates the
     * corresponding user in this system and issues our own JWT pair - the same
     * AuthResponse shape /login already returns, so the frontend can treat both
     * flows identically after the initial credential hand-off.
     *
     * <p>Token verification uses Google's stateless tokeninfo endpoint (a single
     * GET) rather than local JWKS verification. Tradeoff: one extra network round-
     * trip per login vs the complexity of downloading, caching and rotating Google's
     * public keys ourselves. At this shop's expected concurrent-login volume the
     * round-trip cost is negligible and the failure mode is clear (the endpoint
     * returns 400 for invalid/expired tokens, mapped to a 422 here).
     *
     * <p>Account collision policy: if a LOCAL (email+password) account already
     * exists for the same email, Google login is rejected with a clear message
     * rather than silently merging (which would bypass the password and let
     * anyone with a matching Google account take over a LOCAL account). The
     * reverse also holds: a GOOGLE account holder who tries /login with their
     * email+password is rejected by AuthService.login's own guard.
     */
    @Transactional
    public AuthResponse loginWithGoogle(String credential, String guestSessionId) {
        GoogleTokenInfo tokenInfo = verifyGoogleToken(credential);

        String email = normalizeEmail(tokenInfo.email());
        Optional<UserIdentity> identityBySubject = userIdentityRepository.findByProviderAndProviderSubject(
                IdentityProvider.GOOGLE, tokenInfo.sub());
        Optional<User> existing = identityBySubject.map(UserIdentity::getUser);

        User user;
        if (existing.isPresent()) {
            user = existing.get();
            if (user.getStatus() == UserStatus.LOCKED) {
                throw new AccountLockedException();
            }
            if (user.getStatus() == UserStatus.DEACTIVATED) {
                throw new BusinessRuleException(HttpStatus.FORBIDDEN, "Account is deactivated");
            }
            UserIdentity identity = identityBySubject.orElseThrow();
            identity.setProviderEmail(email);
            identity.setLastLoginAt(Instant.now());
            userIdentityRepository.save(identity);
            user.setLastLoginAt(Instant.now());
            user = userRepository.save(user);
        } else {
            Optional<User> emailOwner = userRepository.findByEmail(email);
            if (emailOwner.isPresent()) {
                throw new BusinessRuleException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "An account with this email already exists. Please use its original sign-in method.");
            }
            user = new User();
            user.setEmail(email);
            user.setFullName(tokenInfo.name() != null && !tokenInfo.name().isBlank()
                    ? tokenInfo.name().trim()
                    : email.split("@")[0]);
            user.setRole(UserRole.CUSTOMER);
            user.setStatus(UserStatus.ACTIVE);
            user.setLastLoginAt(Instant.now());
            try {
                user = userRepository.save(user);
                UserIdentity identity = new UserIdentity();
                identity.setUser(user);
                identity.setProvider(IdentityProvider.GOOGLE);
                identity.setProviderSubject(tokenInfo.sub());
                identity.setProviderEmail(email);
                identity.setLastLoginAt(Instant.now());
                userIdentityRepository.save(identity);
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessRuleException(HttpStatus.CONFLICT, "Account creation conflict, please retry");
            }
        }

        cartMergeService.mergeGuestCartIntoUserCart(guestSessionId, user.getId());
        return new AuthResponse(userMapper.toResponse(user), issueTokens(user));
    }

    private record GoogleTokenInfo(String email, String name, String sub) {
    }

    @SuppressWarnings("unchecked")
    private GoogleTokenInfo verifyGoogleToken(String idToken) {
        Map<String, Object> claims;
        try {
            claims = restClient.get()
                    .uri(googleProperties.tokenInfoUrl() + "?id_token={token}", idToken)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            log.debug("Google tokeninfo rejected credential: {}", ex.getStatusCode());
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid or expired Google credential");
        } catch (Exception ex) {
            log.warn("Google tokeninfo call failed: {}", ex.getMessage());
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Could not verify Google credential, please try again");
        }

        if (claims == null) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Google credential");
        }

        // Validate "aud" matches our configured client ID to prevent token substitution
        // (a token issued by Google for app A used to authenticate to app B).
        // Skipped when clientId is not configured (empty = dev/test convenience).
        String clientId = googleProperties.clientId();
        if (clientId != null && !clientId.isBlank()) {
            String aud = String.valueOf(claims.getOrDefault("aud", ""));
            if (!clientId.equals(aud)) {
                throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Google credential was not issued for this application");
            }
        }

        String emailVerified = String.valueOf(claims.getOrDefault("email_verified", "false"));
        if (!"true".equals(emailVerified)) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Google account email is not verified");
        }

        String email = (String) claims.get("email");
        if (email == null || email.isBlank()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not retrieve email from Google credential");
        }

        return new GoogleTokenInfo(email, (String) claims.get("name"), (String) claims.get("sub"));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
