package com.dunghaiquyen.ecommerce.modules.payment.sepay.controller;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.config.AppSePayProperties;
import com.dunghaiquyen.ecommerce.modules.payment.sepay.dto.SePayWebhookRequest;
import com.dunghaiquyen.ecommerce.modules.payment.sepay.service.BankTransactionIngestionService;
import com.dunghaiquyen.ecommerce.modules.payment.sepay.service.SePayWebhookSignatureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class SePayWebhookController {

    private final AppSePayProperties properties;
    private final SePayWebhookSignatureService signatureService;
    private final BankTransactionIngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public SePayWebhookController(
            AppSePayProperties properties,
            SePayWebhookSignatureService signatureService,
            BankTransactionIngestionService ingestionService,
            ObjectMapper objectMapper,
            Validator validator) {
        this.properties = properties;
        this.signatureService = signatureService;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping(path = "/sepay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader("X-SePay-Signature") String signature,
            @RequestHeader("X-SePay-Timestamp") String timestamp,
            @RequestBody byte[] rawBody) {
        if (!properties.isWebhookEnabled()) {
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "SePay webhook is disabled");
        }
        signatureService.verify(signature, timestamp, rawBody);
        SePayWebhookRequest request = parseAndValidate(rawBody);
        var result = ingestionService.ingestWebhook(request);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("alreadyProcessed", result.alreadyProcessed());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private SePayWebhookRequest parseAndValidate(byte[] rawBody) {
        SePayWebhookRequest request;
        try {
            request = objectMapper.readValue(rawBody, SePayWebhookRequest.class);
        } catch (IOException exception) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Malformed SePay webhook payload");
        }
        Set<ConstraintViolation<SePayWebhookRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new BusinessRuleException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Invalid SePay webhook payload");
        }
        return request;
    }
}
