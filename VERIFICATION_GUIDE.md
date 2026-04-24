# Payment API Verification Guide

This comprehensive guide provides step-by-step instructions for verifying the Payment API application after startup. It covers functional, integration, and edge cases.

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Starting the Application](#starting-the-application)
3. [Health Check Verification](#health-check-verification)
4. [Authentication Tests](#authentication-tests)
5. [Payment Operations](#payment-operations)
6. [Payment Lifecycle Tests](#payment-lifecycle-tests)
7. [Validation & Error Handling](#validation--error-handling)
8. [Rate Limiting Tests](#rate-limiting-tests)
9. [Docker Verification](#docker-verification)
10. [Edge Cases](#edge-cases)

---

## Prerequisites

### Required Tools
- **curl** or **Postman** for API testing
- **Docker** and **Docker Compose** for containerized deployment
- **Java 21** for local development
- **Maven 3.9+** for building

### Environment Variables (for Docker)
```bash
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/payment_db
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=password
SPRING_REDIS_HOST=redis
JWT_SECRET=your-super-secret-jwt-key-that-should-be-at-least-256-bits-long
WEBHOOK_ENCRYPTION_KEY=base64-encoded-32-byte-key-from-openssl-rand-base64-32
```

---

## Starting the Application

### Option 1: Local Development
```bash
# Build the application
mvn clean package

# Run with H2 (for testing)
mvn spring-boot:run -Dspring-boot.run.profiles=test

# Run with MySQL (production)
mvn spring-boot:run
```

### Option 2: Docker Compose (Recommended)
```bash
# Start all services (MySQL, Redis, App)
docker-compose up -d

# View logs
docker-compose logs -f app

# Stop all services
docker-compose down
```

### Option 3: Docker Only (App)
```bash
# Build the image
docker build -t payment-api:latest .

# Run (requires external MySQL and Redis)
docker run -d -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://host:3306/payment_db \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=password \
  -e SPRING_REDIS_HOST=redis-host \
  payment-api:latest
```

---

## Health Check Verification

### 1. Basic Health Check
```bash
curl -X GET http://localhost:8080/actuator/health
```

**Expected Response:**
```json
{
  "status": "UP"
}
```

### 2. Info Endpoint
```bash
curl -X GET http://localhost:8080/actuator/info
```

### 3. Swagger UI
Open in browser: `http://localhost:8080/swagger-ui/`

---

## Authentication Tests

### 1. Successful Login
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "password"
  }'
```

**Expected Response (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9..."
}
```

Save the token for subsequent requests:
```bash
export TOKEN="eyJhbGciOiJIUzUxMiJ9..."
```

### 2. Invalid Password
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "wrongpassword"
  }'
```

**Expected Response (401 Unauthorized):**
```json
{
  "error": "Invalid credentials",
  "message": "Username or password is incorrect"
}
```

### 3. Non-existent User
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "nonexistent",
    "password": "password"
  }'
```

**Expected Response (401 Unauthorized)**

---

## Payment Operations

### 1. Create a Payment
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 100.00,
    "currency": "USD"
  }'
```

**Expected Response (200 OK):**
```json
{
  "id": "uuid-here",
  "sourceAccount": "******7890",
  "destinationAccount": "******4321",
  "amount": 100.00,
  "currency": "USD",
  "status": "COMPLETED",
  "statusDescription": "Payment completed successfully",
  "createdAt": "2026-01-24T12:00:00",
  "transactionId": "transaction-uuid",
  "message": "Payment processed successfully"
}
```

Save the payment ID:
```bash
export PAYMENT_ID="uuid-from-response"
```

### 2. Get Payment by ID
```bash
curl -X GET http://localhost:8080/api/payments/$PAYMENT_ID \
  -H "Authorization: Bearer $TOKEN"
```

**Expected Response (200 OK):** Payment details

### 3. Get All Payments (Paginated)
```bash
curl -X GET "http://localhost:8080/api/payments?page=0&size=10" \
  -H "Authorization: Bearer $TOKEN"
```

**Expected Response (200 OK):**
```json
{
  "content": [...],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 10
}
```

### 4. Get Payments by Source Account
```bash
curl -X GET "http://localhost:8080/api/payments/source-account?sourceAccount=1234567890" \
  -H "Authorization: Bearer $TOKEN"
```

### 5. Get Payments by Destination Account
```bash
curl -X GET "http://localhost:8080/api/payments/destination-account?destinationAccount=0987654321" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Payment Lifecycle Tests

### 1. Full Reversal (Refund)
```bash
# First create a payment
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 50.00,
    "currency": "USD"
  }'

# Save the payment ID, then reverse it
curl -X POST http://localhost:8080/api/payments/$PAYMENT_ID/reversal \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "reason": "Customer requested full refund for order cancellation"
  }'
```

**Expected Response (200 OK):**
```json
{
  "id": "...",
  "status": "REVERSED",
  "statusDescription": "Payment has been reversed",
  "message": "Reversal processed successfully. Amount reversed: 50.00"
}
```

### 2. Partial Reversal (Partial Refund)
```bash
# Create a payment
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 100.00,
    "currency": "USD"
  }'

# Partial reversal
curl -X POST http://localhost:8080/api/payments/$PAYMENT_ID/reversal \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "reason": "Partial refund for damaged item in order",
    "partialReversal": true,
    "reversalAmount": 30.00
  }'
```

**Expected Response (200 OK):**
```json
{
  "status": "REFUNDED",
  "statusDescription": "Payment has been partially refunded"
}
```

### 3. Update Payment Status
```bash
curl -X PATCH http://localhost:8080/api/payments/$PAYMENT_ID/status \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "status": "REVERSED"
  }'
```

---

## Validation & Error Handling

### 1. Missing Required Fields
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{}'
```

**Expected Response (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Validation Failed",
  "message": "Request validation failed. Check field errors for details.",
  "fieldErrors": [
    {"field": "sourceAccount", "message": "Source account is required"},
    {"field": "amount", "message": "Amount is required"}
  ]
}
```

### 2. Invalid Source Account Format
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "invalid",
    "destinationAccount": "0987654321",
    "amount": 100.00,
    "currency": "USD"
  }'
```

**Expected Response (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Invalid Account",
  "message": "Invalid source account"
}
```

### 3. Negative Amount
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": -100.00,
    "currency": "USD"
  }'
```

**Expected Response (400 Bad Request)**

### 4. Self-Transfer Prevention
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "1234567890",
    "amount": 100.00,
    "currency": "USD"
  }'
```

**Expected Response (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Invalid Account",
  "message": "Source and destination accounts cannot be the same"
}
```

### 5. Payment Not Found
```bash
curl -X GET http://localhost:8080/api/payments/non-existent-id \
  -H "Authorization: Bearer $TOKEN"
```

**Expected Response (404 Not Found):**
```json
{
  "status": 404,
  "error": "Payment Not Found",
  "message": "Payment not found with ID: non-existent-id"
}
```

### 6. Invalid Status Transition
```bash
# Create and complete a payment, then try invalid transition
curl -X PATCH http://localhost:8080/api/payments/$PAYMENT_ID/status \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "status": "PENDING"
  }'
```

**Expected Response (409 Conflict):**
```json
{
  "status": 409,
  "error": "Invalid Status Transition",
  "message": "Invalid status transition for payment ...: cannot transition from COMPLETED to PENDING"
}
```

### 7. Unauthorized Access
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 100.00,
    "currency": "USD"
  }'
```

**Expected Response (403 Forbidden)**

### 8. Invalid Token
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer invalid-token" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 100.00,
    "currency": "USD"
  }'
```

**Expected Response (403 Forbidden)**

### 9. Cannot Delete Completed Payment
```bash
curl -X DELETE http://localhost:8080/api/payments/$PAYMENT_ID \
  -H "Authorization: Bearer $TOKEN"
```

**Expected Response (409 Conflict):**
```json
{
  "status": 409,
  "error": "Invalid Operation",
  "message": "Cannot delete a completed payment. Use reversal instead."
}
```

### 10. Reversal Without Reason
```bash
curl -X POST http://localhost:8080/api/payments/$PAYMENT_ID/reversal \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{}'
```

**Expected Response (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Validation Failed",
  "fieldErrors": [
    {"field": "reason", "message": "Reason for reversal is required"}
  ]
}
```

---

## Rate Limiting Tests

### Test Rate Limiting (if configured with low limits)
```bash
# Run multiple requests quickly
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X GET http://localhost:8080/api/payments \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Api-Key: test-client"
done
```

Check response headers for rate limit info:
```
X-RateLimit-Limit: 10000
X-RateLimit-Remaining: 9999
X-RateLimit-Reset: timestamp
```

---

## Docker Verification

### 1. Build Docker Image
```bash
docker build -t payment-api:latest .
```

### 2. Start with Docker Compose
```bash
docker-compose up -d
```

### 3. Verify All Containers Running
```bash
docker-compose ps
```

**Expected Output:**
```
NAME              STATUS                   PORTS
payment-api       Up (healthy)             0.0.0.0:8080->8080/tcp
payment-mysql     Up (healthy)             0.0.0.0:3306->3306/tcp
payment-redis     Up (healthy)             0.0.0.0:6379->6379/tcp
```

### 4. Check Application Logs
```bash
docker-compose logs app
```

### 5. Health Check in Docker
```bash
docker exec payment-api curl -f http://localhost:8080/actuator/health
```

### 6. Test API Through Docker
```bash
# Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "password"}'

# Create payment (use token from above)
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 100.00,
    "currency": "USD"
  }'
```

### 7. Verify MySQL Data
```bash
docker exec -it payment-mysql mysql -uroot -ppassword payment_db -e "SELECT * FROM payments LIMIT 5;"
```

### 8. Verify Redis Cache
```bash
docker exec -it payment-redis redis-cli KEYS "*"
```

### 9. Stop and Clean Up
```bash
docker-compose down -v  # -v removes volumes
```

---

## Edge Cases

### 1. Very Small Amount (Minimum)
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 0.01,
    "currency": "USD"
  }'
```

### 2. Large Amount
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 999999999.99,
    "currency": "USD"
  }'
```

### 3. Currency Conversion (EUR to USD)
```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 100.00,
    "currency": "EUR"
  }'
```

### 4. Multiple Currencies (Supported)
Test with: USD, EUR, GBP, JPY, CHF, CAD, AUD, NZD, SEK, NOK, DKK, SGD, HKD, INR, CNY

### 5. Double Reversal Prevention
```bash
# After reversing a payment, try to reverse again
curl -X POST http://localhost:8080/api/payments/$REVERSED_PAYMENT_ID/reversal \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "reason": "Trying to reverse again"
  }'
```

**Expected Response (422 Unprocessable Entity):**
```json
{
  "status": 422,
  "error": "Reversal Failed",
  "message": "Cannot reverse payment with status REVERSED..."
}
```

### 6. Partial Reversal Exceeding Original Amount
```bash
curl -X POST http://localhost:8080/api/payments/$PAYMENT_ID/reversal \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "reason": "Partial refund exceeding amount",
    "partialReversal": true,
    "reversalAmount": 999999.00
  }'
```

**Expected Response (422 Unprocessable Entity)**

### 7. Concurrent Payment Creation
```bash
# Run multiple concurrent requests
for i in {1..5}; do
  curl -X POST http://localhost:8080/api/payments \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{
      \"sourceAccount\": \"123456789$i\",
      \"destinationAccount\": \"0987654321\",
      \"amount\": $((i * 10)).00,
      \"currency\": \"USD\"
    }" &
done
wait
```

---

## Test Summary Checklist

| Test Category | Test Case | Expected Status |
|---------------|-----------|-----------------|
| Health | Health check | 200 |
| Auth | Valid login | 200 |
| Auth | Invalid password | 401 |
| Auth | Non-existent user | 401 |
| Payment | Create payment | 200 |
| Payment | Get by ID | 200 |
| Payment | Get all (paginated) | 200 |
| Payment | Get by source account | 200 |
| Payment | Full reversal | 200 |
| Payment | Partial reversal | 200 |
| Validation | Missing fields | 400 |
| Validation | Invalid account | 400 |
| Validation | Negative amount | 400 |
| Validation | Self-transfer | 400 |
| Validation | Short reversal reason | 400 |
| Error | Payment not found | 404 |
| Error | Invalid status transition | 409 |
| Error | Cannot delete completed | 409 |
| Error | Double reversal | 422 |
| Security | No auth token | 403 |
| Security | Invalid token | 403 |

---

## Running Automated Tests

### Unit Tests Only
```bash
mvn test -Dtest="*Test" -DexcludeTests="*IntegrationTest,*EndToEndTest"
```

### Integration Tests Only
```bash
mvn test -Dtest="*IntegrationTest"
```

### E2E Tests Only
```bash
mvn test -Dtest="*EndToEndTest"
```

### All Tests
```bash
mvn clean test
```

**Expected Result:** All 217 tests should pass.

---

## Troubleshooting

### Application Won't Start
1. Check MySQL is running: `docker-compose ps mysql`
2. Check Redis is running: `docker-compose ps redis`
3. Check logs: `docker-compose logs app`

### Tests Failing
1. Ensure test profile is active: `-Dspring.profiles.active=test`
2. Check H2 console: `http://localhost:8080/h2-console`

### Docker Build Fails
1. Ensure Docker daemon is running
2. Check disk space: `docker system df`
3. Clean up: `docker system prune -a`

### Rate Limiting Issues
1. Check rate limit headers in response
2. Wait for reset period
3. Use different X-Api-Key for testing

---

## Contact & Support

For issues or questions, refer to:
- Swagger Documentation: `http://localhost:8080/swagger-ui/`
- Health Status: `http://localhost:8080/actuator/health`
- Application Logs: `docker-compose logs -f app`
