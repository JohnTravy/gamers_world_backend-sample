package com.travy.SpringRestAPI.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travy.SpringRestAPI.Model.Payment;
import com.travy.SpringRestAPI.Model.User;
import com.travy.SpringRestAPI.Repository.PaymentRepository;
import com.travy.SpringRestAPI.Repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaystackClient paystackClient;

    @Value("${paystack.secret.key}")
    private String paystackSecretKey;

    private static final BigDecimal PLAN_AMOUNT = BigDecimal.valueOf(5000);
    private static final int PLAN_DAYS = 30;

    public Payment createPayment(String email, double amount, String currency, int days) {
        if (BigDecimal.valueOf(amount).compareTo(PLAN_AMOUNT) != 0 || days != PLAN_DAYS) {
            throw new RuntimeException("Invalid payment plan.");
        }

        Payment pending = getPendingPayment(email, amount, days);
        if (pending != null) return pending;

        if (hasActiveSubscription(email)) {
            throw new RuntimeException("You already have an active subscription.");
        }

        Payment payment = new Payment();
        payment.setUserEmail(email);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setSubscriptionDays(days);
        payment.setStatus("PENDING");

        return paymentRepository.save(payment);
    }

    public Payment getPendingPayment(String email, double amount, int days) {
        return paymentRepository
                .findByUserEmailAndStatusAndAmountAndSubscriptionDays(email, "PENDING", BigDecimal.valueOf(amount), days)
                .orElse(null);
    }

    public boolean hasActiveSubscription(String email) {
        return userRepository.findByEmail(email)
                .map(User::isSubscriptionActive)
                .orElse(false);
    }

    public List<Payment> getAllPayments() {
        return paymentRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Payment> getPaymentsByStatus(String status) {
        return paymentRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public void refundPayment(Long paymentId) {
        Payment payment = paymentRepository.findByIdWithLock(paymentId);
        if (payment == null) throw new RuntimeException("Payment not found: " + paymentId);
        if (!"SUCCESS".equals(payment.getStatus())) throw new RuntimeException("Only SUCCESS payments can be refunded");
        if ("REFUNDED".equals(payment.getStatus())) throw new RuntimeException("Payment has already been refunded");

        payment.setStatus("REFUNDED");
        payment.setRefundedAt(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    @Transactional
    public void completePaymentFromWebhook(Long paymentId, String gatewayRef, Long paystackTxnId, LocalDateTime paidAt) {
        if (gatewayRef == null || gatewayRef.isBlank()) {
            throw new RuntimeException("Missing gateway reference");
        }

        if (paymentRepository.findByGatewayRef(gatewayRef).isPresent()) return;

        Payment payment = paymentRepository.findByIdWithLock(paymentId);
        if (payment == null) return;
        if ("SUCCESS".equals(payment.getStatus())) return;

        if (BigDecimal.valueOf(payment.getAmount()).compareTo(PLAN_AMOUNT) != 0 || payment.getSubscriptionDays() != PLAN_DAYS) {
            throw new RuntimeException("Payment plan mismatch for payment ID: " + paymentId);
        }

        if (paymentRepository.findByPaystackTransactionId(paystackTxnId).isPresent()) return;

        payment.setGatewayRef(gatewayRef);
        payment.setPaystackTransactionId(paystackTxnId);
        payment.setPaidAt(paidAt);
        payment.setStatus("SUCCESS");

        User user = userRepository.findByEmail(payment.getUserEmail())
                .orElseThrow(() -> new RuntimeException("User not found for payment ID: " + paymentId));

        LocalDate now = LocalDate.now();
        LocalDate currentExpiry = user.getSubscriptionExpiryDate();
        LocalDate newExpiry = (currentExpiry != null && currentExpiry.isAfter(now))
                ? currentExpiry.plusDays(PLAN_DAYS)
                : now.plusDays(PLAN_DAYS);

        user.setHasPaid(true);
        user.setSubscriptionExpiryDate(newExpiry);

        userRepository.save(user);
        paymentRepository.save(payment);

        System.out.println("✅ Payment SUCCESS | paymentId=" + paymentId + " | gatewayRef=" + gatewayRef);
    }

    public boolean verifyPaystackSignature(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            SecretKeySpec keySpec = new SecretKeySpec(paystackSecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(hash);
            return constantTimeEquals(computed, signature);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public Long extractPaymentId(String payload) throws Exception {
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode metadata = root.path("data").path("metadata");
        if (metadata.isMissingNode() || metadata.path("payment_id").isMissingNode()) {
            throw new RuntimeException("Missing payment_id in metadata");
        }
        return metadata.path("payment_id").asLong();
    }

    public String extractReference(String payload) throws Exception {
        return new ObjectMapper().readTree(payload).path("data").path("reference").asText(null);
    }

    public String extractEventType(String payload) throws Exception {
        return new ObjectMapper().readTree(payload).path("event").asText();
    }

    public Long extractPaystackTransactionId(String payload) throws Exception {
        JsonNode root = new ObjectMapper().readTree(payload);
        return root.path("data").path("id").asLong();
    }

    public LocalDateTime extractPaidAt(String payload) throws Exception {
        JsonNode root = new ObjectMapper().readTree(payload);
        String paidAt = root.path("data").path("paid_at").asText(null);
        return paidAt != null ? OffsetDateTime.parse(paidAt).toLocalDateTime() : null;
    }

    @Transactional
    public void verifyPaymentManually(Long paymentId, String reference) {
        Payment payment = paymentRepository.findByIdWithLock(paymentId);
        if (payment == null) throw new RuntimeException("Payment not found: " + paymentId);

        if ("SUCCESS".equals(payment.getStatus())) {
            System.out.println("Payment already SUCCESS, skipping manual verification: " + paymentId);
            return;
        }

        JsonNode data = paystackClient.verifyTransaction(reference);

        String status = data.path("status").asText();
        Long paystackTxnId = data.path("id").asLong();
        String gatewayRef = data.path("reference").asText();
        String paidAtStr = data.path("paid_at").asText(null);
        LocalDateTime paidAt = (paidAtStr != null) ? OffsetDateTime.parse(paidAtStr).toLocalDateTime() : null;

        if (!"success".equalsIgnoreCase(status)) {
            System.out.println("Payment not successful on Paystack: " + paymentId + " | status=" + status);
            return;
        }

        if (paymentRepository.findByGatewayRef(gatewayRef).isPresent()) {
            System.out.println("Payment already processed for gatewayRef: " + gatewayRef);
            return;
        }

        payment.setGatewayRef(gatewayRef);
        payment.setPaystackTransactionId(paystackTxnId);
        payment.setPaidAt(paidAt);
        payment.setStatus("SUCCESS");

        User user = userRepository.findByEmail(payment.getUserEmail())
                .orElseThrow(() -> new RuntimeException("User not found for payment ID: " + paymentId));

        LocalDate now = LocalDate.now();
        LocalDate currentExpiry = user.getSubscriptionExpiryDate();
        LocalDate newExpiry = (currentExpiry != null && currentExpiry.isAfter(now))
                ? currentExpiry.plusDays(PLAN_DAYS)
                : now.plusDays(PLAN_DAYS);

        user.setHasPaid(true);
        user.setSubscriptionExpiryDate(newExpiry);

        userRepository.save(user);
        paymentRepository.save(payment);

        System.out.println("✅ Manual verification SUCCESS | paymentId=" + paymentId + " | reference=" + gatewayRef);
    }
}