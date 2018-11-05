package de.rwth.idsg.steve.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.repository.dto.ConnectorStatus;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.service.ChargePointService15_Client;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.web.dto.ConnectorStatusForm;
import de.rwth.idsg.steve.web.dto.TransactionQueryForm;
import de.rwth.idsg.steve.web.dto.ocpp.ReserveNowParams;
import de.rwth.idsg.steve.web.dto.ocpp.ReserveNowParamsRest;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@ResponseBody
@RequestMapping(
        value = "/manager/rest",
        method = RequestMethod.GET,
        produces = MediaType.APPLICATION_JSON_VALUE)
public class RestController {

    @Autowired
    private ChargePointRepository chargePointRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    @Qualifier("ChargePointService16_Client")
    private ChargePointService16_Client client16;
    @Autowired
    @Qualifier("ChargePointService15_Client")
    private ChargePointService15_Client client15;

    private ObjectMapper objectMapper;

    @PostConstruct
    private void init() {
        objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------

    private static final String CHARGEBOXES = "/chargeboxes";
    private static final String CONNECTORS = "/chargeboxes/{chargeBoxId}/connectors";
    private static final String RESERVE = "/chargeboxes/{chargeBoxId}/connectors/{connectorId}/reserve";
    private static final String TRANSACTIONS = "/transactions/{ocppId}";
    private static final String TRANSACTION = "/transaction/{transactionId}";

    // -------------------------------------------------------------------------
    // HTTP methods
    // -------------------------------------------------------------------------


    @RequestMapping(value = CHARGEBOXES, method = RequestMethod.GET)
    public void getChargeBoxes(HttpServletResponse response) throws IOException {
        List<Map<String, Object>> detailsList = new ArrayList<>();
        for (Integer pkId : chargePointRepository.getChargeBoxIdPkPair(chargePointRepository.getChargeBoxIds()).values()) {
            ChargePoint.Details details = chargePointRepository.getDetails(pkId);
            HashMap<String, Object> result = new HashMap<>();
            result.put("id", details.getChargeBox().getChargeBoxId());
            result.put("description", details.getChargeBox().getDescription());
            result.put("lat", details.getChargeBox().getLocationLatitude());
            result.put("long", details.getChargeBox().getLocationLongitude());
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

    @RequestMapping(value = CONNECTORS, method = RequestMethod.GET)
    public void getConnectors(@PathVariable("chargeBoxId") String chargeBoxId,
                              HttpServletResponse response) throws IOException {
        ConnectorStatusForm model = new ConnectorStatusForm();
        model.setChargeBoxId(chargeBoxId);
        model.setStatus(null);
        List<ConnectorStatus> latestList = chargePointRepository.getChargePointConnectorStatus(model);
        String s = serializeArray(latestList);
        writeOutput(response, s);
    }

    @RequestMapping(value = RESERVE, method = RequestMethod.POST)
    public void postReserve(@PathVariable("chargeBoxId") String chargeBoxId,
                            @PathVariable("connectorId") String connectorId,
                            @RequestBody String jsonStr,
                            HttpServletResponse response) throws IOException {
        ReserveNowParamsRest params = objectMapper.readValue(jsonStr, ReserveNowParamsRest.class);
        Map<String, Object> result = new HashMap<>();
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
            int res = client16.reserveNow(reserveNowParams);
            result.put("result", res);
        } else {
            int res = client15.reserveNow(reserveNowParams);
            result.put("result", res);
        }
        writeOutput(response, objectMapper.writeValueAsString(result));
    }

    @RequestMapping(value = TRANSACTIONS, method = RequestMethod.GET)
    public void getTransactions(@PathVariable("ocppId") String ocppId,
                                HttpServletResponse response) throws IOException {
        TransactionQueryForm model = new TransactionQueryForm();
        model.setOcppIdTag(ocppId);
        model.setType(TransactionQueryForm.QueryType.ALL);
        List<Transaction> transactions = transactionRepository.getTransactions(model);
        String s = serializeArray(transactions);
        writeOutput(response, s);
    }

    @RequestMapping(value = TRANSACTION, method = RequestMethod.GET)
    public void getTransaction(@PathVariable("transactionId") String transactionId,
                               HttpServletResponse response) throws IOException {
        TransactionQueryForm model = new TransactionQueryForm();
        model.setTransactionPk(Integer.parseInt(transactionId));
        model.setType(TransactionQueryForm.QueryType.ALL);
        List<Transaction> transactions = transactionRepository.getTransactions(model);
        writeOutput(response, objectMapper.writeValueAsString(
                    transactions.size() == 0 ? null : transactions.get(0)
                ));
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
