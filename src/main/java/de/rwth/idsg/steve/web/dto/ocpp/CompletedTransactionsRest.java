package de.rwth.idsg.steve.web.dto.ocpp;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;

@Getter
@Setter
public class CompletedTransactionsRest {

    @NotNull(message = "From Date/Time")
    private String from;

}
