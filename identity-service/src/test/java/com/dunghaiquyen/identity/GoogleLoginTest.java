package com.dunghaiquyen.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.dunghaiquyen.identity.config.IdentityProperties;
import com.dunghaiquyen.identity.domain.IdentityService;
import com.dunghaiquyen.identity.persistence.IdentityRepository;
import com.dunghaiquyen.identity.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.Optional;
import java.util.UUID;
import com.dunghaiquyen.identity.domain.IdentityModels.User;
import com.dunghaiquyen.identity.domain.IdentityModels.Role;
import com.dunghaiquyen.identity.domain.IdentityModels.Status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleLoginTest {
    @Test void rejectsGoogleTokenIssuedForAnotherApplication() {
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://google.test/tokeninfo?id_token=credential")).andRespond(withSuccess("{\"email_verified\":\"true\",\"aud\":\"wrong-client\",\"email\":\"a@example.com\",\"sub\":\"subject\"}", MediaType.APPLICATION_JSON));
        IdentityService service=new IdentityService(mock(IdentityRepository.class),mock(JwtService.class),mock(PasswordEncoder.class),new IdentityProperties.Google("expected-client","https://google.test/tokeninfo"),builder.build());
        assertThatThrownBy(()->service.google("credential")).isInstanceOf(IdentityService.ApiFailure.class).hasMessageContaining("audience");server.verify();
    }

    @Test void acceptsVerifiedGoogleTokenAndIssuesShopTokens() {
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://google.test/tokeninfo?id_token=valid")).andRespond(withSuccess("{\"email_verified\":\"true\",\"aud\":\"expected-client\",\"email\":\"google@example.com\",\"name\":\"Google User\",\"sub\":\"google-subject\"}",MediaType.APPLICATION_JSON));
        IdentityRepository repository=mock(IdentityRepository.class);JwtService jwt=mock(JwtService.class);User user=new User(UUID.randomUUID(),"Google User","google@example.com",null,null,Role.CUSTOMER,Status.ACTIVE,"GOOGLE",1);
        when(repository.userByGoogleSubject("google-subject")).thenReturn(Optional.empty());when(repository.userByEmail("google@example.com")).thenReturn(Optional.empty());when(repository.createGoogle("Google User","google@example.com","google-subject")).thenReturn(user);when(repository.userById(user.id())).thenReturn(Optional.of(user));when(jwt.access(user.id(),Role.CUSTOMER)).thenReturn("access");when(jwt.refresh(user.id())).thenReturn("refresh");when(jwt.refreshTtlDays()).thenReturn(30L);
        IdentityService service=new IdentityService(repository,jwt,mock(PasswordEncoder.class),new IdentityProperties.Google("expected-client","https://google.test/tokeninfo"),builder.build());
        org.assertj.core.api.Assertions.assertThat(service.google("valid").tokens().accessToken()).isEqualTo("access");server.verify();
    }
}
