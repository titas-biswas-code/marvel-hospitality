package com.marvel.hospitality.reservation.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /reference-data} (rest-api.md): public (see {@code marvel.security.additional-public-paths} in
 * application.yml), so UIs can seed their enum dropdowns without a token.
 */
@RestController
@Tag(name = "Reference data", description = "Enum values used by this service; no authentication required.")
class ReferenceDataController {

    @GetMapping("/reference-data")
    @Operation(summary = "List every enum value this service uses",
            description = "Built from Enum.values() (ADR-0004) so the Java enums stay the single source of truth.")
    @ApiResponse(responseCode = "200", description = "OK",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReferenceDataResponse.class),
                    examples = @ExampleObject(value = ReservationApiExamples.REFERENCE_DATA_RESPONSE)))
    ReferenceDataResponse referenceData() {
        return ReferenceDataResponse.current();
    }
}
