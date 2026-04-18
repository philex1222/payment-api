package com.example.paymentapi.controller;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.service.IdempotencyService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.service.command.CancellationHandler;
import com.example.paymentapi.service.command.PaymentLifecycleHandler;
import com.example.paymentapi.service.command.ReversalHandler;
import com.example.paymentapi.service.query.PaymentQueryService;
import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.paymentapi.controller.PaymentController.IDEMPOTENCY_KEY_HEADER;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private ReversalHandler reversalHandler;

    @MockitoBean
    private CancellationHandler cancellationHandler;

    @MockitoBean
    private PaymentLifecycleHandler lifecycleHandler;

    @MockitoBean
    private PaymentQueryService queryService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private TransactionService transactionService;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    public void setUp() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("password");

        String loginResponseJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        LoginResponse loginResponse = objectMapper.readValue(loginResponseJson, LoginResponse.class);
        token = "Bearer " + loginResponse.getToken();
    }

    private WorkflowStub stubWorkflowStub() {
        WorkflowStub mockStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(
                eq("PaymentCreationWorkflow"), any())).thenReturn(mockStub);
        return mockStub;
    }

    @Test
    public void testCreatePayment() throws Exception {
        stubWorkflowStub();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    public void testCreatePayment_ValidationError() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        // Missing required fields

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_firstRequest_processesAndStores() throws Exception {
        stubWorkflowStub();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        when(idempotencyService.get("key-abc")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").isNotEmpty());

        verify(idempotencyService).store(eq("key-abc"), any(PaymentWorkflowResponse.class));
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_duplicateRequest_returnsCachedResponse() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        PaymentWorkflowResponse cached = new PaymentWorkflowResponse("payment-cached-xyz", "PENDING", null);
        when(idempotencyService.get("key-abc")).thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").value("payment-cached-xyz"));

        verify(workflowClient, never()).newUntypedWorkflowStub(anyString(), any(io.temporal.client.WorkflowOptions.class));
    }

    @Test
    public void testGetPaymentById() throws Exception {
        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId("payment123");
        paymentResponse.setStatus("COMPLETED");
        paymentResponse.setCreatedAt(LocalDateTime.now());

        when(queryService.findById("payment123")).thenReturn(paymentResponse);

        mockMvc.perform(get("/api/v1/payments/payment123")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("payment123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    public void testGetPayments() throws Exception {
        when(queryService.findAll(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    public void testCreatePayment_selfTransfer_returnsBadRequest() throws Exception {
        PaymentRequest r = new PaymentRequest();
        r.setSourceAccount("SAME");
        r.setDestinationAccount("SAME");
        r.setAmount(BigDecimal.valueOf(100));
        r.setCurrency("USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(r)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testGetPayments_amountFromGreaterThanAmountTo_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/payments")
                        .param("amountFrom", "500")
                        .param("amountTo", "100")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testGetPaymentsBySourceAccount_returnsList() throws Exception {
        PaymentResponse pr = new PaymentResponse();
        pr.setId("p-1");
        when(queryService.findBySourceAccount("SRC")).thenReturn(List.of(pr));
        mockMvc.perform(get("/api/v1/payments/source-account")
                        .param("sourceAccount", "SRC")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("p-1"));
    }

    @Test
    public void testGetPaymentsByDestinationAccount_returnsList() throws Exception {
        PaymentResponse pr = new PaymentResponse();
        pr.setId("p-2");
        when(queryService.findByDestinationAccount("DST")).thenReturn(List.of(pr));
        mockMvc.perform(get("/api/v1/payments/destination-account")
                        .param("destinationAccount", "DST")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("p-2"));
    }

    @Test
    public void testUpdatePaymentStatus_returnsUpdated() throws Exception {
        PaymentResponse pr = new PaymentResponse();
        pr.setId("p-1");
        pr.setStatus("COMPLETED");
        when(lifecycleHandler.updateStatus(eq("p-1"), eq("COMPLETED"))).thenReturn(pr);

        mockMvc.perform(patch("/api/v1/payments/p-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    public void testCancelPayment_returnsOk() throws Exception {
        PaymentResponse pr = new PaymentResponse();
        pr.setId("p-1");
        pr.setStatus("CANCELLED");
        when(cancellationHandler.handle("p-1")).thenReturn(pr);

        mockMvc.perform(post("/api/v1/payments/p-1/cancel")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    public void testDeletePayment_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/payments/p-1")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());
        verify(lifecycleHandler).delete("p-1");
    }

    @Test
    public void testRetryPayment_returnsOk() throws Exception {
        PaymentResponse pr = new PaymentResponse();
        pr.setId("p-1");
        pr.setStatus("PROCESSING");
        when(lifecycleHandler.retry("p-1")).thenReturn(pr);

        mockMvc.perform(post("/api/v1/payments/p-1/retry")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    public void testInitiatePaymentReversal_returnsOk() throws Exception {
        PaymentResponse pr = new PaymentResponse();
        pr.setId("p-1");
        pr.setStatus("REVERSED");
        when(reversalHandler.handle(eq("p-1"), any())).thenReturn(pr);

        String body = "{\"reason\":\"customer-request-refund\"}";
        mockMvc.perform(post("/api/v1/payments/p-1/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
    }

    @Test
    public void testGetTransactionsByPaymentId_returnsList() throws Exception {
        PaymentResponse pr = new PaymentResponse();
        pr.setId("p-1");
        when(queryService.findById("p-1")).thenReturn(pr);
        when(transactionService.getTransactionsByPaymentId("p-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/payments/p-1/transactions")
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    public void testGetWorkflowStatus_returnsStatus() throws Exception {
        com.example.paymentapi.temporal.workflow.PaymentCreationWorkflow wf =
                mock(com.example.paymentapi.temporal.workflow.PaymentCreationWorkflow.class);
        when(wf.getCurrentStatus()).thenReturn("TRANSFERRING");
        when(wf.getPaymentId()).thenReturn("pay-xyz");
        when(workflowClient.newWorkflowStub(
                eq(com.example.paymentapi.temporal.workflow.PaymentCreationWorkflow.class),
                eq("wf-123"))).thenReturn(wf);

        mockMvc.perform(get("/api/v1/payments/workflows/wf-123/status")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-123"))
                .andExpect(jsonPath("$.status").value("TRANSFERRING"))
                .andExpect(jsonPath("$.paymentId").value("pay-xyz"));
    }

    @Test
    public void testGetWorkflowStatus_notFound_returns404() throws Exception {
        when(workflowClient.newWorkflowStub(
                eq(com.example.paymentapi.temporal.workflow.PaymentCreationWorkflow.class),
                eq("wf-missing")))
                .thenThrow(new io.temporal.client.WorkflowNotFoundException(
                        io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                .setWorkflowId("wf-missing").build(),
                        "PaymentCreationWorkflow",
                        new RuntimeException("workflow not found")));

        mockMvc.perform(get("/api/v1/payments/workflows/wf-missing/status")
                        .header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testCancelWorkflow_sendsSignalAndReturns202() throws Exception {
        WorkflowStub mockStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(eq("wf-cancel-1"))).thenReturn(mockStub);

        mockMvc.perform(post("/api/v1/payments/workflows/wf-cancel-1/cancel")
                        .header("Authorization", token)
                        .param("reason", "user-changed-mind"))
                .andExpect(status().isAccepted());

        verify(mockStub).signal("requestCancel", "user-changed-mind");
    }

    @Test
    public void testCancelWorkflow_defaultReasonAppliedWhenOmitted() throws Exception {
        WorkflowStub mockStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(eq("wf-cancel-2"))).thenReturn(mockStub);

        mockMvc.perform(post("/api/v1/payments/workflows/wf-cancel-2/cancel")
                        .header("Authorization", token))
                .andExpect(status().isAccepted());

        verify(mockStub).signal("requestCancel", "user-initiated");
    }

    @Test
    public void testCancelWorkflow_notFound_returns404() throws Exception {
        when(workflowClient.newUntypedWorkflowStub(eq("wf-missing-cancel")))
                .thenThrow(new io.temporal.client.WorkflowNotFoundException(
                        io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                .setWorkflowId("wf-missing-cancel").build(),
                        "PaymentCreationWorkflow",
                        new RuntimeException("workflow not found")));

        mockMvc.perform(post("/api/v1/payments/workflows/wf-missing-cancel/cancel")
                        .header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_setsWorkflowIdFromKey() throws Exception {
        when(idempotencyService.get("idem-42")).thenReturn(Optional.empty());
        WorkflowStub mockStub = stubWorkflowStub();
        PaymentRequest req = new PaymentRequest("1234567890", "0987654321",
                BigDecimal.valueOf(100), "USD", null);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "idem-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").value("payment-idem-42"));

        verify(mockStub).start(any(PaymentRequest.class), eq("admin"));
    }
}
