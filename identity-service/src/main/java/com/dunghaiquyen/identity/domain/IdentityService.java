package com.dunghaiquyen.identity.domain;

import com.dunghaiquyen.identity.config.IdentityProperties;
import com.dunghaiquyen.identity.domain.IdentityModels.Status;
import com.dunghaiquyen.identity.domain.IdentityModels.User;
import com.dunghaiquyen.identity.persistence.IdentityRepository;
import com.dunghaiquyen.identity.security.JwtService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class IdentityService {
    private final IdentityRepository repository;
    private final JwtService jwt;
    private final PasswordEncoder passwords;
    private final IdentityProperties.Google google;
    private final RestClient rest;

    public IdentityService(IdentityRepository repository, JwtService jwt, PasswordEncoder passwords, IdentityProperties.Google google, RestClient googleRestClient) {
        this.repository=repository; this.jwt=jwt; this.passwords=passwords; this.google=google; this.rest=googleRestClient;
    }

    @Transactional
    public AuthResult register(String fullName,String email,String phone,String password) {
        String normalized=email.trim().toLowerCase();
        try { return authenticated(repository.createLocal(fullName.trim(),normalized,phone,passwords.encode(password))); }
        catch(DataIntegrityViolationException ex){throw new ApiFailure(HttpStatus.CONFLICT,"Email already exists");}
    }

    @Transactional
    public AuthResult login(String email,String password) {
        User user=repository.userByEmail(email.trim().toLowerCase()).orElseThrow(()->new ApiFailure(HttpStatus.UNAUTHORIZED,"Invalid email or password"));
        if(!"LOCAL".equals(user.loginProvider())) throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY,"This account uses Google sign-in");
        if(user.status()!=Status.ACTIVE) throw new ApiFailure(HttpStatus.FORBIDDEN,"Account is not active");
        if(!passwords.matches(password,user.passwordHash())) throw new ApiFailure(HttpStatus.UNAUTHORIZED,"Invalid email or password");
        repository.touchLogin(user.id()); return authenticated(repository.userById(user.id()).orElseThrow());
    }

    @Transactional(noRollbackFor=ApiFailure.class)
    public Tokens refresh(String raw) {
        UUID signedUser=jwt.parseRefresh(raw).orElseThrow(()->new ApiFailure(HttpStatus.UNAUTHORIZED,"Invalid refresh token"));
        var stored=repository.refresh(raw).orElseThrow(()->new ApiFailure(HttpStatus.UNAUTHORIZED,"Invalid refresh token"));
        if(!signedUser.equals(stored.userId())||stored.expiresAt().isBefore(Instant.now())) throw new ApiFailure(HttpStatus.UNAUTHORIZED,"Invalid refresh token");
        if(repository.claimRefresh(stored.id())==0){repository.revokeAll(signedUser,"REUSE_DETECTED");throw new ApiFailure(HttpStatus.UNAUTHORIZED,"Refresh token has been revoked");}
        User user=repository.userById(signedUser).orElseThrow(()->new ApiFailure(HttpStatus.UNAUTHORIZED,"Invalid refresh token"));
        if(user.status()!=Status.ACTIVE) throw new ApiFailure(HttpStatus.FORBIDDEN,"Account is not active");
        return tokens(user);
    }

    @Transactional public void logout(String raw,UUID caller){repository.logout(raw,caller);}

    @Transactional
    public AuthResult google(String credential) {
        Map<?,?> claims;
        try { claims=rest.get().uri(google.tokenInfoUrl()+"?id_token={token}",credential).retrieve().body(Map.class); }
        catch(Exception ex){throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid or expired Google credential");}
        if(claims==null||!"true".equals(String.valueOf(claims.get("email_verified")))) throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY,"Google email is not verified");
        if(google.clientId()!=null&&!google.clientId().isBlank()&&!google.clientId().equals(String.valueOf(claims.get("aud")))) throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY,"Google credential has an invalid audience");
        String email=String.valueOf(claims.get("email")).trim().toLowerCase(), subject=String.valueOf(claims.get("sub"));
        if(email.isBlank()||subject.isBlank()) throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid Google credential");
        User user=repository.userByGoogleSubject(subject).orElseGet(()->{
            if(repository.userByEmail(email).isPresent()) throw new ApiFailure(HttpStatus.CONFLICT,"Email already uses another sign-in method");
            String name=claims.get("name")==null?email.substring(0,email.indexOf('@')):String.valueOf(claims.get("name"));
            return repository.createGoogle(name,email,subject);
        });
        if(user.status()!=Status.ACTIVE) throw new ApiFailure(HttpStatus.FORBIDDEN,"Account is not active");
        repository.touchLogin(user.id()); return authenticated(repository.userById(user.id()).orElseThrow());
    }

    @Transactional
    public String requestPasswordReset(String email) {
        return repository.userByEmail(email.trim().toLowerCase()).filter(u->"LOCAL".equals(u.loginProvider())).map(u->{
            String raw=UUID.randomUUID()+""+UUID.randomUUID(); repository.createReset(u.id(),raw,Instant.now().plus(30,ChronoUnit.MINUTES)); return raw;
        }).orElse(null);
    }

    @Transactional
    public void resetPassword(String raw,String password) {
        var row=repository.reset(raw).orElseThrow(()->new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid or expired reset token"));
        if(row.usedAt()!=null||row.expiresAt().isBefore(Instant.now())||repository.claimReset(row.id())==0) throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid or expired reset token");
        repository.updatePassword(row.userId(),passwords.encode(password)); repository.revokeAll(row.userId(),"PASSWORD_RESET");
    }

    private AuthResult authenticated(User user){return new AuthResult(new PublicUser(user.id(),user.fullName(),user.email(),user.phone(),user.role(),user.status(),user.loginProvider()),tokens(user));}
    private Tokens tokens(User user){String access=jwt.access(user.id(),user.role()),refresh=jwt.refresh(user.id());repository.saveRefresh(user.id(),refresh,Instant.now().plus(jwt.refreshTtlDays(),ChronoUnit.DAYS));return new Tokens(access,refresh);}
    public record Tokens(String accessToken,String refreshToken){}
    public record PublicUser(UUID id,String fullName,String email,String phone,IdentityModels.Role role,Status status,String loginProvider){}
    public record AuthResult(PublicUser user,Tokens tokens){}
    public static class ApiFailure extends RuntimeException { private final HttpStatus status; public ApiFailure(HttpStatus status,String message){super(message);this.status=status;} public HttpStatus status(){return status;} }
}
