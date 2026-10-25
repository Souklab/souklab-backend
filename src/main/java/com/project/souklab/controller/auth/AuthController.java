package com.project.souklab.controller.auth;

import com.project.souklab.config.OAuthProperties;
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
import com.project.souklab.dto.auth.OAuthExchangeRequest;
import com.project.souklab.dto.auth.DeleteAccountRequest;
import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.dto.profile.UserPatchDTO;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.security.OAuthCookie;
import com.project.souklab.service.auth.AuthService;
import com.project.souklab.service.profile.ProfileService;
import com.project.souklab.security.AuthorizationCodeStore;
import com.project.souklab.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.project.souklab.model.AccountRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.util.Optional;

/**
 * HTTP adapter for authentication and profile endpoints.
 * Authentication operations (login, register, tokens, passwords, OAuth2) delegate to {@link AuthService}.
 * Profile lifecycle operations (get/patch profile, complete profile) delegate to {@link ProfileService}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication & Profile", description = "User authentication, registration, token lifecycle, password management, and current user profile (/me)")
@RequiredArgsConstructor
public class AuthController {

    private static final AccountRole ARTISAN_SIGNUP_INTENT = AccountRole.ARTISAN;
    private static final AccountRole CLIENT_SIGNUP_INTENT = AccountRole.CLIENT;
    private static final String OAUTH2_GOOGLE_AUTHORIZATION_REDIRECT_URI = "/oauth2/authorization/google";

    private final AuthService authService;
    private final ProfileService profileService;
    private final OAuthProperties oAuthProperties;
    private final Optional<AuthorizationCodeStore> authorizationCodeStore;

    /**
     * Registers a new user and returns the account-type-specific profile response.
     */
    @PostMapping("/register")
    @Operation(summary = "Register user", description = "Registers a new user (CLIENT or ARTISAN). Artisans start in PENDING status awaiting administrator verification; clients are immediately ACTIVE. Returns the account-type-specific ProfileResponse.")
    public ResponseEntity<ApiResponse<ProfileResponse>> register(@Valid @RequestBody UserRegistrationDTO registrationDTO) {
        ProfileResponse response = authService.registerUser(registrationDTO);
        boolean isPending = response.getAccountStatus() == AccountStatus.PENDING;
        String message = isPending
                ? "Registration successful. Your artisan account has been created and is pending administrator verification."
                : "Registration successful. Welcome to Souklab!";

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, message));
    }

    /**
     * Authenticates a user and returns JWT access and refresh tokens.
     */
    @PostMapping("/login")
    @Operation(summary = "Login with credentials", description = "Authenticates user with email and password. Returns JWT access token (1 hour) and refresh token (24 hours) along with user summary.")
    public ResponseEntity<ApiResponse<JwtResponseDTO>> login(@Valid @RequestBody LoginDTO loginDTO, HttpServletRequest request) {
        JwtResponseDTO response = authService.login(loginDTO, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Login successful."));
    }

    /**
     * Rotates the provided refresh token and returns a new JWT pair.
     */
    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token", description = "Rotates the provided refresh token and returns a new JWT access and refresh token pair.")
    public ResponseEntity<ApiResponse<JwtResponseDTO>> refreshToken(@Valid @RequestBody TokenRefreshRequestDTO request) {
        JwtResponseDTO response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Token refreshed successfully."));
    }

    @PostMapping("/oauth/exchange")
    @Operation(summary = "Exchange OAuth authorization code", description = "Consumes a one-time OAuth authorization code. Codes expire after 60 seconds.")
    public ResponseEntity<ApiResponse<JwtResponseDTO>> exchangeOAuthCode(@Valid @RequestBody OAuthExchangeRequest request) {
        JwtResponseDTO response = authorizationCodeStore.orElseThrow(() ->
                new IllegalStateException("OAuth authorization-code exchange is unavailable")).consume(request.code());
        if (response == null) {
            throw new UnauthorizedException("Invalid or expired OAuth authorization code.");
        }
        return ResponseEntity.ok(ApiResponse.success(response, "OAuth authorization code exchanged successfully."));
    }

    /**
     * Revokes tokens for the current user and logs them out.
     */
    @PostMapping("/logout")
    @Operation(summary = "Logout user", description = "Revokes refresh tokens for the current user and terminates the active session.")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestBody(required = false) TokenRefreshRequestDTO tokenRequest) {
        authService.logout(tokenRequest);
        return ResponseEntity.ok(ApiResponse.success(null, "Logout successful."));
    }

    @DeleteMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete current account", description = "Anonymises and disables the authenticated account after password or OAuth confirmation.")
    public ResponseEntity<Void> deleteCurrentAccount(@RequestBody(required = false) DeleteAccountRequest request) {
        authService.deleteCurrentAccount(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Verifies the submitted email verification code.
     */
    @PostMapping("/verify-email")
    @Operation(summary = "Verify email", description = "Validates the 6-digit numeric verification OTP code sent to user upon registration.")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@Valid @RequestBody VerifyEmailRequestDTO request) {
        authService.verifyEmail(request);
        return ResponseEntity.ok(ApiResponse.success(null, "Email verified successfully."));
    }

    /**
     * Resends the email verification code to the given address if the account exists and is unverified.
     */
    @PostMapping("/resend-verification")
    @Operation(summary = "Resend verification email", description = "Issues a fresh 6-digit verification code and invalidates older active codes for an unverified account.")
    public ResponseEntity<ApiResponse<Void>> resendVerification(@Valid @RequestBody ResendVerificationRequestDTO request) {
        authService.resendVerification(request);
        return ResponseEntity.ok(ApiResponse.success(null, "If an unverified account exists for this email, a verification code has been sent."));
    }

    /**
     * Initiates the password reset flow for the given email address.
     */
    @PostMapping("/forgot-password")
    @Operation(summary = "Forgot password", description = "Initiates password reset flow by sending a 6-digit reset code to the provided email if an account exists.")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDTO request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.success(null, "If an account exists for this email, instructions have been sent."));
    }

    /**
     * Resets the password using the 6-digit code sent via email.
     */
    @PostMapping("/reset-password")
    @Operation(summary = "Reset password", description = "Resets user password using the 6-digit code sent via email, updating credentials and invalidating active refresh tokens.")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success(null, "Password reset successfully. You can now log in with your new password."));
    }

    /**
     * Changes the authenticated user's password.
     */
    @PostMapping("/change-password")
    @Operation(summary = "Change password", description = "Allows an authenticated user to change their password by supplying their current password and a new confirmed password.")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequestDTO request) {
        authService.changePassword(request);
        return ResponseEntity.ok(ApiResponse.success(null, "Password changed successfully."));
    }

    /**
     * Returns the profile of the currently authenticated user.
     */
    @GetMapping("/me")
    @PreAuthorize("@accessControl.canReadProfile(authentication)")
    @Operation(summary = "Get current user profile (/me)", description = "Retrieves the full profile of the currently authenticated user based on their JWT token. Returns polymorphic ProfileResponse: ArtisanResponseDTO (with bio, address, phone, rating, crafts, wilaya) for artisans, or ClientProfileResponseDTO (with company, clientType) for clients.")
    public ResponseEntity<ApiResponse<ProfileResponse>> getCurrentUser() {
        return ResponseEntity.ok(ApiResponse.success(profileService.getCurrentUser()));
    }

    /**
     * Partially updates the authenticated user's profile using a strongly-typed patch DTO.
     */
    @PatchMapping("/me")
    @PreAuthorize("@accessControl.canWriteProfile(authentication)")
    @Operation(summary = "Update current user profile (/me)", description = "Applies JSON Merge Patch updates to the authenticated user's profile. Supports bio, city, address, website, regionId, subCategoryId, materialIds, techniqueIds, epoqueIds for artisans; companyName, clientType for clients. Fields omitted in the JSON payload are preserved unchanged. Returns the updated ProfileResponse.")
    public ResponseEntity<ApiResponse<ProfileResponse>> patchCurrentUser(@Valid @RequestBody(required = false) UserPatchDTO patchDTO) {
        ProfileResponse response = profileService.patchCurrentUser(patchDTO);
        return ResponseEntity.ok(ApiResponse.success(response, "Profile updated successfully."));
    }

    /**
     * Completes the artisan or client profile for the authenticated user.
     */
    @PostMapping("/complete-profile")
    @Operation(summary = "Complete user profile", description = "Onboarding wizard endpoint allowing newly registered users to complete their profile setup with required craft or business details.")
    @PreAuthorize("@accessControl.canWriteProfile(authentication)")
    public ResponseEntity<ApiResponse<ProfileResponse>> completeProfile(@Valid @RequestBody CompleteProfileRequestDTO request) {
        ProfileResponse response = profileService.completeProfile(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Profile completed successfully."));
    }

    /**
     * Initiates the Google OAuth2 flow with artisan registration intent.
     */
    @GetMapping("/oauth/google/artisan")
    @Operation(summary = "Google OAuth2 (Artisan)", description = "Initiates Google OAuth2 login/signup flow with ARTISAN intent cookie and redirects to Google.")
    public void initiateGoogleOAuthArtisan(HttpServletRequest request, HttpServletResponse response) throws IOException {
        setIntentCookie(request, response, ARTISAN_SIGNUP_INTENT);
        response.sendRedirect(OAUTH2_GOOGLE_AUTHORIZATION_REDIRECT_URI);
    }

    /**
     * Initiates the Google OAuth2 flow with client registration intent.
     */
    @GetMapping("/oauth/google/client")
    @Operation(summary = "Google OAuth2 (Client)", description = "Initiates Google OAuth2 login/signup flow with CLIENT intent cookie and redirects to Google.")
    public void initiateGoogleOAuthClient(HttpServletRequest request, HttpServletResponse response) throws IOException {
        setIntentCookie(request, response, CLIENT_SIGNUP_INTENT);
        response.sendRedirect(OAUTH2_GOOGLE_AUTHORIZATION_REDIRECT_URI);
    }

    /**
     * Sets the OAuth2 registration intent cookie on the outgoing response.
     *
     * @param request    the incoming HTTP servlet request
     * @param response   the HTTP response to attach the cookie to
     * @param intentRole the account-type signup intent (for example, {@code "ARTISAN"})
     */
    private void setIntentCookie(HttpServletRequest request, HttpServletResponse response, AccountRole intentRole) {
        ResponseCookie cookie = ResponseCookie.from(OAuthCookie.Intent.NAME.value(), intentRole.value())
                .path("/")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .maxAge(oAuthProperties.getIntentCookieMaxAgeSeconds())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
