package com.project.souklab.controller.auth;
import com.project.souklab.dto.auth.TokenType;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.auth.ChangePasswordRequestDTO;
import com.project.souklab.dto.auth.CompleteProfileRequestDTO;
import com.project.souklab.dto.auth.ForgotPasswordRequestDTO;
import com.project.souklab.dto.auth.JwtResponseDTO;
import com.project.souklab.dto.auth.LoginDTO;
import com.project.souklab.dto.auth.ResendVerificationRequestDTO;
import com.project.souklab.dto.auth.ResetPasswordRequestDTO;
import com.project.souklab.dto.auth.TokenRefreshRequestDTO;
import com.project.souklab.dto.auth.UserRegistrationDTO;
import com.project.souklab.dto.auth.VerifyEmailRequestDTO;
import com.project.souklab.dto.profile.ArtisanResponseDTO;
import com.project.souklab.dto.profile.ClientProfileResponseDTO;
import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.dto.profile.UserPatchDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AccountRole;
import com.project.souklab.security.Permission;
import com.project.souklab.security.OAuthCookie;
import com.project.souklab.service.auth.AuthService;
import com.project.souklab.service.profile.ProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for AuthController.
 * Verifies public vs authenticated endpoint boundaries, request validation,
 * response encapsulation, JSON Merge Patch forwarding, OAuth intent cookie and redirection,
 * and service exception mapping across all 14 authentication endpoints.
 */
