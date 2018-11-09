package de.rwth.idsg.steve.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.repository.*;
import de.rwth.idsg.steve.repository.dto.*;
import de.rwth.idsg.steve.service.ChargePointService15_Client;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.web.dto.ConnectorStatusForm;
import de.rwth.idsg.steve.web.dto.OcppTagForm;
import de.rwth.idsg.steve.web.dto.ReservationQueryForm;
import de.rwth.idsg.steve.web.dto.TransactionQueryForm;
import de.rwth.idsg.steve.web.dto.ocpp.CompletedTransactionsRest;
import de.rwth.idsg.steve.web.dto.ocpp.ReserveNowParams;
import de.rwth.idsg.steve.web.dto.ocpp.ReserveNowParamsRest;
import jooq.steve.db.tables.records.OcppTagRecord;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.LocalDateTime;
import org.joda.time.format.DateTimeFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Controller
@ResponseBody
@RequestMapping(
        value = "/manager/rest",
        method = RequestMethod.GET,
        produces = MediaType.APPLICATION_JSON_VALUE)
public class RestController {

    @Autowired
    @Qualifier("ChargePointService15_Client")
    private ChargePointService15_Client client15;
    @Autowired
    @Qualifier("ChargePointService16_Client")
    private ChargePointService16_Client client16;
    @Autowired
    private ChargePointRepository chargePointRepository;
    @Autowired
    private OcppTagRepository ocppTagRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private ReservationRepository reservationRepository;


    private ObjectMapper objectMapper;

