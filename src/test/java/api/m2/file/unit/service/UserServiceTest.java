package api.m2.file.unit.service;

import api.m2.file.clients.identity.IdentityClient;
import api.m2.file.clients.identity.response.UserLookupResponse;
import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    IdentityClient identityClient;

    UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(identityClient);
    }

    @Test
    void getMe_delegatesToIdentityClient() {
        var me = new UserMe(1L, "a@test.com", "A", "A", "PERSONAL", null);
        when(identityClient.getMe()).thenReturn(me);

        var result = userService.getMe();

        assertThat(result).isEqualTo(me);
    }

    @Test
    void lookupUserByEmail_returnsWhatIdentityClientReturns() {
        var lookup = new UserLookupResponse(1L, "a@test.com");
        when(identityClient.lookupUser("a@test.com")).thenReturn(lookup);

        var result = userService.lookupUserByEmail("a@test.com");

        assertThat(result).isEqualTo(lookup);
    }

    @Test
    void lookupUserByEmail_translatesIdentityRejectionIntoEntityNotFound() {
        doThrow(new RestClientResponseException("Not found", 404, "Not found", null, null, null))
                .when(identityClient).lookupUser("nope@test.com");

        assertThatThrownBy(() -> userService.lookupUserByEmail("nope@test.com"))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
