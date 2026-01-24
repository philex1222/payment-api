# Payment API

A RESTful API for processing payments in a banking system, featuring secure transaction management, multi-currency support, and comprehensive payment lifecycle operations including reversals and refunds.

## Technologies Used

- Java 17
- Spring Boot 2.7
- Spring Data JPA
- Spring Security
- JSON Web Tokens (JWT)
- MySQL (Production) / H2 (Testing)
- Redis (Caching)
- Swagger (API Documentation)
- Resilience4j (Rate Limiting & Circuit Breaker)
- Spring Actuator (Monitoring)
- Lombok

## Features

- Secure payment processing with JWT authentication
- Multi-currency support with currency conversion
- Payment reversals and refunds
- Audit logging for all transactions
- Rate limiting to prevent abuse
- Pagination support for payment queries
- Comprehensive error handling

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 17 or higher
- Apache Maven
- MySQL Database
- Redis Server (optional, for caching)

### Building the Application

1. Clone the repository: `git clone https://github.com/your-username/payment-api.git`
2. Navigate to the project directory: `cd payment-api`
3. Build the application: `mvn clean install`

### Running the Application

1. Configure your database connection in `application.properties`
2. Run the application: `mvn spring-boot:run`
3. The application will start running on `http://localhost:8080`

### API Documentation

The API documentation is generated using Swagger. You can access the Swagger UI at `http://localhost:8080/swagger-ui/` to explore and test the API endpoints.

### Authentication

The API uses JWT-based authentication. To access the protected endpoints:

1. Obtain a token via the login endpoint
2. Include the JWT token in the `Authorization` header: `Bearer <token>`

### API Endpoints

#### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/login` | Authenticate and generate JWT token |

#### Payments

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/payments` | Create a new payment |
| GET | `/api/payments` | Get all payments (paginated) |
| GET | `/api/payments/{id}` | Get a payment by ID |
| GET | `/api/payments/source-account?sourceAccount={account}` | Get payments by source account |
| GET | `/api/payments/destination-account?destinationAccount={account}` | Get payments by destination account |
| PATCH | `/api/payments/{id}/status` | Update payment status |
| DELETE | `/api/payments/{id}` | Delete a payment |
| POST | `/api/payments/{id}/reversal` | Initiate a payment reversal |

### Testing

The project includes unit tests and integration tests. To run the tests:

```bash
mvn test
```

## Contributing

Contributions are welcome! If you find any issues or have suggestions for improvements, please open an issue or submit a pull request.