    @PostConstruct
    private void init() {
        objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------

    private static final String GET_HEARTBEAT = "/heartbeat";
    private static final String GET_CHARGEBOXES = "/chargeboxes";
    private static final String GET_CONNECTORS_BY_CHARGEBOXID = "/chargeboxes/{chargeBoxId}/connectors";
    private static final String POST_RESERVE_CONNECTOR = "/chargeboxes/{chargeBoxId}/connectors/{connectorId}/reserve";
    private static final String GET_TRANSACTIONS_BY_OCPPID = "/transactions/{ocppId}";
    private static final String POST_COMPLETED_TRANSACTIONS_SINCE = "/transactions";
    private static final String POST_OCPP_TAG_ID = "/tag";

    // -------------------------------------------------------------------------
    // HTTP methods
    // -------------------------------------------------------------------------

    @RequestMapping(value = GET_HEARTBEAT, method = RequestMethod.GET)
    public void getHeartbeat(HttpServletResponse response) throws IOException {
        writeOutput(response, "{\"message\": \"success\"}");
    }

    @RequestMapping(value = GET_CHARGEBOXES, method = RequestMethod.GET)
    public void getChargeBoxes(HttpServletResponse response) throws IOException {
        List<Map<String, Object>> detailsList = new ArrayList<>();
        for (Integer pkId : chargePointRepository.getChargeBoxIdPkPair(chargePointRepository.getChargeBoxIds()).values()) {
            ChargePoint.Details details = chargePointRepository.getDetails(pkId);
            HashMap<String, Object> result = new HashMap<>();
            result.put("id", details.getChargeBox().getChargeBoxId());
            result.put("description", details.getChargeBox().getDescription());
            result.put("lat", details.getChargeBox().getLocationLatitude());
            result.put("lng", details.getChargeBox().getLocationLongitude());
            if (details.getAddress() != null) {
                result.put("street", details.getAddress().getStreet());
                result.put("city", details.getAddress().getCity());
                result.put("houseNumber", details.getAddress().getHouseNumber());
                result.put("postcode", details.getAddress().getZipCode());
            }
            detailsList.add(result);
        }

        String s = serializeArray(detailsList);
        writeOutput(response, s);
    }

    @RequestMapping(value = GET_CONNECTORS_BY_CHARGEBOXID, method = RequestMethod.GET)
    public void getConnectors(@PathVariable("chargeBoxId") String chargeBoxId,
                              HttpServletResponse response) throws IOException {
        ConnectorStatusForm model = new ConnectorStatusForm();
        model.setChargeBoxId(chargeBoxId);
        model.setStatus(null);
        List<ConnectorStatus> connectorStatusList = chargePointRepository.getChargePointConnectorStatus(model);

        // get any active reservations
        ReservationQueryForm queryForm = new ReservationQueryForm();
        queryForm.setChargeBoxId(chargeBoxId);
        queryForm.setPeriodType(ReservationQueryForm.QueryPeriodType.ACTIVE);
        Map<Integer, Reservation> reservationMap = reservationRepository.getReservations(queryForm)
                .stream()
                .collect(Collectors.toMap(Reservation::getConnectorId, x -> x));

        List<ConnectorStatus> result = new ArrayList<>();

        for (ConnectorStatus status : connectorStatusList) {
            if ("AVAILABLE".equals(status.getStatus().toUpperCase()) && reservationMap.get(status.getConnectorId()) != null) {
                result.add(ConnectorStatus.builder()
                        .chargeBoxPk(status.getChargeBoxPk())
                        .chargeBoxId(status.getChargeBoxId())
                        .connectorId(status.getConnectorId())
                        .timeStamp(status.getTimeStamp())
                        .statusTimestamp(status.getStatusTimestamp())
                        .status("RESERVED")
                        .errorCode(status.getErrorCode())
                        .build()
                );
            } else {
                result.add(status);
            }
        }

        String s = serializeArray(result);
        writeOutput(response, s);
    }

    @RequestMapping(value = POST_RESERVE_CONNECTOR, method = RequestMethod.POST)
    public void postReserve(@PathVariable("chargeBoxId") String chargeBoxId,
                            @PathVariable("connectorId") String connectorId,
                            @RequestBody String jsonStr,
                            HttpServletResponse response) throws IOException {
        ReserveNowParamsRest params = objectMapper.readValue(jsonStr, ReserveNowParamsRest.class);
        Map<String, Integer> map = chargePointRepository.getChargeBoxIdPkPair(new ArrayList<>() {{
            add(chargeBoxId);
        }});
        ChargePoint.Details details = chargePointRepository.getDetails(map.get(chargeBoxId));

        ReserveNowParams reserveNowParams = new ReserveNowParams();
        ArrayList<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        chargePointSelectList.add(new ChargePointSelect(OcppTransport.JSON, chargeBoxId, "-"));
        reserveNowParams.setChargePointSelectList(chargePointSelectList);
        reserveNowParams.setConnectorId(Integer.parseInt(connectorId));
        reserveNowParams.setExpiry(LocalDateTime.parse(params.getExpiry(), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")));
        reserveNowParams.setIdTag(params.getIdTag());

        if (details.getChargeBox().getOcppProtocol().contains("1.6")) {
            client16.reserveNow(reserveNowParams);
        } else {
            client15.reserveNow(reserveNowParams);
        }

        ReservationQueryForm queryForm = new ReservationQueryForm();
        queryForm.setChargeBoxId(chargeBoxId);
        queryForm.setOcppIdTag(params.getIdTag());
        queryForm.setStatus(ReservationStatus.WAITING);
        queryForm.setPeriodType(ReservationQueryForm.QueryPeriodType.ACTIVE);
        List<Reservation> reservationList = reservationRepository.getReservations(queryForm);

        Optional<Reservation> optionalReservation = reservationList.stream()
                .filter(x -> x.getConnectorId() == Integer.parseInt(connectorId))
                .findFirst();

        if (optionalReservation.isEmpty()) {
            throw new RuntimeException("Error");
        }

        writeOutput(response, objectMapper.writeValueAsString(optionalReservation.get()));
    }

    @RequestMapping(value = GET_TRANSACTIONS_BY_OCPPID, method = RequestMethod.GET)
    public void getTransactions(@PathVariable("ocppId") String ocppId,
                                HttpServletResponse response) throws IOException {
        TransactionQueryForm model = new TransactionQueryForm();
        model.setOcppIdTag(ocppId);
        model.setType(TransactionQueryForm.QueryType.ALL);
        List<Transaction> transactions = transactionRepository.getTransactions(model);
        String s = serializeArray(transactions);
        writeOutput(response, s);
    }

    @RequestMapping(value = POST_COMPLETED_TRANSACTIONS_SINCE, method = RequestMethod.POST)
    public void getCompletedTransactions(@RequestBody String jsonStr, HttpServletResponse response) throws IOException {
        CompletedTransactionsRest params = objectMapper.readValue(jsonStr, CompletedTransactionsRest.class);

        TransactionQueryForm model = new TransactionQueryForm();
        model.setType(TransactionQueryForm.QueryType.ALL);
        model.setPeriodType(TransactionQueryForm.QueryPeriodType.FROM_TO);
        model.setFrom(LocalDateTime.parse(params.getFrom(), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")));
        model.setTo(LocalDateTime.now());
        List<Transaction> transactions = transactionRepository.getTransactions(model)
                .stream()
                .filter(x -> !x.getStopTimestamp().isEmpty())
                .collect(Collectors.toList());
        String s = serializeArray(transactions);
        writeOutput(response, s);

    }

//    @RequestMapping(value = GET_TRANSACTION_BY_ID, method = RequestMethod.GET)
//    public void getTransaction(@PathVariable("transactionId") String transactionId,
//                               HttpServletResponse response) throws IOException {
//        TransactionQueryForm model = new TransactionQueryForm();
//        model.setTransactionPk(Integer.parseInt(transactionId));
//        model.setType(TransactionQueryForm.QueryType.ALL);
//        List<Transaction> transactions = transactionRepository.getTransactions(model);
//        writeOutput(response, objectMapper.writeValueAsString(transactions.size() == 0 ? null : transactions.get(0)));
//    }

    @RequestMapping(value = POST_OCPP_TAG_ID, method = RequestMethod.POST)
    public void postTag(@Valid OcppTagForm form, HttpServletResponse response) throws IOException {
        OcppTagRecord record = null;
        int result = 0;

        // Attempt to retrieve existing ocpp tag
        try {
            record = ocppTagRepository.getRecord(form.getIdTag());
        } catch (Exception ex) {
        }

        // Use tag if exists otherwise create a new one
        result = record != null ? record.getOcppTagPk() : ocppTagRepository.addOcppTag(form);

        if (result == 0) {
            throw new RuntimeException("Unable to get SteVe Tag for user");
        }

        writeOutput(response, objectMapper.writeValueAsString("{\"ocppTagPk\": \"" + result + "\"}"));
    }

    private String serializeArray(List<?> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            // As fallback return empty array, do not let the frontend hang
            log.error("Error occurred during serialization of response. Returning empty array instead!", e);
            return "[]";
        }
    }

    /**
     * We want to handle this JSON conversion locally, and do not want to register an application-wide
     * HttpMessageConverter just for this little class. Otherwise, it might have unwanted side effects due to
     * different serialization/deserialization needs of different APIs.
     * <p>
     * That's why we are directly accessing the low-level HttpServletResponse and manually writing to output.
     */
    private void writeOutput(HttpServletResponse response, String str) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(str);
    }

}