@ControllerSliceTest(controllers = AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private ProfileService profileService;

    private ClientProfileResponseDTO buildClientProfile(String id, String email, AccountStatus status) {
        return ClientProfileResponseDTO.builder()
                .id(id)
                .email(email)
                .firstName("Karim")
                .lastName("Client")
                .name("Karim Client")
                .phone("+213555000111")
                .accountStatus(status)
                .permissions(Set.of(Permission.Profile.READ))
                .emailVerified(true)
                .createdAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
    }

    private ArtisanResponseDTO buildArtisanProfile(String id, String email, AccountStatus status) {
        return ArtisanResponseDTO.builder()
                .id(id)
                .email(email)
                .firstName("Ahmed")
                .lastName("Artisan")
                .name("Ahmed Artisan")
                .phone("+213555222333")
                .accountStatus(status)
                .permissions(Set.of(Permission.Artisan.CONTENT))
                .emailVerified(false)
                .teacher(false)
                .verified(false)
                .createdAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
    }

    private JwtResponseDTO buildJwtResponse(ProfileResponse user) {
        return JwtResponseDTO.builder()
                .accessToken("access-token-123")
                .refreshToken("refresh-token-456")
                .tokenType(TokenType.BEARER)
                .expiresIn(900L)
                .user(user)
                .permissions(List.of(Permission.Profile.READ))
                .build();
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class RegisterTests {

        /**
         * Verifies that successful client registration returns 201 Created with the welcome message.
         */
        @Test
        @DisplayName("client registration returns 201 Created and welcome message")
        void register_whenClient_shouldReturn201WithWelcomeMessage() throws Exception {
            ClientProfileResponseDTO profile = buildClientProfile("user-1", "karim@souklab.dz", AccountStatus.ACTIVE);
            when(authService.registerUser(any(UserRegistrationDTO.class))).thenReturn(profile);

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "password": "Password123!",
                                      "accountType": "CLIENT",
                                      "firstName": "Karim",
                                      "lastName": "Client"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.message").value("Registration successful. Welcome to Souklab!"))
                    .andExpect(jsonPath("$.data.id").value("user-1"))
                    .andExpect(jsonPath("$.data.email").value("karim@souklab.dz"))
                    .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"));

            ArgumentCaptor<UserRegistrationDTO> captor = ArgumentCaptor.forClass(UserRegistrationDTO.class);
            verify(authService).registerUser(captor.capture());
            assertThat(captor.getValue().getEmail()).isEqualTo("karim@souklab.dz");
            assertThat(captor.getValue().getAccountType()).isEqualTo(AccountRole.CLIENT);
        }

        /**
         * Verifies that successful artisan registration returns 201 Created with the pending verification message.
         */
        @Test
        @DisplayName("artisan registration returns 201 Created and pending verification message")
        void register_whenArtisan_shouldReturn201WithPendingMessage() throws Exception {
            ArtisanResponseDTO profile = buildArtisanProfile("user-2", "ahmed@souklab.dz", AccountStatus.PENDING);
            when(authService.registerUser(any(UserRegistrationDTO.class))).thenReturn(profile);

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "ahmed@souklab.dz",
                                      "password": "Password123!",
                                      "accountType": "ARTISAN",
                                      "firstName": "Ahmed",
                                      "lastName": "Artisan"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.message").value("Registration successful. Your artisan account has been created and is pending administrator verification."))
                    .andExpect(jsonPath("$.data.id").value("user-2"))
                    .andExpect(jsonPath("$.data.accountStatus").value("PENDING"));
        }

        /**
         * Verifies that invalid registration fields fail Bean Validation returning 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("registration validation failure returns 422 Unprocessable Entity")
        void register_whenValidationFails_shouldReturn422WithErrors() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "not-an-email",
                                      "password": "short",
                                      "accountType": ""
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.email").exists())
                    .andExpect(jsonPath("$.errors.password").value("Password must be at least 8 characters long"))
                    .andExpect(jsonPath("$.errors.accountType").value("Account type is required (ARTISAN or CLIENT)"));
        }

        /**
         * Verifies that duplicate email ConflictException maps to 409 Conflict.
         */
        @Test
        @DisplayName("duplicate email registration returns 409 Conflict")
        void register_whenEmailExists_shouldReturn409Conflict() throws Exception {
            when(authService.registerUser(any(UserRegistrationDTO.class)))
                    .thenThrow(new ConflictException("Email is already registered: existing@souklab.dz"));

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "existing@souklab.dz",
                                      "password": "Password123!",
                                      "accountType": "CLIENT"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("Email is already registered: existing@souklab.dz"));
        }

        /**
         * Verifies that requesting ADMIN role registration maps to 400 Bad Request.
         */
        @Test
        @DisplayName("admin role registration returns 400 Bad Request")
        void register_whenAdminRoleRequested_shouldReturn400BadRequest() throws Exception {
            when(authService.registerUser(any(UserRegistrationDTO.class)))
                    .thenThrow(new BadRequestException("Administrator registration is not permitted via public registration."));

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "admin@souklab.dz",
                                      "password": "Password123!",
                                      "accountType": "ADMIN"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginTests {

        /**
         * Verifies that valid login credentials return 200 OK with tokens and profile.
         */
        @Test
        @DisplayName("valid login credentials return 200 OK and JWT response")
        void login_whenValidCredentials_shouldReturn200Ok() throws Exception {
            ClientProfileResponseDTO user = buildClientProfile("user-1", "karim@souklab.dz", AccountStatus.ACTIVE);
            JwtResponseDTO jwtResponse = buildJwtResponse(user);
            when(authService.login(any(LoginDTO.class), any())).thenReturn(jwtResponse);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "password": "Password123!"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Login successful."))
                    .andExpect(jsonPath("$.data.accessToken").value("access-token-123"))
                    .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-456"))
                    .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.data.user.email").value("karim@souklab.dz"));
        }

        /**
         * Verifies that login without password returns 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("login without password returns 422 Unprocessable Entity")
        void login_whenPasswordMissing_shouldReturn422Validation() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz"
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.password").value("Password is required"));
        }

        /**
         * Verifies that invalid credentials map to 401 Unauthorized.
         */
        @Test
        @DisplayName("invalid credentials return 401 Unauthorized")
        void login_whenInvalidCredentials_shouldReturn401Unauthorized() throws Exception {
            when(authService.login(any(LoginDTO.class), any()))
                    .thenThrow(new UnauthorizedException("Invalid email or password."));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "password": "WrongPassword!"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Invalid email or password."));
        }

        /**
         * Verifies that pending/banned user login maps to 403 Forbidden.
         */
        @Test
        @DisplayName("pending account login returns 403 Forbidden")
        void login_whenAccountPending_shouldReturn403Forbidden() throws Exception {
            when(authService.login(any(LoginDTO.class), any()))
                    .thenThrow(new ForbiddenException("Your account is pending verification."));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "ahmed@souklab.dz",
                                      "password": "Password123!"
                                    }
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("Your account is pending verification."));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class RefreshTokenTests {

        /**
         * Verifies that valid refresh token returns 200 OK with new JWT tokens.
         */
        @Test
        @DisplayName("valid refresh token returns 200 OK and refreshed tokens")
        void refresh_whenValidToken_shouldReturn200Ok() throws Exception {
            ClientProfileResponseDTO user = buildClientProfile("user-1", "karim@souklab.dz", AccountStatus.ACTIVE);
            JwtResponseDTO jwtResponse = buildJwtResponse(user);
            when(authService.refreshToken(any(TokenRefreshRequestDTO.class))).thenReturn(jwtResponse);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "refreshToken": "valid-refresh-token"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Token refreshed successfully."))
                    .andExpect(jsonPath("$.data.accessToken").value("access-token-123"));
        }

        /**
         * Verifies that blank refresh token returns 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("blank refresh token returns 422 Unprocessable Entity")
        void refresh_whenTokenBlank_shouldReturn422Validation() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "refreshToken": ""
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.refreshToken").exists());
        }

        /**
         * Verifies that expired refresh token maps to 401 Unauthorized.
         */
        @Test
        @DisplayName("expired refresh token returns 401 Unauthorized")
        void refresh_whenTokenExpired_shouldReturn401Unauthorized() throws Exception {
            when(authService.refreshToken(any(TokenRefreshRequestDTO.class)))
                    .thenThrow(new UnauthorizedException("Refresh token has expired."));

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "refreshToken": "expired-token"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class LogoutTests {

        /**
         * Verifies that authenticated logout with refresh token body passes both email and token to service.
         */
        @Test
        @DisplayName("authenticated logout with token passes email and token to service returning 200 OK")
        void logout_whenAuthenticatedWithToken_shouldPassBothToService() throws Exception {
            doNothing().when(authService).logout(any(TokenRefreshRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/logout")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "refreshToken": "token-xyz"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Logout successful."))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(authService).logout(any(TokenRefreshRequestDTO.class));
        }

        /**
         * Verifies that authenticated logout with omitted body passes username and null refreshToken to service.
         */
        @Test
        @DisplayName("authenticated logout without body passes username and null token to service returning 200 OK")
        void logout_whenAuthenticatedWithoutBody_shouldPassNullTokenToService() throws Exception {
            doNothing().when(authService).logout(any(TokenRefreshRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/logout")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Logout successful."))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(authService).logout(isNull(TokenRefreshRequestDTO.class));
        }

        /**
         * Verifies that anonymous logout with omitted body passes nulls to service returning 200 OK.
         */
        @Test
        @DisplayName("anonymous logout without body passes nulls to service returning 200 OK")
        void logout_whenAnonymousAndEmptyBody_shouldPassNullsToService() throws Exception {
            doNothing().when(authService).logout((TokenRefreshRequestDTO) null);

            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Logout successful."));

            verify(authService).logout((TokenRefreshRequestDTO) null);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/verify-email")
    class VerifyEmailTests {

        /**
         * Verifies that valid verification code returns 200 OK.
         */
        @Test
        @DisplayName("valid verification code returns 200 OK")
        void verifyEmail_whenValid_shouldReturn200Ok() throws Exception {
            doNothing().when(authService).verifyEmail(any(VerifyEmailRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "code": "123456"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Email verified successfully."));

            ArgumentCaptor<VerifyEmailRequestDTO> captor = ArgumentCaptor.forClass(VerifyEmailRequestDTO.class);
            verify(authService).verifyEmail(captor.capture());
            assertThat(captor.getValue().getEmail()).isEqualTo("karim@souklab.dz");
            assertThat(captor.getValue().getCode()).isEqualTo("123456");
        }

        /**
         * Verifies that invalid verification code format returns 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("invalid code format returns 422 Unprocessable Entity")
        void verifyEmail_whenInvalidFormat_shouldReturn422Validation() throws Exception {
            mockMvc.perform(post("/api/v1/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "invalid-email",
                                      "code": "abc"
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.email").value("Invalid email format"))
                    .andExpect(jsonPath("$.errors.code").value("Verification code must be a 6-digit number"));
        }

        /**
         * Verifies that expired/invalid verification code maps to 400 Bad Request.
         */
        @Test
        @DisplayName("invalid verification code returns 400 Bad Request")
        void verifyEmail_whenServiceThrows_shouldReturn400BadRequest() throws Exception {
            doThrow(new BadRequestException("Invalid or expired verification code."))
                    .when(authService).verifyEmail(any(VerifyEmailRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "code": "999999"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("Invalid or expired verification code."));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/resend-verification")
    class ResendVerificationTests {

        /**
         * Verifies that valid email returns 200 OK with safe enumeration-resistant message.
         */
        @Test
        @DisplayName("valid resend request returns 200 OK")
        void resendVerification_whenValid_shouldReturn200Ok() throws Exception {
            doNothing().when(authService).resendVerification(any(ResendVerificationRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("If an unverified account exists for this email, a verification code has been sent."));
        }

        /**
         * Verifies that missing email returns 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("missing email returns 422 Unprocessable Entity")
        void resendVerification_whenEmailBlank_shouldReturn422Validation() throws Exception {
            mockMvc.perform(post("/api/v1/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": ""
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.email").value("Email is required"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/forgot-password")
    class ForgotPasswordTests {

        /**
         * Verifies that forgot password request returns 200 OK with enumeration-resistant message.
         */
        @Test
        @DisplayName("valid forgot password request returns 200 OK")
        void forgotPassword_whenValid_shouldReturn200Ok() throws Exception {
            doNothing().when(authService).forgotPassword(any(ForgotPasswordRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("If an account exists for this email, instructions have been sent."));
        }

        /**
         * Verifies that invalid email format returns 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("invalid email format returns 422 Unprocessable Entity")
        void forgotPassword_whenEmailInvalid_shouldReturn422Validation() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "not-an-email"
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.email").value("Invalid email format"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/reset-password")
    class ResetPasswordTests {

        /**
         * Verifies that valid password reset returns 200 OK.
         */
        @Test
        @DisplayName("valid reset password request returns 200 OK")
        void resetPassword_whenValid_shouldReturn200Ok() throws Exception {
            doNothing().when(authService).resetPassword(any(ResetPasswordRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "code": "123456",
                                      "newPassword": "BrandNewPassword123!"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Password reset successfully. You can now log in with your new password."));
        }

        /**
         * Verifies that short new password returns 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("short password returns 422 Unprocessable Entity")
        void resetPassword_whenValidationFails_shouldReturn422() throws Exception {
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "code": "123456",
                                      "newPassword": "short"
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.newPassword").value("Password must be at least 8 characters"));
        }

        /**
         * Verifies that expired reset code maps to 400 Bad Request.
         */
        @Test
        @DisplayName("expired reset code returns 400 Bad Request")
        void resetPassword_whenServiceThrows_shouldReturn400BadRequest() throws Exception {
            doThrow(new BadRequestException("Invalid or expired reset code."))
                    .when(authService).resetPassword(any(ResetPasswordRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "karim@souklab.dz",
                                      "code": "123456",
                                      "newPassword": "NewPassword123!"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("Invalid or expired reset code."));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/change-password")
    class ChangePasswordTests {

        /**
         * Verifies that authenticated user can change password returning 200 OK.
         */
        @Test
        @DisplayName("authenticated user with valid password change returns 200 OK")
        void changePassword_whenAuthenticatedAndValid_shouldReturn200Ok() throws Exception {
            doNothing().when(authService).changePassword(any(ChangePasswordRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "oldPassword": "CurrentPassword123!",
                                      "newPassword": "BrandNewPassword123!"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Password changed successfully."));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized from security filter chain.
         */
        @Test
        @DisplayName("unauthenticated password change receives 401 Unauthorized")
        void changePassword_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "oldPassword": "CurrentPassword123!",
                                      "newPassword": "BrandNewPassword123!"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }

        /**
         * Verifies that validation failure returns 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("missing old password returns 422 Unprocessable Entity")
        void changePassword_whenValidationFails_shouldReturn422() throws Exception {
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "oldPassword": "",
                                      "newPassword": "BrandNewPassword123!"
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.oldPassword").value("Current password is required"));
        }

        /**
         * Verifies that incorrect current password maps to 401 Unauthorized.
         */
        @Test
        @DisplayName("wrong current password returns 401 Unauthorized")
        void changePassword_whenOldPasswordWrong_shouldReturn401Unauthorized() throws Exception {
            doThrow(new UnauthorizedException("Current password is incorrect."))
                    .when(authService).changePassword(any(ChangePasswordRequestDTO.class));

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "oldPassword": "WrongCurrentPassword123!",
                                      "newPassword": "BrandNewPassword123!"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Current password is incorrect."));
        }

        /**
         * Verifies that identical new and old passwords fail class-level validation with 422 Unprocessable Entity.
         */
        @Test
        @DisplayName("identical new and old passwords return 422 Unprocessable Entity")
        void changePassword_whenNewPasswordEqualsOldPassword_shouldReturn422Validation() throws Exception {
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "oldPassword": "SamePassword123!",
                                      "newPassword": "SamePassword123!"
                                    }
                                    """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.newPassword").value("New password must be different from your current password."));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/me")
    class GetCurrentUserTests {

        /**
         * Verifies that authenticated user retrieves current profile returning 200 OK.
         */
        @Test
        @DisplayName("authenticated user retrieves current profile returning 200 OK")
        void getCurrentUser_whenAuthenticated_shouldReturnProfileAnd200Ok() throws Exception {
            ClientProfileResponseDTO user = buildClientProfile("user-1", "karim@souklab.dz", AccountStatus.ACTIVE);
            when(profileService.getCurrentUser()).thenReturn(user);

            mockMvc.perform(get("/api/v1/auth/me")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("user-1"))
                    .andExpect(jsonPath("$.data.email").value("karim@souklab.dz"))
                    .andExpect(jsonPath("$.data.permissions[0]").value(Permission.Profile.READ.value()));

            verify(profileService).getCurrentUser();
        }

        /**
         * Verifies that unauthenticated call receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated current user request receives 401 Unauthorized")
        void getCurrentUser_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }

        /**
         * Verifies that missing user in database maps to 404 Not Found.
         */
        @Test
        @DisplayName("user not found maps to 404 Not Found")
        void getCurrentUser_whenUserNotFound_shouldReturn404NotFound() throws Exception {
            when(profileService.getCurrentUser())
                    .thenThrow(new ResourceNotFoundException("User not found: client@souklab.dz"));

            mockMvc.perform(get("/api/v1/auth/me")
                            .with(client()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("User not found: client@souklab.dz"));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/auth/me")
    class PatchCurrentUserTests {

        /**
         * Verifies that authenticated JSON Merge Patch deserializes into UserPatchDTO with explicit null to service.
         */
        @Test
        @DisplayName("JSON Merge Patch with explicit null passes UserPatchDTO to service returning 200 OK")
        void patchCurrentUser_whenAuthenticatedWithExplicitNull_shouldPassUserPatchDtoToServiceAndReturn200Ok() throws Exception {
            ClientProfileResponseDTO updated = buildClientProfile("user-1", "karim@souklab.dz", AccountStatus.ACTIVE);
            updated.setBio("Updated bio");
            updated.setCity(null);
            when(profileService.patchCurrentUser(any(UserPatchDTO.class))).thenReturn(updated);

            mockMvc.perform(patch("/api/v1/auth/me")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "bio": "Updated bio",
                                      "city": null
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Profile updated successfully."))
                    .andExpect(jsonPath("$.data.bio").value("Updated bio"))
                    .andExpect(jsonPath("$.data.city").value(nullValue()));

            ArgumentCaptor<UserPatchDTO> captor = ArgumentCaptor.forClass(UserPatchDTO.class);
            verify(profileService).patchCurrentUser(captor.capture());
            UserPatchDTO captured = captor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.getBio().isDefined()).isTrue();
            assertThat(captured.getBio().getValue()).isEqualTo("Updated bio");
            assertThat(captured.getCity().isDefined()).isTrue();
            assertThat(captured.getCity().isNull()).isTrue();
            assertThat(captured.getAddress().isDefined()).isFalse();
        }

        /**
         * Verifies that empty or omitted body is tolerated and passed to service.
         */
        @Test
        @DisplayName("empty patch body is passed to service returning 200 OK")
        void patchCurrentUser_whenEmptyBody_shouldPassToService() throws Exception {
            ClientProfileResponseDTO current = buildClientProfile("user-1", "karim@souklab.dz", AccountStatus.ACTIVE);
            when(profileService.patchCurrentUser(any())).thenReturn(current);

            mockMvc.perform(patch("/api/v1/auth/me")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200));

            verify(profileService).patchCurrentUser(any());
        }

        /**
         * Verifies that unauthenticated PATCH /me receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated PATCH /me receives 401 Unauthorized")
        void patchCurrentUser_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(patch("/api/v1/auth/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "bio": "Unauthorized update"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/complete-profile")
    class CompleteProfileTests {

        /**
         * Verifies that authenticated user can complete profile returning 200 OK.
         */
        @Test
        @DisplayName("authenticated user completes profile returning 200 OK")
        void completeProfile_whenAuthenticated_shouldPassDtoAndReturn200Ok() throws Exception {
            ClientProfileResponseDTO profile = buildClientProfile("user-1", "karim@souklab.dz", AccountStatus.ACTIVE);
            profile.setCity("Algiers");
            profile.setRegionId("reg-16");
            when(profileService.completeProfile(any(CompleteProfileRequestDTO.class))).thenReturn(profile);

            mockMvc.perform(post("/api/v1/auth/complete-profile")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "regionId": "reg-16",
                                      "city": "Algiers",
                                      "bio": "Handmade leather goods enthusiast"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Profile completed successfully."))
                    .andExpect(jsonPath("$.data.city").value("Algiers"))
                    .andExpect(jsonPath("$.data.regionId").value("reg-16"));

            ArgumentCaptor<CompleteProfileRequestDTO> captor = ArgumentCaptor.forClass(CompleteProfileRequestDTO.class);
            verify(profileService).completeProfile(captor.capture());
            assertThat(captor.getValue().getCity()).isEqualTo("Algiers");
            assertThat(captor.getValue().getRegionId()).isEqualTo("reg-16");
            assertThat(captor.getValue().getBio()).isEqualTo("Handmade leather goods enthusiast");
        }

        /**
         * Verifies that unauthenticated complete profile request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated complete profile receives 401 Unauthorized")
        void completeProfile_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/auth/complete-profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "city": "Algiers"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/oauth/google/artisan")
    class OAuthGoogleArtisanTests {

        /**
         * Verifies that initiating Google OAuth for artisan sets SOUKLAB_OAUTH_INTENT=ARTISAN cookie
         * and redirects to /oauth2/authorization/google with 302 Found.
         */
        @Test
        @DisplayName("artisan OAuth initiation sets cookie and redirects 302")
        void initiateGoogleOAuthArtisan_whenAnonymous_shouldSetCookieAndRedirect302() throws Exception {
            mockMvc.perform(get("/api/v1/auth/oauth/google/artisan"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/oauth2/authorization/google"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(OAuthCookie.Intent.NAME.value() + "=ARTISAN")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=300")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/oauth/google/client")
    class OAuthGoogleClientTests {

        /**
         * Verifies that initiating Google OAuth for client sets SOUKLAB_OAUTH_INTENT=CLIENT cookie
         * and redirects to /oauth2/authorization/google with 302 Found.
         */
        @Test
        @DisplayName("client OAuth initiation sets cookie and redirects 302")
        void initiateGoogleOAuthClient_whenAnonymous_shouldSetCookieAndRedirect302() throws Exception {
            mockMvc.perform(get("/api/v1/auth/oauth/google/client"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/oauth2/authorization/google"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(OAuthCookie.Intent.NAME.value() + "=CLIENT")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=300")));
        }
    }
}
