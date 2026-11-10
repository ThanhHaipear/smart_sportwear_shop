package com.dunghaiquyen.identity.api;

import com.dunghaiquyen.identity.domain.IdentityModels.Address;
import com.dunghaiquyen.identity.domain.IdentityModels.Role;
import com.dunghaiquyen.identity.domain.IdentityModels.Status;
import com.dunghaiquyen.identity.domain.IdentityModels.User;
import com.dunghaiquyen.identity.domain.IdentityService;
import com.dunghaiquyen.identity.persistence.IdentityRepository;
import com.dunghaiquyen.identity.domain.PasswordResetDelivery;
import com.dunghaiquyen.identity.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityApi {
    private final IdentityService service; private final IdentityRepository repository; private final PasswordResetDelivery resetDelivery;
    public IdentityApi(IdentityService service,IdentityRepository repository,PasswordResetDelivery resetDelivery){this.service=service;this.repository=repository;this.resetDelivery=resetDelivery;}

    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public Envelope<IdentityService.AuthResult> register(@Valid @RequestBody Register request){return Envelope.ok("Register successful",service.register(request.fullName(),request.email(),request.phone(),request.password()));}
    @PostMapping("/auth/login") public Envelope<IdentityService.AuthResult> login(@Valid @RequestBody Login request){return Envelope.ok("Login successful",service.login(request.email(),request.password()));}
    @PostMapping("/auth/refresh") public Envelope<IdentityService.Tokens> refresh(@Valid @RequestBody Token request){return Envelope.ok("Token refreshed",service.refresh(request.refreshToken()));}
    @PostMapping("/auth/logout") public Envelope<Map<String,Object>> logout(@AuthenticationPrincipal JwtService.Principal principal,@Valid @RequestBody Token request){service.logout(request.refreshToken(),principal.userId());return Envelope.ok("Logout successful",Map.of());}
    @PostMapping("/auth/google") public Envelope<IdentityService.AuthResult> google(@Valid @RequestBody Google request){return Envelope.ok("Login successful",service.google(request.credential()));}
    @PostMapping("/auth/forgot-password") public Envelope<Map<String,Object>> forgot(@Valid @RequestBody Forgot request){String token=service.requestPasswordReset(request.email());if(token!=null)resetDelivery.send(request.email(),token);return Envelope.ok("If the email exists, a reset instruction has been sent",Map.of());}
    @PostMapping("/auth/reset-password") public Envelope<Map<String,Object>> reset(@Valid @RequestBody Reset request){service.resetPassword(request.token(),request.newPassword());return Envelope.ok("Password reset successful",Map.of());}

    @GetMapping("/users/me") public Envelope<UserView> me(@AuthenticationPrincipal JwtService.Principal p){return Envelope.ok(UserView.of(repository.userById(p.userId()).orElseThrow()));}
    @PatchMapping("/users/me") public Envelope<UserView> updateMe(@AuthenticationPrincipal JwtService.Principal p,@Valid @RequestBody Profile request){return Envelope.ok(UserView.of(repository.updateProfile(p.userId(),request.fullName(),request.phone())));}
    @GetMapping("/users/me/addresses") public Envelope<List<Address>> addresses(@AuthenticationPrincipal JwtService.Principal p){return Envelope.ok(repository.addresses(p.userId()));}
    @PostMapping("/users/me/addresses") @ResponseStatus(HttpStatus.CREATED) public Envelope<Address> createAddress(@AuthenticationPrincipal JwtService.Principal p,@Valid @RequestBody AddressBody r){return Envelope.ok(repository.createAddress(p.userId(),r.receiverName(),r.phone(),r.province(),r.district(),r.ward(),r.addressLine(),Boolean.TRUE.equals(r.isDefault())));}
    @PatchMapping("/users/me/addresses/{id}") public Envelope<Address> updateAddress(@AuthenticationPrincipal JwtService.Principal p,@PathVariable UUID id,@Valid @RequestBody AddressPatch r){return Envelope.ok(repository.updateAddress(p.userId(),id,r.receiverName(),r.phone(),r.province(),r.district(),r.ward(),r.addressLine()));}
    @DeleteMapping("/users/me/addresses/{id}") public Envelope<Void> deleteAddress(@AuthenticationPrincipal JwtService.Principal p,@PathVariable UUID id){repository.deleteAddress(p.userId(),id);return Envelope.ok(null);}
    @PatchMapping("/users/me/addresses/{id}/default") public Envelope<Address> defaultAddress(@AuthenticationPrincipal JwtService.Principal p,@PathVariable UUID id){return Envelope.ok(repository.setDefault(p.userId(),id));}

    @PatchMapping("/admin/users/{id}/role") @PreAuthorize("hasRole('ADMIN')") public Envelope<UserView> role(@PathVariable UUID id,@RequestBody RoleBody body){return Envelope.ok(UserView.of(repository.updateRole(id,body.role())));}
    @PatchMapping("/admin/users/{id}/status") @PreAuthorize("hasRole('ADMIN')") public Envelope<UserView> status(@PathVariable UUID id,@RequestBody StatusBody body){return Envelope.ok(UserView.of(repository.updateStatus(id,body.status())));}

    public record Register(@NotBlank @Size(max=150) String fullName,@NotBlank @Email String email,@Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).{8,72}$") String password,@Pattern(regexp="^[0-9+\\-() ]{8,20}$") String phone){}
    public record Login(@NotBlank @Email String email,@NotBlank String password){}
    public record Token(@NotBlank String refreshToken){}
    public record Google(@NotBlank String credential){}
    public record Forgot(@NotBlank @Email String email){}
    public record Reset(@NotBlank String token,@Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).{8,72}$") String newPassword){}
    public record Profile(@Size(max=150) String fullName,@Pattern(regexp="^[0-9+\\-() ]{8,20}$") String phone){}
    public record AddressBody(@NotBlank String receiverName,@Pattern(regexp="^[0-9+\\-() ]{8,20}$") String phone,@NotBlank String province,String district,@NotBlank String ward,@NotBlank String addressLine,Boolean isDefault){}
    public record AddressPatch(String receiverName,String phone,String province,String district,String ward,String addressLine){}
    public record RoleBody(Role role){} public record StatusBody(Status status){}
    public record UserView(UUID id,String fullName,String email,String phone,Role role,Status status,String loginProvider){static UserView of(User u){return new UserView(u.id(),u.fullName(),u.email(),u.phone(),u.role(),u.status(),u.loginProvider());}}
    public record Envelope<T>(boolean success,String message,T data,Object meta,Object errors,String traceId){static <T> Envelope<T> ok(T data){return ok("OK",data);} static <T> Envelope<T> ok(String message,T data){return new Envelope<>(true,message,data,null,null,null);}}
}
