package de.rwth.idsg.steve.web.dto.ocpp;

import de.rwth.idsg.steve.web.validation.IdTag;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Getter
@Setter
public class ReserveNowParamsRest {

    @NotNull(message = "Expiry Date/Time is required")
    private String expiry;

    @NotBlank(message = "User ID Tag is required.")
    @IdTag
    private String idTag;

}
