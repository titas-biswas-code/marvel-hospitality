package com.marvel.hospitality.notification.api;

import com.marvel.hospitality.notification.application.ListNotificationsUseCase;
import com.marvel.hospitality.platform.security.PropertyAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /notifications?reservationId=} (rest-api.md): the rendered notifications of one reservation, for demos
 * and verification. The path names no property, so instead of a {@code 403 FORBIDDEN_PROPERTY} check the result is
 * filtered to the properties in the caller's token (ADR-0002): notifications of other properties are simply absent.
 */
@RestController
@RequestMapping("/notifications")
@Tag(name = "Notifications", description = "Notifications rendered from reservation-status-changed events.")
class NotificationController {

    private final ListNotificationsUseCase listUseCase;

    NotificationController(ListNotificationsUseCase listUseCase) {
        this.listUseCase = listUseCase;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('reservation:read')")
    @Operation(summary = "List a reservation's notifications, oldest first",
            description = "Only notifications of properties in the token's `properties` claim are returned; "
                    + "an unknown reservation, or one of another property, yields an empty list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = NotificationResponse.class)),
                            examples = @ExampleObject(value = NotificationApiExamples.NOTIFICATIONS_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (reservationId missing or too long)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = NotificationApiExamples.VALIDATION_FAILED_EXAMPLE))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = NotificationApiExamples.UNAUTHENTICATED_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN (token lacks reservation:read)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = NotificationApiExamples.FORBIDDEN_EXAMPLE)))})
    List<NotificationResponse> list(
            @Parameter(example = "P4145478") @RequestParam @NotBlank @Size(max = 8) String reservationId,
            @AuthenticationPrincipal Jwt jwt) {
        return listUseCase.list(reservationId, entitledTo(jwt)).stream().map(NotificationResponse::from).toList();
    }

    private static Predicate<String> entitledTo(Jwt jwt) {
        List<String> properties = PropertyAccess.entitledProperties(jwt);
        return properties.contains(PropertyAccess.ALL_PROPERTIES) ? propertyId -> true : properties::contains;
    }
}
